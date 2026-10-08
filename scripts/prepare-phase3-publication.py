#!/usr/bin/env python3
"""Prepare a disposable clone and test base ref, plus a saved-app workflow import.
Requires explicit --execute; creates only named ai/phase3-verify-* test refs.
"""
import argparse
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import uuid
from phase3_publication import Publication, save

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--execute', action='store_true', required=True)
a = p.parse_args()
root = a.output.resolve(); root.mkdir(parents=True, exist_ok=False)
source = Path(__file__).resolve().parent.parent
repo = root / 'repo'
subprocess.run(['git', 'clone', '--no-hardlinks', str(source), str(repo)], check=True, capture_output=True)
tag = uuid.uuid4().hex[:10]
branch = 'ai/phase3-verify-head-' + tag
base = 'ai/phase3-verify-base-' + tag
remote = 'https://github.com/LeeSeungYun1020/SleepWorker.git'
subprocess.run(['git', '-C', str(repo), 'remote', 'set-url', 'origin', remote], check=True)
subprocess.run(['git', '-C', str(repo), 'switch', '-c', branch], check=True)
for key, value in [('user.name', 'Phase3 acceptance'), ('user.email', 'phase3@example.invalid')]:
    subprocess.run(['git', '-C', str(repo), 'config', key, value], check=True)
meta = repo / '.aiflow'; meta.mkdir(exist_ok=True)
manifest = meta / 'publication-manifest.json'
save(manifest, dict(repo='LeeSeungYun1020/SleepWorker', remote=remote, worktree=str(repo),
                    branch=branch, baseBranch=base, account='LeeSeungYun1020'))
helper = meta / 'phase3_publication.py'; shutil.copyfile(source / 'scripts/phase3_publication.py', helper)
pub = Publication(manifest); pub.authenticate(); sha = pub.git('rev-parse', 'HEAD')
pub.push_ref(base, sha, None)
save(root / 'setup.json', dict(base=sha, branch=branch, baseBranch=base, manifest=str(manifest), helper=str(helper)))
marker = 'PHASE3_PUBLICATION_TEST.md'
fixture = meta / 'fixture.py'
fixture.write_text("from pathlib import Path\nimport subprocess\np=Path('" + marker + "')\np.write_text('# Phase 3 publication test\\n\\nTemporary acceptance fixture. Do not merge.\\n')\nsubprocess.run(['git','add',str(p)],check=True)\nsubprocess.run(['git','commit','-m','test: isolated Phase 3 publication fixture'],check=True)\n")
q = shlex.quote
cmd = 'python3 ' + q(str(helper))
manifestarg = q(str(manifest))
def shell(id, script, next):
    return dict(id=id, kind='shell', workspace='local', script=script, timeoutSec=120,
                transitions=[dict(when='success', next=next), dict(when='otherwise', next='ask')])
review = ('Read only .aiflow/review-target.json and git diff of its second SHA (base) to first SHA (head). '
          'This is a disposable publication test, not a product implementation task. Verify that only '
          'PHASE3_PUBLICATION_TEST.md was added and says this is temporary and must not merge. '
          'Do not edit source, run tests, delegate, publish, or call other models. '
          'Write .aiflow/REVIEW.md with first line exactly APPROVED if that condition holds, otherwise CHANGES_REQUESTED; '
          'second line the exact head SHA; third line the exact base SHA; then a short rationale. Reply briefly.')
steps = [
    shell('sync', 'git fetch origin ' + q(base) + ' && test "$(git rev-parse HEAD)" = ' + q(sha), 'fixture'),
    shell('fixture', 'python3 ' + q(str(fixture)), 'verify'),
    shell('verify', 'test "$(git diff --name-only ' + sha + ' HEAD)" = ' + marker + ' && git diff --check && test -z "$(git status --porcelain)"', 'prepare-review'),
    shell('prepare-review', cmd + ' prepare-review ' + manifestarg, 'review'),
    dict(id='review', session=dict(ref='reviewer', mode='new'), model='gpt-6-luna', effort='medium',
         script=review, timeoutSec=180, completion='exitCode', transitions=[dict(when='success', next='publish'), dict(when='otherwise', next='ask')]),
    shell('publish', cmd + ' publish ' + manifestarg, 'reuse'),
    shell('reuse', cmd + ' publish ' + manifestarg, 'log'),
    shell('log', 'cat .aiflow/publication.json', 'end'),
]
w = dict(name='Phase 3 · actual publication tail (Luna review)', repoPath=str(repo), baseBranch=base,
         start='sync', maxSteps=10, sessions=dict(reviewer=dict(provider='codex', workspace='local')), steps=steps)
save(root / 'workflow.yaml', w)
print(root / 'workflow.yaml')
