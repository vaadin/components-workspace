#!/usr/bin/env bash
# Creates web-components/node_modules/.bin -> ../../node_modules/.bin
# so wtr-utils.js can resolve its hardcoded ./node_modules/.bin/lerna path
# from cwd=web-components/ under workspace hoisting.
#
# Removes whatever is at web-components/node_modules/.bin before (re)creating
# the symlink: the GHA install cache restores this location as a real
# directory of bin-shim files (tar dereferences symlinks pointing outside
# the cached path), and a plain `ln -snf` won't replace a directory.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p web-components/node_modules
rm -rf web-components/node_modules/.bin
ln -s ../../node_modules/.bin web-components/node_modules/.bin

# Diagnostic — terse but useful when the post-symlink state is wrong.
echo "web-components/node_modules/.bin -> $(readlink web-components/node_modules/.bin)"
if [ -e node_modules/.bin/lerna ]; then
  echo "workspace-root lerna: present"
else
  echo "WARNING: workspace-root node_modules/.bin/lerna is missing"
fi
