import unittest
from phase3_publication import exact_review, validate_manifest, Blocked


class Phase3PublicationTests(unittest.TestCase):
    def test_review_cannot_approve_from_body_or_stale_identity(self):
        self.assertTrue(exact_review('APPROVED\nh\nb\nreason', ['h', 'b'], 'h', 'b', ''))
        for text in ('', 'APPROVED', 'CHANGES_REQUESTED\nh\nb\nAPPROVED',
                     'NOT_APPROVED\nh\nb', 'APPROVED\nold\nb', 'APPROVED\nh\nold',
                     '\nAPPROVED\nh\nb'):
            self.assertFalse(exact_review(text, ['h', 'b'], 'h', 'b', ''), text)
        self.assertFalse(exact_review('APPROVED\nh\nb', ['h', 'b'], 'h', 'b', '?? dirty'))
        self.assertFalse(exact_review('APPROVED\nh\nb', ['other', 'b'], 'h', 'b', ''))

    def test_delivery_refs_and_other_repositories_are_out_of_scope(self):
        m = dict(repo='owner/repo', remote='https://github.com/owner/repo.git',
                 branch='ai/phase3-verify-head-123', baseBranch='ai/phase3-verify-base-123')
        validate_manifest(m)
        for patch in (dict(branch='ai/phase3-acceptance'), dict(baseBranch='main'),
                      dict(baseBranch=m['branch']), dict(remote='https://github.com/other/repo.git')):
            with self.subTest(patch=patch), self.assertRaises(Blocked):
                validate_manifest(dict(m, **patch))

class CleanupRecoveryTests(unittest.TestCase):
    def fixture(self, directory, refs):
        import json
        from pathlib import Path
        from phase3_publication import Publication
        p = Publication.__new__(Publication)
        p.meta = Path(directory)
        p.m = dict(repo='owner/repo', account='owner', branch='ai/phase3-verify-head-x',
                   baseBranch='ai/phase3-verify-base-x')
        (p.meta / 'publication.json').write_text(json.dumps(dict(head='head', base='base', pr=dict(number=2))))
        p.authenticate = lambda: None
        p.remote = lambda name: refs.get(name)
        p.pushes = []
        def push(branch, sha, expected):
            p.pushes.append((branch, sha, expected))
            refs.pop(branch, None)
        p.push_ref = push
        def gh(*args):
            if args[-1] == '.state':
                return 'CLOSED'
            return json.dumps(dict(title='[TEST][DO NOT MERGE] Phase 3 publication acceptance',
                author=dict(login='owner'), isDraft=True, headRefOid='head', number=2,
                headRefName=p.m['branch'], baseRefName=p.m['baseBranch'], state='CLOSED', url='test-pr'))
        p.gh = gh
        return p

    def test_cleanup_resumes_after_only_head_was_deleted(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            refs = {'ai/phase3-verify-base-x': 'base'}
            p = self.fixture(d, refs)
            self.assertTrue(p.cleanup()['baseDeleted'])
            self.assertEqual([('ai/phase3-verify-base-x', None, 'base')], p.pushes)
            self.assertEqual('CLOSED', p.cleanup()['state'])
            self.assertEqual(1, len(p.pushes))

    def test_cleanup_does_not_delete_a_changed_ref(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            p = self.fixture(d, {'ai/phase3-verify-base-x': 'someone-elses-commit'})
            with self.assertRaises(Blocked):
                p.cleanup()
            self.assertEqual([], p.pushes)


if __name__ == '__main__':
    unittest.main()
