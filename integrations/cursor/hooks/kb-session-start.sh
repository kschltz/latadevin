#!/usr/bin/env bash
# Cursor sessionStart hook — injects recent Latadevin KB entries as additional_context.
# https://cursor.com/docs/agent/hooks (sessionStart output schema)
#
# Installed copy: ~/.cursor/hooks/latadevin-kb-session-start.sh
# BB_DIR is set by integrations/cursor/install.sh.
# For local testing without installing: LATADEVIN_KB_DIR=/path/to/this/repo

set -euo pipefail

BB_DIR=""

# Consume stdin (Cursor sends JSON session metadata)
INPUT=$(cat)
if [ -n "${LATADEVIN_KB_DEBUG:-}" ]; then
  echo "$INPUT" >>/tmp/latadevin-kb-cursor-hook.log
fi

ROOT="${BB_DIR:-${LATADEVIN_KB_DIR:-}}"
if [ -z "$ROOT" ] || [ ! -d "$ROOT" ]; then
  echo '{}'
  exit 0
fi

LIMIT="${LATADEVIN_KB_SESSION_LIST_LIMIT:-15}"

OUT=$(cd "$ROOT" && bb scripts/kb.clj list "$LIMIT" 2>/dev/null || true)

if [ -z "$OUT" ]; then
  echo '{}'
  exit 0
fi

if echo "$OUT" | grep -qx 'Knowledge base is empty.'; then
  echo '{}'
  exit 0
fi

HEADER="PRIMARY LONG-TERM MEMORY — Latadevin KB (this chat is not). Below is a recent sample only — before non-trivial work run kb-recall \"…\" and/or kb-tree; after discoveries use kb-store. Full text: kb-get <topic>."
CONTEXT=$(printf '%s\n\n%s\n' "$HEADER" "$OUT")

# Avoid oversized injections if the KB listing grows unexpectedly
MAX_CHARS=12000
if [ "${#CONTEXT}" -gt "$MAX_CHARS" ]; then
  CONTEXT="${CONTEXT:0:$MAX_CHARS}…"
fi

command -v jq >/dev/null 2>&1 || {
  echo '{}'
  exit 0
}

jq -n --arg ctx "$CONTEXT" '{additional_context: $ctx}'
