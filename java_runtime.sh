#!/usr/bin/env bash

orchard_java_is_supported() {
	if ! command -v java >/dev/null 2>&1; then
		return 1
	fi
	local version_line
	version_line="$(java -version 2>&1 | head -n 1)"
	[[ "$version_line" =~ version\ \"([0-9]+) ]] && [[ "${BASH_REMATCH[1]}" -ge 21 ]]
}

orchard_source_sdkman() {
	local restore_nounset=0
	local result=0
	if [[ $- == *u* ]]; then
		restore_nounset=1
		set +u
	fi
	source "$SDKMAN_DIR/bin/sdkman-init.sh" || result=$?
	if [[ "$restore_nounset" -eq 1 ]]; then
		set -u
	fi
	return "$result"
}

orchard_activate_java() {
	export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
	if [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
		# SDKMAN defines `sdk` and activates its default Java in this shell.
		orchard_source_sdkman
		if orchard_java_is_supported; then
			return
		fi
	fi
	if orchard_java_is_supported; then
		return
	fi

	local java_candidate
	for java_candidate in /usr/lib/jvm/java-*/bin/java; do
		if [[ -x "$java_candidate" ]]; then
			export JAVA_HOME="$(cd "$(dirname "$java_candidate")/.." && pwd)"
			export PATH="$JAVA_HOME/bin:$PATH"
			if orchard_java_is_supported; then
				return
			fi
		fi
	done

	if [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
		local java_home
		java_home="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
		if [[ -x "$java_home/bin/java" ]]; then
			export JAVA_HOME="$java_home"
			export PATH="$JAVA_HOME/bin:$PATH"
		fi
	fi
}

orchard_install_java() {
	orchard_activate_java
	if orchard_java_is_supported && [[ -z "${ORCHARD_JAVA_CANDIDATE:-}" ]]; then
		return
	fi

	for command in curl zip unzip; do
		if ! command -v "$command" >/dev/null 2>&1; then
			echo "SDKMAN requires $command. Install the native prerequisites and retry." >&2
			return 1
		fi
	done

	export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
	if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
		local installer
		installer="$(mktemp)"
		curl --fail --silent --show-error --location \
			"https://get.sdkman.io?ci=true&rcupdate=false" --output "$installer"
		bash "$installer"
		rm -f "$installer"
	fi

	orchard_source_sdkman
	if [[ -n "${ORCHARD_JAVA_CANDIDATE:-}" ]]; then
		sdk install java "$ORCHARD_JAVA_CANDIDATE"
	else
		sdk install java
	fi
	orchard_activate_java
	orchard_java_is_supported
}