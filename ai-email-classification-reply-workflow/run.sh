#!/usr/bin/env bash
# Runs the AI email classification and reply workflow with Camel JBang.
#
#   ./run.sh         start the workflow (*.camel.yaml + application.properties)
#   ./run.sh dev     same, reloading on file changes
#   ./run.sh test    integration tests with local mock APIs and a temporary SQLite database
#
# API keys, model settings and mailbox credentials are read from the
# environment or from a .env file next to this script (gitignored - never commit it).
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi

shopt -s nullglob
routes=(*.camel.yaml)
props=()
[[ -f application.properties ]] && props=(application.properties)

case "${1:-start}" in
  start | dev)
    if [[ ${#routes[@]} -eq 0 ]]; then
      echo "no *.camel.yaml in $(pwd)" >&2
      exit 1
    fi
    [[ "${1:-start}" == dev ]] && set -- --dev || set --
    exec camel run "${routes[@]}" ${props[@]+"${props[@]}"} "$@"
    ;;
  test)
    exec python3 test/run-tests.py
    ;;
  *)
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
