#!/usr/bin/env bash

orchard_total_memory_bytes() {
	case "$(uname -s)" in
		Darwin) sysctl -n hw.memsize ;;
		Linux) awk '/^MemTotal:/ { printf "%.0f\n", $2 * 1024; exit }' /proc/meminfo ;;
		*) printf '8589934592\n' ;;
	esac
}

orchard_select_model_defaults() {
	local total_bytes="${ORCHARD_MEMORY_BYTES:-$(orchard_total_memory_bytes)}"
	local memory_gib=$((total_bytes / 1073741824))
	local platform="CLASSIC_PC"
	if [[ "$(uname -s)" == "Darwin" ]] && [[ "$(uname -m)" == "arm64" ]]; then
		platform="APPLE_SILICON"
	fi
	platform="${ORCHARD_PLATFORM:-$platform}"

	if [[ -n "${ORCHARD_MODELS:-}" ]]; then
		read -r -a ORCHARD_RECOMMENDED_MODELS <<<"${ORCHARD_MODELS//,/ }"
		ORCHARD_RECOMMENDED_PRESET="custom"
	elif [[ -n "${ORCHARD_MODEL:-}" ]]; then
		ORCHARD_RECOMMENDED_MODELS=("$ORCHARD_MODEL")
		ORCHARD_RECOMMENDED_PRESET="custom"
	elif [[ "$platform" == "APPLE_SILICON" ]]; then
		case "$memory_gib" in
			0|1|2|3|4|5|6|7|8|9|10|11|12|13|14|15)
				ORCHARD_RECOMMENDED_PRESET="apple-silicon-8gb"
				ORCHARD_RECOMMENDED_MODELS=("qwen3:4b" "qwen2.5-coder:3b") ;;
			1[6-9]|2[0-9]|3[01])
				ORCHARD_RECOMMENDED_PRESET="apple-silicon-16gb"
				ORCHARD_RECOMMENDED_MODELS=("qwen3:8b" "qwen2.5-coder:7b") ;;
			3[2-9]|4[0-9]|5[0-9]|6[0-3])
				ORCHARD_RECOMMENDED_PRESET="apple-silicon-32gb"
				ORCHARD_RECOMMENDED_MODELS=("qwen3:14b" "qwen2.5-coder:14b") ;;
			6[4-9]|7[0-9]|8[0-9]|9[0-5])
				ORCHARD_RECOMMENDED_PRESET="apple-silicon-64gb"
				ORCHARD_RECOMMENDED_MODELS=("qwen3-coder:30b") ;;
			9[6-9]|1[01][0-9]|12[0-7])
				ORCHARD_RECOMMENDED_PRESET="apple-silicon-96gb"
				ORCHARD_RECOMMENDED_MODELS=("gpt-oss:120b") ;;
			*)
				ORCHARD_RECOMMENDED_PRESET="apple-silicon-128gb"
				ORCHARD_RECOMMENDED_MODELS=("gpt-oss:120b") ;;
		esac
	else
		if ((memory_gib >= 64)); then
			ORCHARD_RECOMMENDED_PRESET="classic-pc-64gb"
			ORCHARD_RECOMMENDED_MODELS=("qwen3-coder:30b")
		elif ((memory_gib >= 32)); then
			ORCHARD_RECOMMENDED_PRESET="classic-pc-32gb"
			ORCHARD_RECOMMENDED_MODELS=("qwen3:14b" "qwen2.5-coder:14b")
		elif ((memory_gib >= 16)); then
			ORCHARD_RECOMMENDED_PRESET="classic-pc-16gb"
			ORCHARD_RECOMMENDED_MODELS=("qwen3:8b" "qwen2.5-coder:7b")
		else
			ORCHARD_RECOMMENDED_PRESET="classic-pc-8gb"
			ORCHARD_RECOMMENDED_MODELS=("qwen3:4b" "qwen2.5-coder:3b")
		fi
	fi

	export ORCHARD_RECOMMENDED_PRESET
}