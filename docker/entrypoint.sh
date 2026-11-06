#!/bin/sh
set -eu
# JAVA_OPTS is intentionally split into JVM arguments; disable pathname expansion.
set -f
exec java ${JAVA_OPTS:--XX:MaxRAMPercentage=75} -jar /app/app.jar "$@"
