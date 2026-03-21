#!/usr/bin/env bash
set -euo pipefail

# Datalevin Knowledge Base — Installer
# Delegates to the agent-specific installer under integrations/<agent>/install.sh
#
# Usage:
#   ./install.sh              # Install for Claude Code (default)
#   ./install.sh claude-code  # Same as above, explicit
#   ./install.sh <agent>      # Install for a specific agent

AGENT="${1:-claude-code}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INTEGRATION="$SCRIPT_DIR/integrations/$AGENT/install.sh"

if [ ! -f "$INTEGRATION" ]; then
  echo "Error: No installer found for agent '$AGENT'."
  echo "Available agents:"
  for d in "$SCRIPT_DIR/integrations"/*/; do
    echo "  $(basename "$d")"
  done
  exit 1
fi

exec bash "$INTEGRATION"
