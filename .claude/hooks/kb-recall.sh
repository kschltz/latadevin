#!/usr/bin/env bash
# Auto-recall hook for Claude Code UserPromptSubmit
# Searches the knowledge base for keywords in the user's prompt
# and injects relevant entries as <knowledge-base-context>.

set -euo pipefail

BB_DIR="/home/kschltz/shared/datalevin"

# Read JSON input from Claude Code: {"prompt": "..."}
INPUT=$(cat)
PROMPT=$(echo "$INPUT" | jq -r '.prompt // ""' 2>/dev/null || true)

if [ -z "$PROMPT" ]; then
  exit 0
fi

# Extract keywords: lowercase words >= 4 chars, deduplicated, up to 10
KEYWORDS=$(echo "$PROMPT" \
  | tr '[:upper:]' '[:lower:]' \
  | grep -oE '[a-zA-Z]{4,}' \
  | sort -u \
  | head -10 \
  | tr '\n' ' ' || true)

if [ -z "$KEYWORDS" ]; then
  exit 0
fi

# Run cascading multi-keyword search
RESULTS=$(cd "$BB_DIR" && bb scripts/kb.clj recall-multi $KEYWORDS 2>/dev/null || true)

if [ -n "$RESULTS" ]; then
  echo "<knowledge-base-context>"
  echo "$RESULTS"
  echo "</knowledge-base-context>"
fi
