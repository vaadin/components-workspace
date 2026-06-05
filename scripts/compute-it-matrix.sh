#!/usr/bin/env bash
# Reads a directory tree of *IT.java files and emits a GH Actions matrix
# JSON to stdout. Round-robin distributes IT classes across at most
# MAX_SHARDS buckets, aiming for TARGET_PER_SHARD classes per shard.
#
# Env overrides:
#   MAX_SHARDS        — default 12 (hard cap on parallel shards)
#   TARGET_PER_SHARD  — default 35 (per-shard class count target)
#
# Positional arg: root directory to scan (default
# flow-components/integration-tests/src/test/java). The script assumes
# mergeITs.js has already populated that directory when invoked from CI.

set -euo pipefail

MAX_SHARDS="${MAX_SHARDS:-12}"
TARGET_PER_SHARD="${TARGET_PER_SHARD:-35}"
ROOT="${1:-flow-components/integration-tests/src/test/java}"

if [ ! -d "$ROOT" ]; then
  echo "::error::Merged integration-tests source tree not found at $ROOT (did mergeITs.js run?)" >&2
  exit 1
fi

mapfile -t its < <(
  find "$ROOT" -name '*IT.java' -printf '%P\n' \
    | sed -e 's|/|.|g' -e 's|\.java$||' \
    | sort
)
count=${#its[@]}
if [ "$count" -eq 0 ]; then
  echo '{"include":[]}'
  exit 0
fi

n=$(( (count + TARGET_PER_SHARD - 1) / TARGET_PER_SHARD ))
[ "$n" -lt 1 ] && n=1
[ "$n" -gt "$MAX_SHARDS" ] && n=$MAX_SHARDS
[ "$n" -gt "$count" ] && n=$count

declare -a buckets
for ((i=1; i<=n; i++)); do buckets[$i]=""; done
i=1
for t in "${its[@]}"; do
  [ -n "${buckets[$i]}" ] && buckets[$i]+=","
  buckets[$i]+="$t"
  i=$((i+1))
  [ $i -gt $n ] && i=1
done

json='{"include":['
for ((k=1; k<=n; k++)); do
  [ $k -gt 1 ] && json+=','
  json+='{"shard":"'$k'/'$n'","tests":"'${buckets[$k]}'"}'
done
json+=']}'
echo "$json"
