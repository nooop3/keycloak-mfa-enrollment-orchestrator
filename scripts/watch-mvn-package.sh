#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/.dev"
COMMAND='mvn --batch-mode --errors --fail-at-end package && touch .dev/keycloak-restart'
WATCH_EXTENSIONS="java,kt,xml,ftl,properties"

if command -v watchexec >/dev/null 2>&1; then
  exec watchexec \
    --clear \
    --restart \
    --watch "$ROOT/src" \
    --watch "$ROOT/pom.xml" \
    --watch "$ROOT/src/main/resources" \
    --ignore "$ROOT/target" \
    --exts "$WATCH_EXTENSIONS" \
    -- bash -c "cd \"$ROOT\" && $COMMAND"
fi

if command -v entr >/dev/null 2>&1; then
  TMP_FILE="$(mktemp)"
  trap 'rm -f "$TMP_FILE"' EXIT
  find "$ROOT/src" -type f \( -name '*.java' -o -name '*.kt' -o -name '*.xml' -o -name '*.ftl' -o -name '*.properties' \) -print > "$TMP_FILE"
  echo "$ROOT/pom.xml" >> "$TMP_FILE"
  cat "$TMP_FILE" | entr -cd bash -c "cd \"$ROOT\" && $COMMAND"
  exit 0
fi

echo "watch-mvn-package: install watchexec (preferred) or entr to enable auto-packaging." >&2
exit 1
