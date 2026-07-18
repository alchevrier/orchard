#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_MODEL="${ORCHARD_MODEL:-phi3:mini}"
CHECK_ONLY=0
SKIP_MODEL=0

usage() {
	cat <<'EOF'
Usage: ./setup_orchard.sh [--check] [--skip-ollama]

Install Orchard's runtime prerequisites on macOS or Linux, download the default
Ollama model, and build the application. Existing installations are preserved.

	--check        Report missing prerequisites without installing anything
	--skip-ollama  Install the build toolchain without Ollama or its default model
EOF
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--check) CHECK_ONLY=1 ;;
		--skip-ollama|--skip-model) SKIP_MODEL=1 ;;
		--help|-h) usage; exit 0 ;;
		*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
	esac
	shift
done

log() {
	printf '\n==> %s\n' "$1"
}

have() {
	command -v "$1" >/dev/null 2>&1
}

run_privileged() {
	if [[ "$(id -u)" -eq 0 ]]; then
		"$@"
	elif have sudo; then
		sudo "$@"
	else
		echo "This installation step requires root access or sudo: $*" >&2
		exit 1
	fi
}

activate_brew() {
	if ! have brew; then
		if [[ -x /opt/homebrew/bin/brew ]]; then
			eval "$(/opt/homebrew/bin/brew shellenv)"
		elif [[ -x /usr/local/bin/brew ]]; then
			eval "$(/usr/local/bin/brew shellenv)"
		fi
	fi
}

java_is_supported() {
	if ! have java; then
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
	activate_brew
	if have brew && [[ -x "$(brew --prefix openjdk@21 2>/dev/null)/bin/java" ]]; then
		export JAVA_HOME="$(brew --prefix openjdk@21)"
		export PATH="$JAVA_HOME/bin:$PATH"
		return
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

install_homebrew() {
	activate_brew
	if have brew; then
		return
	fi
	log "Installing Homebrew"
	local installer
	installer="$(mktemp)"
	curl --fail --silent --show-error --location https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh --output "$installer"
	NONINTERACTIVE=1 /bin/bash "$installer"
	rm -f "$installer"
	activate_brew
	have brew || { echo "Homebrew installed but is not available on PATH." >&2; exit 1; }
}

install_macos() {
	install_homebrew
	local packages=(git curl openjdk@21)
	if [[ "$SKIP_MODEL" -eq 0 ]]; then
		packages+=(ollama)
	fi
	log "Installing ${packages[*]}"
	brew install "${packages[@]}"
	activate_java
}

install_linux_packages() {
	log "Installing Git, curl, JDK, and desktop runtime libraries"
	if have apt-get; then
		run_privileged apt-get update
		if ! apt-cache show openjdk-21-jdk >/dev/null 2>&1; then
			echo "This distribution does not provide OpenJDK 21. Install JDK 21 or newer, then rerun this script." >&2
			exit 1
		fi
		run_privileged apt-get install -y openjdk-21-jdk git curl ca-certificates fontconfig libfreetype6 libx11-6 libxext6 libxi6 libxrender1 libxtst6
	elif have dnf; then
		run_privileged dnf install -y java-21-openjdk-devel git curl ca-certificates fontconfig freetype libX11 libXext libXi libXrender libXtst
	elif have pacman; then
		run_privileged pacman -Syu --needed --noconfirm jdk21-openjdk git curl ca-certificates fontconfig freetype2 libx11 libxext libxi libxrender libxtst
	elif have zypper; then
		run_privileged zypper --non-interactive install java-21-openjdk-devel git curl ca-certificates fontconfig libfreetype6 libX11-6 libXext6 libXi6 libXrender1 libXtst6
	else
		echo "Unsupported Linux package manager. Install JDK 21, Git, curl, fontconfig, FreeType, and X11 desktop libraries, then rerun this script." >&2
		exit 1
	fi
}

install_ollama_linux() {
	if have ollama; then
		return
	fi
	log "Installing Ollama"
	local installer
	installer="$(mktemp)"
	curl --fail --silent --show-error --location https://ollama.com/install.sh --output "$installer"
	sh "$installer"
	rm -f "$installer"
}

start_ollama() {
	if curl --silent --fail http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
		return
	fi
	log "Starting Ollama"
	mkdir -p "$HOME/.orchard"
	nohup ollama serve >"$HOME/.orchard/ollama.log" 2>&1 &
	for attempt in {1..30}; do
		if curl --silent --fail http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
			return
		fi
		if [[ "$attempt" -eq 30 ]]; then
			echo "Ollama did not become ready. See $HOME/.orchard/ollama.log" >&2
			exit 1
		fi
		sleep 1
	done
}

check_prerequisites() {
	activate_java
	local missing=()
	if ! java_is_supported; then
		missing+=("JDK 21+")
	fi
	local commands=(git curl)
	if [[ "$SKIP_MODEL" -eq 0 ]]; then
		commands+=(ollama)
	fi
	for command in "${commands[@]}"; do
		have "$command" || missing+=("$command")
	done
	if [[ ${#missing[@]} -gt 0 ]]; then
		printf 'Missing prerequisites: %s\n' "${missing[*]}" >&2
		return 1
	fi
	printf 'Java:   %s\n' "$(java -version 2>&1 | head -n 1)"
	printf 'Git:    %s\n' "$(git --version)"
	if have ollama; then
		printf 'Ollama: %s\n' "$(ollama --version 2>&1 | head -n 1)"
	fi
}

if [[ "$CHECK_ONLY" -eq 1 ]]; then
	check_prerequisites
	exit
fi

case "$(uname -s)" in
	Darwin) install_macos ;;
	Linux)
		install_linux_packages
		if [[ "$SKIP_MODEL" -eq 0 ]]; then
			install_ollama_linux
		fi
		;;
	*) echo "Orchard setup supports macOS and Linux." >&2; exit 1 ;;
esac

activate_java
check_prerequisites

if [[ "$SKIP_MODEL" -eq 0 ]]; then
	start_ollama
	log "Downloading Ollama model $DEFAULT_MODEL"
	ollama pull "$DEFAULT_MODEL"
fi

log "Building Orchard and downloading Gradle dependencies"
cd "$ROOT_DIR"
./gradlew build --no-daemon

cat <<EOF

Orchard is ready.

Start it with:
  ./run_orchard.sh

EOF

if [[ "$SKIP_MODEL" -eq 0 ]]; then
	cat <<EOF

Default model:
	$DEFAULT_MODEL
EOF
fi