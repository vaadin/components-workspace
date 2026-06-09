#!/usr/bin/env bash
# Reads overlay component short names from stdin (whitespace-separated) and
# emits a GH Actions matrix JSON to stdout. Modules are LPT bin-packed by
# *IT.java file count into at most MAX_SHARDS buckets, aiming for
# TARGET_PER_SHARD classes per shard.
#
# Env overrides:
#   MAX_SHARDS        — default 12 (hard cap on parallel shards)
#   TARGET_PER_SHARD  — default 35 (per-shard class count target)
#
# Positional arg: flow-components root (default flow-components). Each input
# name is mapped to <root>/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests
# and its *IT.java file count is read from src/test/java/ (counts as 0 if the
# directory is absent).

set -euo pipefail

MAX_SHARDS="${MAX_SHARDS:-12}"
TARGET_PER_SHARD="${TARGET_PER_SHARD:-35}"
ROOT="${1:-flow-components}"

if ! [[ "$MAX_SHARDS" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::MAX_SHARDS must be a positive integer (got: $MAX_SHARDS)" >&2
  exit 1
fi
if ! [[ "$TARGET_PER_SHARD" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::TARGET_PER_SHARD must be a positive integer (got: $TARGET_PER_SHARD)" >&2
  exit 1
fi

if [ ! -d "$ROOT" ]; then
  echo "::error::flow-components root not found at $ROOT" >&2
  exit 1
fi

# Read overlay short names from stdin as whitespace-separated tokens.
# Normalise newlines and tabs to spaces so callers may pipe one name per line.
input=$(cat | tr '\n\t' '  ')
read -r -a names <<<"$input"
if [ "${#names[@]}" -eq 0 ]; then
  echo '{"include":[]}'
  exit 0
fi

# Build (count, module-path) pairs.
pairs=()
for n in "${names[@]}"; do
  module="vaadin-${n}-flow-parent/vaadin-${n}-flow-integration-tests"
  abs="$ROOT/$module"
  if [ ! -d "$abs" ]; then
    echo "::error::IT module not found: $abs" >&2
    exit 1
  fi
  if [ -d "$abs/src/test/java" ]; then
    count=$(find "$abs/src/test/java" -name '*IT.java' | wc -l | tr -d ' ')
  else
    count=0
  fi
  pairs+=("$count $module")
done

# Sort descending by count (LPT input order). Ties break alphabetically.
mapfile -t sorted < <(printf '%s\n' "${pairs[@]}" | sort -k1,1nr -k2,2)

total=0
for p in "${sorted[@]}"; do
  c="${p%% *}"
  total=$(( total + c ))
done

n=$(( (total + TARGET_PER_SHARD - 1) / TARGET_PER_SHARD ))
[ "$n" -lt 1 ] && n=1
[ "$n" -gt "$MAX_SHARDS" ] && n=$MAX_SHARDS
[ "$n" -gt "${#sorted[@]}" ] && n="${#sorted[@]}"

declare -a bucket_modules bucket_counts
for ((i=0; i<n; i++)); do
  bucket_modules[$i]=""
  bucket_counts[$i]=0
done

# LPT: place each module into the currently-lightest bucket.
for p in "${sorted[@]}"; do
  c="${p%% *}"
  m="${p#* }"
  lightest=0
  for ((i=1; i<n; i++)); do
    if [ "${bucket_counts[$i]}" -lt "${bucket_counts[$lightest]}" ]; then
      lightest=$i
    fi
  done
  [ -n "${bucket_modules[$lightest]}" ] && bucket_modules[$lightest]+=","
  bucket_modules[$lightest]+="$m"
  bucket_counts[$lightest]=$(( bucket_counts[$lightest] + c ))
done

json='{"include":['
for ((k=0; k<n; k++)); do
  [ $k -gt 0 ] && json+=','
  json+='{"shard":"'$((k+1))'/'$n'","modules":"'${bucket_modules[$k]}'"}'
done
json+=']}'
echo "$json"
