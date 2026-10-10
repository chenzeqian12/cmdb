#!/usr/bin/env bash

java_major_version() {
    local output
    output=$("$1" -version 2>&1) || true
    if [[ "$output" =~ version\ \"([0-9]+) ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
    fi
}

resolve_java17() {
    local candidates=() candidate major
    [ -n "${JAVA_17_HOME:-}" ] && candidates+=("$JAVA_17_HOME/bin/java")
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME/bin/java")
    candidates+=("$(command -v java 2>/dev/null || true)")

    for candidate in "${candidates[@]}"; do
        [ -n "$candidate" ] && [ -x "$candidate" ] || continue
        major=$(java_major_version "$candidate")
        case "$major" in
            '' | *[!0-9]*) continue ;;
        esac
        if [ "$major" -ge 17 ]; then
            printf '%s' "$candidate"
            return 0
        fi
        printf 'Skipping %s: Java %s, need >= 17\n' "$candidate" "$major" >&2
    done

    printf 'No Java >= 17 found (JAVA_17_HOME=%s, JAVA_HOME=%s).\n' \
        "${JAVA_17_HOME:-<unset>}" "${JAVA_HOME:-<unset>}" >&2
    printf 'Install the OMP jdk17 component, or point JAVA_17_HOME at a JDK 17.\n' >&2
    return 1
}