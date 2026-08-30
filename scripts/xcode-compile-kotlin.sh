#!/usr/bin/env bash

set -euo pipefail

if [[ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" == "YES" ]]; then
    echo "Skipping Gradle build because OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED is YES."
    exit 0
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    java_candidates=(
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
    )
    for java_candidate in "${java_candidates[@]}"; do
        if [[ -x "${java_candidate}/bin/java" ]]; then
            export JAVA_HOME="${java_candidate}"
            break
        fi
    done
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "A Java 17 or newer runtime is required. Install Android Studio or set JAVA_HOME." >&2
    exit 1
fi

export PATH="${JAVA_HOME}/bin:/opt/homebrew/bin:${PATH}"
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx12288M -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=2048m}"
export KOTLIN_DAEMON_JVMARGS="${KOTLIN_DAEMON_JVMARGS:--Xmx8192M}"

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "${repository_root}"
exec ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
