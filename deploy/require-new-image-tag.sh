#!/bin/sh
# CI guard: a version tag is assigned once. Registry/auth errors fail closed.
set -eu
[ "$#" -eq 1 ] || { printf '%s\n' 'Usage: require-new-image-tag.sh IMAGE:VERSION' >&2; exit 1; }
umask 077
diagnostic=$(mktemp)
trap 'rm -f "$diagnostic"' EXIT HUP INT TERM
if docker manifest inspect "$1" >/dev/null 2>"$diagnostic"; then
    printf '%s\n' 'This image version already exists. Choose a new version; overwriting is refused.' >&2
    exit 1
fi
if grep -Eiq 'manifest unknown|no such manifest' "$diagnostic"; then exit 0; fi
printf '%s\n' 'Cannot establish that the image tag is unused. Check registry access before publishing.' >&2
exit 1
