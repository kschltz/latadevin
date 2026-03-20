#!/usr/bin/env bash
set -euo pipefail

# Claude Code Knowledge Base — Installer
# Sets up the Datalevin-backed KB, auto-recall hook, and permissions.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KB_DIR="$SCRIPT_DIR"
KB_DB="${CLAUDE_KB_PATH:-$HOME/.claude/datalevin-kb}"
CLAUDE_DIR="$HOME/.claude"
SETTINGS_FILE="$CLAUDE_DIR/settings.json"

# Colors (if terminal supports them)
if [ -t 1 ]; then
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  YELLOW='\033[0;33m'
  BOLD='\033[1m'
  NC='\033[0m'
else
  GREEN='' RED='' YELLOW='' BOLD='' NC=''
fi

ok()   { echo -e "${GREEN}[ok]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]${NC} $1"; }
fail() { echo -e "${RED}[error]${NC} $1"; exit 1; }
info() { echo -e "${BOLD}$1${NC}"; }

# ── Check prerequisites ──────────────────────────────────────────

info "Checking prerequisites..."

command -v bb >/dev/null 2>&1 || fail "Babashka (bb) not found. Install: https://github.com/babashka/babashka#installation"
ok "Babashka $(bb --version 2>&1 | head -1)"

command -v dtlv >/dev/null 2>&1 || fail "Datalevin (dtlv) not found. Install: https://github.com/juji-io/datalevin/blob/master/doc/install.md"
DTLV_VERSION=$(dtlv --version 2>&1 | head -1 || true)
ok "Datalevin $DTLV_VERSION"

command -v jq >/dev/null 2>&1 || fail "jq not found. Install: https://jqlang.github.io/jq/download/"
ok "jq $(jq --version 2>&1)"

# ── Initialize database ──────────────────────────────────────────

info "Initializing database..."

if [ -d "$KB_DB" ]; then
  ok "Database already exists at $KB_DB"
else
  # Running any kb command will auto-create the database
  cd "$KB_DIR" && bb scripts/kb.clj list >/dev/null 2>&1
  ok "Created database at $KB_DB"
fi

# ── Install hook into Claude Code settings ────────────────────────

info "Configuring Claude Code..."

mkdir -p "$CLAUDE_DIR"

# Ensure the hook script path is absolute
HOOK_SCRIPT="$KB_DIR/.claude/hooks/kb-recall.sh"

if [ ! -f "$HOOK_SCRIPT" ]; then
  fail "Hook script not found at $HOOK_SCRIPT"
fi

# Build the hook entry
HOOK_ENTRY=$(cat <<ENDJSON
{
  "hooks": [
    {
      "type": "command",
      "command": "bash \"$HOOK_SCRIPT\"",
      "timeout": 10
    }
  ]
}
ENDJSON
)

if [ ! -f "$SETTINGS_FILE" ]; then
  # Create settings from scratch
  cat > "$SETTINGS_FILE" <<ENDJSON
{
  "permissions": {
    "allow": [
      "Bash(bb:*)"
    ]
  },
  "hooks": {
    "UserPromptSubmit": [
      $(echo "$HOOK_ENTRY")
    ]
  }
}
ENDJSON
  ok "Created $SETTINGS_FILE with hook and permissions"
else
  # Settings file exists — merge carefully
  BACKUP="$SETTINGS_FILE.bak.$(date +%s)"
  cp "$SETTINGS_FILE" "$BACKUP"
  ok "Backed up existing settings to $BACKUP"

  # Check if hook already installed
  if grep -q "kb-recall.sh" "$SETTINGS_FILE" 2>/dev/null; then
    ok "Auto-recall hook already installed"
  else
    # Add UserPromptSubmit hook
    UPDATED=$(jq --argjson hook "$HOOK_ENTRY" '
      .hooks //= {} |
      .hooks.UserPromptSubmit //= [] |
      .hooks.UserPromptSubmit += [$hook]
    ' "$SETTINGS_FILE")
    echo "$UPDATED" > "$SETTINGS_FILE"
    ok "Added auto-recall hook to UserPromptSubmit"
  fi

  # Add bb:* permission if not present
  if jq -e '.permissions.allow // [] | any(. == "Bash(bb:*)")' "$SETTINGS_FILE" >/dev/null 2>&1; then
    ok "Bash(bb:*) permission already present"
  else
    UPDATED=$(jq '
      .permissions //= {} |
      .permissions.allow //= [] |
      .permissions.allow += ["Bash(bb:*)"]
    ' "$SETTINGS_FILE")
    echo "$UPDATED" > "$SETTINGS_FILE"
    ok "Added Bash(bb:*) permission"
  fi
fi

# ── Update hook script with correct path ──────────────────────────

# Patch the hook to use this install location
sed -i "s|^BB_DIR=.*|BB_DIR=\"$KB_DIR\"|" "$HOOK_SCRIPT"
ok "Hook configured to use $KB_DIR"

# ── Copy CLAUDE.md if in a project ────────────────────────────────

info ""
info "Installation complete!"
echo ""
echo "Next steps:"
echo "  1. Restart Claude Code (or start a new session)"
echo "  2. The auto-recall hook will search the KB on every prompt"
echo "  3. Run 'bb kb-tree' to see your knowledge base (empty on first install)"
echo ""
echo "To use from other project directories, either:"
echo "  a. Add 'cd $KB_DIR &&' before bb commands, or"
echo "  b. Copy CLAUDE.md into your project and set CLAUDE_KB_DIR=$KB_DIR"
echo ""
echo "Quick start:"
echo "  cd $KB_DIR"
echo "  bb kb-abstract \"abstract/my-project\" \"Description of my project.\""
echo "  bb kb-summary \"summary/my-group\" \"What this group covers.\" \"abstract/my-project\""
echo "  bb kb-store --parent \"summary/my-group\" \"arch/my-note\" \"Content here.\" tag1 tag2"
echo "  bb kb-tree"
