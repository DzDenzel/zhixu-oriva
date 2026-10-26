"""Regression checks for missing and divergent cross-client golden fixtures."""
from pathlib import Path
import tempfile
import unittest

from check_test_fixtures import FIXTURES, check_fixtures


class FixtureConsistencyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='oriva-fixtures-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in FIXTURES:
            path = self.root / name
            path.parent.mkdir(parents=True)
            path.write_bytes(b'{"cases":[]}\n')

    def test_matching_resources_pass(self):
        self.assertEqual(len(check_fixtures(self.root)), 64)

    def test_changed_resource_fails_and_names_both_files(self):
        (self.root / FIXTURES[1]).write_bytes(b'{"cases":[{}]}\n')
        with self.assertRaises(ValueError) as raised:
            check_fixtures(self.root)
        for name in FIXTURES:
            self.assertIn(name, str(raised.exception))

    def test_each_missing_resource_fails_with_its_relative_path(self):
        for name in FIXTURES:
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_bytes()
                path.unlink()
                try:
                    with self.assertRaisesRegex(ValueError, name):
                        check_fixtures(self.root)
                finally:
                    path.write_bytes(original)


if __name__ == '__main__':
    unittest.main()
