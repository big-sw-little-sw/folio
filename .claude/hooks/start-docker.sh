#!/usr/bin/env bash
# Starts the Docker daemon in Claude Code cloud sessions so Testcontainers tests can run.
# Does nothing on local machines. Never fails the session: always exits 0.

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

if docker info >/dev/null 2>&1; then
  exit 0
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo -n"
fi

($SUDO dockerd >/tmp/dockerd.log 2>&1 &)

for _ in $(seq 1 30); do
  if docker info >/dev/null 2>&1; then
    exit 0
  fi
  sleep 1
done

echo "Docker daemon did not start within 30s; integration tests will be skipped. See /tmp/dockerd.log" >&2
exit 0
