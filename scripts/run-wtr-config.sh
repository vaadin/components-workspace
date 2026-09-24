#!/usr/bin/env bash
# Runs web-test-runner (via npm test) with the given config file, honoring
# the COMPONENTS env var. When COMPONENTS is empty: --all. When set: loop
# --group per name. Pass an empty string as the config argument to invoke
# the default Chrome unit suite (no --config).
# Caller is responsible for `cd`-ing into web-components/ before running.
set -euo pipefail
config="${1:-}"
config_arg=()
[ -n "$config" ] && config_arg=(--config "$config")
if [ -z "${COMPONENTS:-}" ]; then
  npm test -- "${config_arg[@]+"${config_arg[@]}"}" --all
else
  for c in $COMPONENTS; do
    npm test -- "${config_arg[@]+"${config_arg[@]}"}" --group "$c"
  done
fi
