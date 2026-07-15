#!/bin/bash

set -euo pipefail

backend_pid=""
cleanup() {
	echo "Shutting down Orchard..."
	if [[ -n "$backend_pid" ]]; then
		kill "$backend_pid" 2>/dev/null || true
	fi
}
trap cleanup EXIT INT TERM

echo "Starting Orchard backend..."
./gradlew :backend:jvmRun --no-daemon &
backend_pid=$!

for attempt in {1..60}; do
	if curl --silent --fail http://127.0.0.1:8085/api/workspace >/dev/null; then
		break
	fi
	if ! kill -0 "$backend_pid" 2>/dev/null; then
		echo "Orchard backend exited before becoming ready." >&2
		exit 1
	fi
	if [[ "$attempt" -eq 60 ]]; then
		echo "Timed out waiting for the Orchard backend." >&2
		exit 1
	fi
	sleep 1
done

echo "Starting Orchard desktop..."
./gradlew :frontend:desktopRun --no-daemon
