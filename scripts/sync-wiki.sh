#!/bin/sh
# Copies docs/wiki/*.md into a checkout of the GitHub wiki, removing pages that
# no longer exist in docs/wiki. Usage: sh scripts/sync-wiki.sh <wiki checkout>
set -eu
dest="${1:?usage: sync-wiki.sh <wiki checkout>}"
src="$(dirname "$0")/../docs/wiki"
[ -d "$dest/.git" ] || { echo "not a git checkout: $dest" >&2; exit 1; }
for page in "$dest"/*.md; do
  [ -e "$page" ] || continue
  [ -e "$src/$(basename "$page")" ] || rm -f "$page"
done
cp "$src"/*.md "$dest"/
echo "synced $(find "$src" -name "*.md" | wc -l) pages"
