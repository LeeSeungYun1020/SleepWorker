#!/usr/bin/env python3
"""Exercise publication guards against real Git refs and explicit test-only GitHub refs.
Run after the app's publish/reuse steps and before cleanup. No model calls.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
from phase3_publication import Publication, Blocked, save

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('manifest', type=Path)
p.add_argument('--read-only-account', required=True)
a = p.parse_args()
pub = Publication(a.manifest); pub.authenticate()
m = pub.m; meta = pub.meta
state = json.loads((meta / 'publication.json').read_text())
head, base = state['head'], state['base']
original_review = (meta / 'REVIEW.md').read_text()
original_target = (meta / 'review-target.json').read_text()
results = []


def pr_state():
    return json.loads(pub.gh('pr', 'view', str(state['pr']['number']), '--repo', m['repo'], '--json', 'state,headRefOid,isDraft'))


before_pr = dict(state='OPEN', headRefOid=head, isDraft=True)
def assert_pr_restored():
    # Git refs update before GraphQL's PR projection; wait boundedly for that projection.
    deadline = time.monotonic() + 20
    while True:
        observed = pr_state()
        if observed == before_pr:
            return
        if time.monotonic() >= deadline:
            raise AssertionError('PR projection did not converge: ' + json.dumps(observed))
        time.sleep(1)
assert_pr_restored()
def attempt(name, setup, restore, expected, factory=lambda: Publication(a.manifest), action='publish'):
    try:
        setup()
        try:
            getattr(factory(), action)()
        except Blocked as e:
            if not any(reason in str(e) for reason in ((expected,) if isinstance(expected, str) else expected)):
                raise AssertionError(name + ': wrong rejection: ' + str(e))
            results.append(dict(case=name, status='PASS', rejection=str(e), source='actual local Git / live GitHub probes'))
        else:
            raise AssertionError(name + ': unsafe operation accepted')
    finally:
        restore()
    assert pub.remote(m['branch']) == head and pub.remote(m['baseBranch']) == base
    assert_pr_restored()
    save(meta / 'fault-results.json', results)


def review(text):
    (meta / 'REVIEW.md').write_text(text)


for name, text in [
    ('changes-requested-with-approved-in-body', 'CHANGES_REQUESTED\n' + head + '\n' + base + '\nAPPROVED'),
    ('empty-review', ''), ('malformed-review', 'APPROVED\n' + head),
    ('stale-reviewed-head', 'APPROVED\n' + base + '\n' + base),
    ('stale-reviewed-base', 'APPROVED\n' + head + '\n' + head),
]:
    attempt(name, lambda text=text: review(text), lambda: review(original_review), 'guard failed')

probe = pub.cwd / 'phase3-dirty-probe.tmp'
assert not probe.exists()
attempt('dirty-worktree', lambda: probe.write_text('owned temporary fixture'), lambda: probe.unlink(missing_ok=True), 'guard failed')
attempt('stale-review-target', lambda: save(meta / 'review-target.json', [base, base]),
        lambda: (meta / 'review-target.json').write_text(original_target), 'guard failed')
attempt('detached-or-switched-branch', lambda: pub.git('switch', '--detach', head),
        lambda: pub.git('switch', m['branch']), 'local branch changed')
advanced = pub.git('commit-tree', pub.git('rev-parse', head + '^{tree}'), '-p', head, '-m', 'owned guard mutation')
attempt('local-head-advanced', lambda: pub.git('update-ref', 'refs/heads/' + m['branch'], advanced, head),
        lambda: pub.git('update-ref', 'refs/heads/' + m['branch'], head, advanced), 'guard failed')
advanced_base = pub.git('commit-tree', pub.git('rev-parse', base + '^{tree}'), '-p', base, '-m', 'owned remote base mutation')
attempt('live-remote-base-moved', lambda: pub.push_ref(m['baseBranch'], advanced_base, base),
        lambda: pub.push_ref(m['baseBranch'], base, advanced_base), 'guard failed')
attempt('live-remote-head-moved', lambda: pub.push_ref(m['branch'], advanced, head),
        lambda: pub.push_ref(m['branch'], head, advanced), 'remote head changed')
attempt('cleanup-ref-changed', lambda: pub.push_ref(m['branch'], advanced, head),
        lambda: pub.push_ref(m['branch'], head, advanced), ('test refs changed', 'test PR ownership/state changed'), action='cleanup')
attempt('wrong-origin', lambda: pub.git('remote', 'set-url', 'origin', 'https://github.com/invalid/invalid.git'),
        lambda: pub.git('remote', 'set-url', 'origin', m['remote']), 'actual origin differs')
attempt('wrong-saved-account', lambda: None, lambda: None, 'publishing account mismatch',
        factory=lambda: Publication(a.manifest, a.read_only_account))
other_manifest = meta / 'readonly-manifest.json'
save(other_manifest, dict(m, account=a.read_only_account))
attempt('live-read-only-permission', lambda: None, lambda: None, 'no push permission',
        factory=lambda: Publication(other_manifest))
# Confirm server-side write rejection too, using a non-mutating push dry-run.
readonly = Publication(other_manifest)
try:
    readonly.authenticate()
except Blocked as e:
    assert 'no push permission' in str(e)
else:
    raise AssertionError('Account is not read-only; do not run denial probe')
probe_branch = m['branch'] + '-denied'
assert pub.remote(probe_branch) is None
r = subprocess.run(['git', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                    'push', '--dry-run', 'origin', 'HEAD:refs/heads/' + probe_branch], cwd=pub.cwd,
                   env=readonly.env, capture_output=True, text=True, timeout=60)
assert r.returncode != 0 and ('403' in r.stderr or 'denied' in r.stderr.lower()), 'No measured permission denial'
assert pub.remote(probe_branch) is None
results.append(dict(case='live-server-write-denial-dry-run', status='PASS', exitCode=r.returncode,
                    source='live GitHub git receive authorization; no ref created'))
# Even failed prepare must invalidate an old approval.
probe.write_text('owned temporary dirty state')
try:
    try:
        Publication(a.manifest).prepare()
    except Blocked:
        pass
    else:
        raise AssertionError('dirty prepare unexpectedly succeeded')
    assert not (meta / 'REVIEW.md').exists() and not (meta / 'review-target.json').exists()
    results.append(dict(case='prepare-invalidates-before-failure', status='PASS', source='actual filesystem and Git'))
finally:
    probe.unlink(missing_ok=True)
    review(original_review)
    (meta / 'review-target.json').write_text(original_target)
assert_pr_restored()
save(meta / 'fault-results.json', results)
print(json.dumps({'status': 'PASS', 'cases': len(results), 'prUnchanged': True, 'refsRestored': True}))
