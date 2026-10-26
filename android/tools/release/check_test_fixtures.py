#!/usr/bin/env python3
"""Keep the Android and backend rhythm test resources byte-for-byte identical."""
import argparse
import hashlib
from pathlib import Path

FIXTURES = (
    'android/core-record/src/test/resources/rhythm-golden.json',
    'backend/planlock-record/src/test/resources/rhythm-golden.json',
)


def check_fixtures(root):
    root = Path(root)
    contents = []
    for name in FIXTURES:
        path = root / name
        if not path.is_file():
            raise ValueError('Missing test fixture: ' + name)
        contents.append(path.read_bytes())
    if contents[0] != contents[1]:
        raise ValueError('Test fixtures differ: ' + ' != '.join(FIXTURES))
    return hashlib.sha256(contents[0]).hexdigest()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[3])
    args = parser.parse_args()
    try:
        digest = check_fixtures(args.root)
    except ValueError as failure:
        parser.exit(1, str(failure) + '\n')
    print('Rhythm test fixtures match; SHA-256: ' + digest)
