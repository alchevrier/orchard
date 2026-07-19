#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_COMMAND="${ORCHARD_GRADLE:-$ROOT_DIR/gradlew}"
source "$ROOT_DIR/model_defaults.sh"
source "$ROOT_DIR/java_runtime.sh"
orchard_select_model_defaults
SKIP_OLLAMA="${ORCHARD_SKIP_OLLAMA:-0}"
backend_pid=""
ollama_pid=""

usage() {
	cat <<'EOF'
Usage: ./run_orchard.sh [--skip-ollama]

Start Orchard's model service, backend, and desktop application. Existing
services are reused and only processes started by this launcher are stopped.

  --skip-ollama  Start Orchard with an LM Studio or remote provider
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--skip-ollama) SKIP_OLLAMA=1 ;;
		--help|-h) usage; exit 0 ;;
		*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

require_command() {
	if ! command -v "$1" >/dev/null 2>&1; then
		echo "Missing required command: $1. Run ./setup_orchard.sh first." >&2
		exit 1
	fi
}

cleanup() {
	echo "Shutting down Orchard..."
	if [[ -n "$backend_pid" ]]; then
		kill "$backend_pid" 2>/dev/null || true
	fi
	if [[ -n "$ollama_pid" ]]; then
		kill "$ollama_pid" 2>/dev/null || true
	fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$ROOT_DIR"
orchard_activate_java
require_command java
if ! orchard_java_is_supported; then
	echo "Orchard requires JDK 21 or newer. Run ./setup_orchard.sh first." >&2
	exit 1
fi
require_command git
require_command curl

if [[ "$SKIP_OLLAMA" != "1" ]] && ! curl --silent --fail http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
	require_command ollama
	echo "Starting Ollama..."
	mkdir -p "$HOME/.orchard"
	ollama serve >"$HOME/.orchard/ollama.log" 2>&1 &
	ollama_pid=$!
	for attempt in {1..30}; do
		if curl --silent --fail http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
			break
		fi
		if ! kill -0 "$ollama_pid" 2>/dev/null; then
			echo "Ollama exited before becoming ready. See $HOME/.orchard/ollama.log" >&2
			exit 1
		fi
		if [[ "$attempt" -eq 30 ]]; then
			echo "Timed out waiting for Ollama. See $HOME/.orchard/ollama.log" >&2
			exit 1
		fi
		sleep 1
	done
fi

if [[ "$SKIP_OLLAMA" != "1" ]]; then
	require_command ollama
	for model in "${ORCHARD_RECOMMENDED_MODELS[@]}"; do
		if ! ollama show "$model" >/dev/null 2>&1; then
			echo "Ollama model $model is not installed for preset $ORCHARD_RECOMMENDED_PRESET. Run ./setup_orchard.sh or: ollama pull $model" >&2
			exit 1
		fi
	done
fi

if curl --silent --fail http://127.0.0.1:8085/api/workspace >/dev/null 2>&1; then
	echo "Using the Orchard backend already running on 127.0.0.1:8085."
else
	echo "Starting Orchard backend..."
	"$GRADLE_COMMAND" :backend:jvmRun --no-daemon &
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
fi

echo "Starting Orchard desktop..."
"$GRADLE_COMMAND" :frontend:desktopRun --no-daemon
