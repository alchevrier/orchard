#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_COMMAND="${ORCHARD_GRADLE:-$ROOT_DIR/gradlew}"
DEFAULT_MODEL="${ORCHARD_MODEL:-phi3:mini}"
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

java_is_supported() {
	if ! command -v java >/dev/null 2>&1; then
		return 1
	fi
	local version_line
	version_line="$(java -version 2>&1 | head -n 1)"
	[[ "$version_line" =~ version\ \"([0-9]+) ]] && [[ "${BASH_REMATCH[1]}" -ge 21 ]]
}

activate_java() {
	if java_is_supported; then
		return
	fi
	local brew_command=""
	if command -v brew >/dev/null 2>&1; then
		brew_command="$(command -v brew)"
	elif [[ -x /opt/homebrew/bin/brew ]]; then
		brew_command="/opt/homebrew/bin/brew"
	elif [[ -x /usr/local/bin/brew ]]; then
		brew_command="/usr/local/bin/brew"
	fi
	if [[ -n "$brew_command" ]]; then
		local java_home
		java_home="$($brew_command --prefix openjdk@21 2>/dev/null || true)"
		if [[ -x "$java_home/bin/java" ]]; then
			export JAVA_HOME="$java_home"
			export PATH="$JAVA_HOME/bin:$PATH"
			return
		fi
	fi
	local java_candidate
	for java_candidate in /usr/lib/jvm/java-21-openjdk*/bin/java; do
		if [[ -x "$java_candidate" ]]; then
			export JAVA_HOME="$(cd "$(dirname "$java_candidate")/.." && pwd)"
			export PATH="$JAVA_HOME/bin:$PATH"
			return
		fi
	done
	if [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
		local java_home
		java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
		if [[ -x "$java_home/bin/java" ]]; then
			export JAVA_HOME="$java_home"
			export PATH="$JAVA_HOME/bin:$PATH"
		fi
	fi
}

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
activate_java
require_command java
if ! java_is_supported; then
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
	if ! ollama show "$DEFAULT_MODEL" >/dev/null 2>&1; then
		echo "Ollama model $DEFAULT_MODEL is not installed. Run ./setup_orchard.sh or: ollama pull $DEFAULT_MODEL" >&2
		exit 1
	fi
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
