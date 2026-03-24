#!/usr/bin/env bash
# Cursor sessionStart hook — injects Latadevin KB context as additional_context.
# https://cursor.com/docs/agent/hooks (sessionStart output schema)
#
# Installed copy: ~/.cursor/hooks/latadevin-kb-session-start.sh
# BB_DIR is set by integrations/cursor/install.sh.
# For local testing without installing: LATADEVIN_KB_DIR=/path/to/this/repo
#
# Context includes:
#   1) kb-recall-multi on keywords from workspace folder names (+ optional env words)
#   2) kb list (recent entries)
#
# Env:
#   LATADEVIN_KB_SESSION_RECALL_WORKSPACE=0  — skip keyword extraction from workspace_roots
#   LATADEVIN_KB_SESSION_RECALL_KEYWORDS="foo bar" — extra keywords (space-separated)
#   LATADEVIN_KB_SESSION_RECALL_TIMEOUT=20     — seconds for recall-multi (default 20)
#   LATADEVIN_KB_SESSION_RECALL_MAX=10         — max keywords passed to recall-multi
#   LATADEVIN_KB_SESSION_LIST_LIMIT            — kb list limit (default 15)
#   LATADEVIN_KB_DEBUG                         — append stdin JSON to /tmp/latadevin-kb-cursor-hook.log

set -euo pipefail

# Set by integrations/cursor/install.sh to the latadevin repo (bb + scripts/kb.clj).
BB_DIR=""

# Consume stdin (Cursor sends JSON session metadata)
INPUT=$(cat)
if [ -n "${LATADEVIN_KB_DEBUG:-}" ]; then
  echo "$INPUT" >>/tmp/latadevin-kb-cursor-hook.log
fi

# LATADEVIN_KB_DIR overrides BB_DIR (e.g. local testing). Empty BB_DIR must not mask the override.
ROOT="${LATADEVIN_KB_DIR:-}"
[ -z "$ROOT" ] && ROOT="$BB_DIR"
if [ -z "$ROOT" ] || [ ! -d "$ROOT" ]; then
  echo '{}'
  exit 0
fi

LIMIT="${LATADEVIN_KB_SESSION_LIST_LIMIT:-15}"
RECALL_TIMEOUT="${LATADEVIN_KB_SESSION_RECALL_TIMEOUT:-20}"
RECALL_MAX="${LATADEVIN_KB_SESSION_RECALL_MAX:-10}"

blocked_word() {
  case "$1" in
    home|user|work|code|main|test|temp|copy|src|dist|build|node|modules|vendor|docs|linux|users) return 0 ;;
    *) return 1 ;;
  esac
}

# Keywords from workspace root basenames (same idea as Claude's prompt token filter: >= 4 chars).
keywords_from_workspace() {
  if [ "${LATADEVIN_KB_SESSION_RECALL_WORKSPACE:-1}" = "0" ]; then
    return
  fi
  echo "$INPUT" | jq -r '.workspace_roots[]? // empty' 2>/dev/null | while read -r root; do
    [ -z "$root" ] && continue
    base=$(basename "$root")
    echo "$base" | tr '[:upper:]' '[:lower:]' | tr '_-' ' '
  done | tr ' ' '\n' | grep -oE '[a-z]{4,}' || true
}

merge_keywords() {
  {
    keywords_from_workspace
    if [ -n "${LATADEVIN_KB_SESSION_RECALL_KEYWORDS:-}" ]; then
      echo "$LATADEVIN_KB_SESSION_RECALL_KEYWORDS" | tr ' ' '\n' | tr '[:upper:]' '[:lower:]' | grep -oE '[a-z]{4,}' || true
    fi
  } | while read -r w; do
    [ -z "$w" ] && continue
    blocked_word "$w" && continue
    echo "$w"
  done | sort -u | head -"$RECALL_MAX" | tr '\n' ' '
}

KEYWORDS=$(merge_keywords)
KEYWORDS=${KEYWORDS%% }
KEYWORDS=${KEYWORDS## }

RECALL_OUT=""
if [ -n "$KEYWORDS" ]; then
  if command -v timeout >/dev/null 2>&1; then
    # shellcheck disable=SC2086
    RECALL_OUT=$(cd "$ROOT" && timeout "$RECALL_TIMEOUT" bb scripts/kb.clj recall-multi $KEYWORDS 2>/dev/null || true)
  else
    # shellcheck disable=SC2086
    RECALL_OUT=$(cd "$ROOT" && bb scripts/kb.clj recall-multi $KEYWORDS 2>/dev/null || true)
  fi
fi

if [ -z "$RECALL_OUT" ] || echo "$RECALL_OUT" | grep -qx 'No results found.'; then
  RECALL_OUT=""
fi

OUT=$(cd "$ROOT" && bb scripts/kb.clj list "$LIMIT" 2>/dev/null || true)

if [ -z "$OUT" ] && [ -z "$RECALL_OUT" ]; then
  echo '{}'
  exit 0
fi

if [ -n "$OUT" ] && echo "$OUT" | grep -qx 'Knowledge base is empty.'; then
  OUT=""
fi

HEADER="PRIMARY LONG-TERM MEMORY — Latadevin KB (this chat is not). Session hook: kb-recall-multi on workspace-derived keywords (plus optional LATADEVIN_KB_SESSION_RECALL_KEYWORDS), then a recent kb list sample. Before non-trivial work still run kb-recall / kb-recall-multi / kb-tree as needed; use kb-store after discoveries. Full entry: kb-get <topic>."

PARTS=("$HEADER")

if [ -n "$RECALL_OUT" ]; then
  PARTS+=("")
  PARTS+=("--- kb-recall-multi (session keywords: $KEYWORDS) ---")
  PARTS+=("$RECALL_OUT")
fi

if [ -n "$OUT" ]; then
  PARTS+=("")
  PARTS+=("--- Recent entries (kb list $LIMIT) ---")
  PARTS+=("$OUT")
fi

CONTEXT=$(printf '%s\n' "${PARTS[@]}")

MAX_CHARS=12000
if [ "${#CONTEXT}" -gt "$MAX_CHARS" ]; then
  CONTEXT="${CONTEXT:0:$MAX_CHARS}…"
fi

command -v jq >/dev/null 2>&1 || {
  echo '{}'
  exit 0
}

jq -n --arg ctx "$CONTEXT" '{additional_context: $ctx}'
