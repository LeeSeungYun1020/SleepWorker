#!/usr/bin/env python3
"""Explicit Phase 3 test-only publication. Credentials stay in child environment memory.

The manifest must identify dedicated ai/phase3-verify-* branches. This helper cannot
publish or clean up delivery PR #1 or main. It is invoked as a shell workflow node.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

TITLE = '[TEST][DO NOT MERGE] Phase 3 publication acceptance'


class Blocked(RuntimeError):
    pass


def run(argv, cwd, env=None):
    p = subprocess.run(argv, cwd=cwd, env=env, capture_output=True, text=True, timeout=90)
    if p.returncode:
        # Never echo environment/credentials. CLI stderr is unnecessary for the guard verdict.
        raise Blocked('command failed: ' + ' '.join(argv[:3]) + ' (exit ' + str(p.returncode) + ')')
    return p.stdout.strip()


def read(path):
    return json.loads(Path(path).read_text())


def save(path, data):
    Path(path).write_text(json.dumps(data, indent=2) + '\n')


def validate_manifest(m):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', m['repo']):
        raise Blocked('invalid repository')
    if m['remote'] != 'https://github.com/' + m['repo'] + '.git':
        raise Blocked('remote/repository mismatch')
    for key in ('branch', 'baseBranch'):
        if not re.fullmatch(r'ai/phase3-verify-[a-z0-9-]+', m[key]):
            raise Blocked('not a dedicated Phase 3 test ref')
    if m['branch'] == m['baseBranch']:
        raise Blocked('head and base must differ')


def exact_review(review, target, head, base, dirty):
    lines = review.splitlines()
    return len(lines) >= 3 and lines[:3] == ['APPROVED', head, base] and target == [head, base] and not dirty


class Publication:
    def __init__(self, manifest, account=None):
        self.path = Path(manifest).resolve()
        self.m = read(self.path)
        validate_manifest(self.m)
        self.cwd = Path(self.m['worktree']).resolve()
        self.meta = self.cwd / '.aiflow'
        self.env = None
        self.account = account or self.m['account']
        if self.git('remote', 'get-url', 'origin') != self.m['remote']:
            raise Blocked('actual origin differs from manifest')

    def git(self, *args):
        return run(['git', *args], self.cwd, self.env)

    def gh(self, *args):
        return run(['gh', *args], self.cwd, self.env)

    def authenticate(self):
        p = subprocess.run(['gh', 'auth', 'token', '--hostname', 'github.com', '--user', self.account],
                           capture_output=True, text=True, timeout=30)
        if p.returncode or not p.stdout.strip():
            raise Blocked('saved account credential unavailable')
        self.env = dict(os.environ, GH_TOKEN=p.stdout.strip(), GH_HOST='github.com', GIT_TERMINAL_PROMPT='0')
        if self.gh('api', 'user', '--jq', '.login') != self.m['account']:
            raise Blocked('publishing account mismatch')
        permissions = json.loads(self.gh('api', 'repos/' + self.m['repo']))['permissions']
        if not permissions.get('push', False):
            raise Blocked('publishing account has no push permission')

    def remote(self, branch):
        text = self.git('ls-remote', 'origin', 'refs/heads/' + branch)
        return text.split()[0] if text else None

    def assert_base(self, base):
        if self.remote(self.m['baseBranch']) != base:
            raise Blocked('remote base changed; re-review required')

    def prepare(self):
        # Invalidate old approval before any operation that can fail.
        (self.meta / 'REVIEW.md').unlink(missing_ok=True)
        (self.meta / 'review-target.json').unlink(missing_ok=True)
        if self.git('status', '--porcelain'):
            raise Blocked('dirty worktree')
        base = self.remote(self.m['baseBranch'])
        if not base:
            raise Blocked('remote base missing')
        target = [self.git('rev-parse', 'HEAD'), base]
        save(self.meta / 'review-target.json', target)
        return {'target': target, 'oldApprovalInvalidated': True}

    def guard(self):
        target = read(self.meta / 'review-target.json')
        head = self.git('rev-parse', 'HEAD')
        base = self.remote(self.m['baseBranch'])
        if self.git('branch', '--show-current') != self.m['branch']:
            raise Blocked('local branch changed')
        if not exact_review((self.meta / 'REVIEW.md').read_text(), target, head, base,
                            self.git('status', '--porcelain')):
            raise Blocked('review/HEAD/base/clean-worktree guard failed')
        self.authenticate()
        remote_head = self.remote(self.m['branch'])
        if remote_head not in (None, head):
            raise Blocked('remote head changed; refusing overwrite')
        self.assert_base(base)
        return head, base, remote_head

    def push_ref(self, branch, sha, expected):
        self.git('-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                 'push', 'origin', '--force-with-lease=refs/heads/' + branch + ':' + (expected or ''),
                 (sha or '') + ':refs/heads/' + branch)

    def publish(self):
        head, base, remote_head = self.guard()
        self.push_ref(self.m['branch'], head, remote_head)
        if self.remote(self.m['branch']) != head:
            raise Blocked('remote head differs after push')
        self.assert_base(base)
        query = ('pr', 'list', '--repo', self.m['repo'], '--head', self.m['branch'],
                 '--base', self.m['baseBranch'], '--state', 'open', '--json',
                 'number,url,headRefOid,isDraft,author,title')
        prs = json.loads(self.gh(*query))
        reused = bool(prs)
        if not prs:
            body = self.meta / 'test-pr-body.md'
            body.write_text('Temporary Phase 3 shell-node publication acceptance. Do not merge.\n\n'
                            'Reviewed head: ' + head + '\nReviewed base: ' + base + '\n')
            self.gh('pr', 'create', '--repo', self.m['repo'], '--head', self.m['branch'],
                    '--base', self.m['baseBranch'], '--draft', '--title', TITLE, '--body-file', str(body))
            prs = json.loads(self.gh(*query))
        if len(prs) != 1 or prs[0]['headRefOid'] != head or not prs[0]['isDraft'] or prs[0]['title'] != TITLE or prs[0]['author']['login'] != self.m['account']:
            raise Blocked('unexpected existing PR; refusing reuse')
        result = dict(head=head, base=base, branch=self.m['branch'], baseBranch=self.m['baseBranch'],
                      pr=prs[0], reused=reused)
        save(self.meta / 'publication.json', result)
        return result

    def cleanup(self):
        state = read(self.meta / 'publication.json')
        self.authenticate()
        pr = json.loads(self.gh('pr', 'view', str(state['pr']['number']), '--repo', self.m['repo'],
                               '--json', 'number,url,title,author,state,isDraft,headRefOid,headRefName,baseRefName'))
        if (pr['title'] != TITLE or pr['author']['login'] != self.m['account'] or not pr['isDraft'] or
            pr['headRefOid'] != state['head'] or pr['headRefName'] != self.m['branch'] or
            pr['baseRefName'] != self.m['baseBranch'] or pr['state'] not in ('OPEN', 'CLOSED')):
            raise Blocked('test PR ownership/state changed; refusing cleanup')
        remote_head = self.remote(self.m['branch'])
        remote_base = self.remote(self.m['baseBranch'])
        if remote_head not in (None, state['head']) or remote_base not in (None, state['base']):
            raise Blocked('test refs changed; refusing cleanup')
        if pr['state'] == 'OPEN' and (remote_head is None or remote_base is None):
            raise Blocked('open test PR lost its refs; inspect before cleanup')
        if pr['state'] == 'OPEN':
            self.gh('pr', 'close', str(pr['number']), '--repo', self.m['repo'])
        if remote_head is not None:
            self.push_ref(self.m['branch'], None, state['head'])
        if remote_base is not None:
            self.push_ref(self.m['baseBranch'], None, state['base'])
        closed = self.gh('pr', 'view', str(pr['number']), '--repo', self.m['repo'], '--json', 'state', '--jq', '.state')
        if closed != 'CLOSED' or self.remote(self.m['branch']) or self.remote(self.m['baseBranch']):
            raise Blocked('cleanup not confirmed')
        return {'prUrl': pr['url'], 'state': closed, 'headDeleted': True, 'baseDeleted': True}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['prepare-review', 'guard', 'publish', 'cleanup'])
    p.add_argument('manifest', type=Path)
    p.add_argument('--account', help='Explicit alternate saved account, for negative tests')
    args = p.parse_args()
    try:
        pub = Publication(args.manifest, args.account)
        result = {'prepare-review': pub.prepare, 'guard': pub.guard,
                  'publish': pub.publish, 'cleanup': pub.cleanup}[args.action]()
        print(json.dumps(result, ensure_ascii=False))
    except (Blocked, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        print('BLOCKED: ' + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
