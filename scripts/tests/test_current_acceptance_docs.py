"""Current release notes must describe the actual candidate and privacy behaviour."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class CurrentAcceptanceDocsTest(unittest.TestCase):
    def test_acceptance_notes_identify_current_candidate_and_pending_road_test(self):
        text=(ROOT/'docs/OWNER_ACCEPTANCE.md').read_text()
        self.assertIn('0.4.0 / code 20',text)
        self.assertIn('PR #7',text)
        self.assertIn('Physical road acceptance: PENDING',text)
        self.assertIn('owner-build.json',text)
        self.assertIn('regional',text.lower())
        self.assertNotIn('Version 0.2.3 / code 12',text)
    def test_privacy_docs_describe_bounded_location_records_and_clear_policy(self):
        for file in ['README.md','docs/architecture.md']:
            text=(ROOT/file).read_text()
            self.assertIn('500',text)
            self.assertIn('Clear local diagnostics',text)
        text=(ROOT/'docs/OWNER_ACCEPTANCE.md').read_text()
        self.assertIn('backup version 13',text)
        self.assertIn('downgrade',text.lower())

class ExactAcceptanceNotesTest(unittest.TestCase):
    def test_rendered_notes_include_exact_sha_and_reject_invalid_or_outdated_metadata(self):
        import sys
        sys.path.insert(0,str(ROOT/'scripts'))
        from render_acceptance_notes import render
        text=(ROOT/'docs/OWNER_ACCEPTANCE.md').read_text()
        result=render('a'*40,text)
        self.assertTrue(result.startswith('Exact source commit: `'+('a'*40)+'`'))
        self.assertIn('Physical road acceptance: PENDING',result)
        with self.assertRaises(ValueError): render('main',text)
        with self.assertRaises(ValueError): render('a'*40,'Version 0.2.3 / code 12')
