#!/usr/bin/env bash
set -euo pipefail

# Cursor integration — Latadevin Knowledge Base
# - sessionStart hook: kb-recall-multi on workspace folder keywords + kb list + framing (additional_context)
# - Cursor rule template → ~/.cursor/latadevin/ (symlink into each repo’s .cursor/rules/)
# - Global kb-* wrappers in ~/.local/bin (includes kb-recall-multi)
#
# Cursor's beforeSubmitPrompt cannot inject context (only continue / user_message);
# per-prompt keyword recall like Claude Code is not available until Cursor extends that hook.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KB_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
KB_DB="${DATALEVIN_KB_PATH:-${CLAUDE_KB_PATH:-$HOME/.local/share/datalevin-kb}}"
CURSOR_DIR="$HOME/.cursor"
HOOKS_DIR="$CURSOR_DIR/hooks"
HOOKS_JSON="$CURSOR_DIR/hooks.json"
SOURCE_HOOK="$SCRIPT_DIR/hooks/kb-session-start.sh"
INSTALLED_HOOK="$HOOKS_DIR/latadevin-kb-session-start.sh"
HOOK_REL="./hooks/latadevin-kb-session-start.sh"

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

info "Checking prerequisites..."

command -v bb >/dev/null 2>&1 || fail "Babashka (bb) not found. Install: https://github.com/babashka/babashka#installation"
ok "Babashka $(bb --version 2>&1 | head -1)"

command -v dtlv >/dev/null 2>&1 || fail "Datalevin (dtlv) not found. Install: https://github.com/juji-io/datalevin/blob/master/doc/install.md"
ok "Datalevin $(dtlv --version 2>&1 | head -1)"

command -v jq >/dev/null 2>&1 || fail "jq not found. Install: https://jqlang.github.io/jq/download/"
ok "jq $(jq --version 2>&1)"

info "Initializing database..."

LEGACY_DB="$HOME/.claude/datalevin-kb"
if [ -d "$KB_DB" ]; then
  ok "Database already exists at $KB_DB"
elif [ -d "$LEGACY_DB" ]; then
  ok "Database already exists at $LEGACY_DB (legacy path)"
else
  cd "$KB_DIR" && bb scripts/kb.clj list >/dev/null 2>&1
  ok "Created database at $KB_DB"
fi

info "Installing Cursor hook..."

if [ ! -f "$SOURCE_HOOK" ]; then
  fail "Hook script not found at $SOURCE_HOOK"
fi

mkdir -p "$HOOKS_DIR"
cp "$SOURCE_HOOK" "$INSTALLED_HOOK"
chmod +x "$INSTALLED_HOOK"

sed -i "s|^BB_DIR=.*|BB_DIR=\"$KB_DIR\"|" "$INSTALLED_HOOK"
ok "Hook installed at $INSTALLED_HOOK (BB_DIR=$KB_DIR)"

NEW_ENTRY=$(jq -n --arg cmd "$HOOK_REL" '[{command: $cmd, timeout: 20}]')

if [ -f "$HOOKS_JSON" ] && grep -q 'latadevin-kb-session-start' "$HOOKS_JSON" 2>/dev/null; then
  ok "Latadevin sessionStart hook already referenced in $HOOKS_JSON"
else
  if [ -f "$HOOKS_JSON" ]; then
    BACKUP="$HOOKS_JSON.bak.$(date +%s)"
    cp "$HOOKS_JSON" "$BACKUP"
    ok "Backed up existing hooks to $BACKUP"
  fi

  if [ ! -f "$HOOKS_JSON" ]; then
    jq -n --argjson entry "$NEW_ENTRY" '{version: 1, hooks: {sessionStart: $entry}}' >"$HOOKS_JSON"
    ok "Created $HOOKS_JSON"
  else
    if ! jq empty "$HOOKS_JSON" 2>/dev/null; then
      fail "$HOOKS_JSON is not valid JSON — fix it manually, then re-run this installer"
    fi
    jq --argjson entry "$NEW_ENTRY" '
      .version //= 1 |
      .hooks //= {} |
      .hooks.sessionStart //= [] |
      .hooks.sessionStart += $entry
    ' "$HOOKS_JSON" >"$HOOKS_JSON.tmp" && mv "$HOOKS_JSON.tmp" "$HOOKS_JSON"
    ok "Merged sessionStart hook into $HOOKS_JSON"
  fi
fi

info "Installing global kb-* commands..."

BIN_DIR="$HOME/.local/bin"
mkdir -p "$BIN_DIR"

KB_COMMANDS=(
  kb-store kb-abstract kb-summary
  kb-recall kb-recall-multi
  kb-get kb-list kb-tree kb-drill
  kb-forget kb-tags kb-by-tag
  kb-backlinks kb-migrate-links
)

for cmd in "${KB_COMMANDS[@]}"; do
  WRAPPER="$BIN_DIR/$cmd"
  cat >"$WRAPPER" <<WRAPPER
#!/usr/bin/env bash
cd "$KB_DIR" && exec bb $cmd "\$@"
WRAPPER
  chmod +x "$WRAPPER"
done

ok "Installed ${#KB_COMMANDS[@]} commands to $BIN_DIR"

RULE_SRC="$SCRIPT_DIR/rules/latadevin-kb-primary-memory.mdc"
RULE_HOME_DIR="$HOME/.cursor/latadevin"
RULE_HOME="$RULE_HOME_DIR/latadevin-kb-primary-memory.mdc"

if [ ! -f "$RULE_SRC" ]; then
  warn "Cursor rule template missing at $RULE_SRC (KB memory hint not installed)"
else
  mkdir -p "$RULE_HOME_DIR"
  cp "$RULE_SRC" "$RULE_HOME"
  ok "Cursor rule installed at $RULE_HOME (alwaysApply: KB = primary long-term memory)"
fi

if ! echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
  warn "$BIN_DIR is not on your PATH — add it to your shell profile"
fi

info ""
info "Installation complete!"
echo ""
echo "Cursor loads ~/.cursor/hooks.json automatically. Start a new Agent chat (or reload the window)"
echo "to pick up the sessionStart hook — recent KB entries appear as additional context."
echo ""
echo "Per-prompt keyword recall (like Claude Code) is not supported: Cursor's beforeSubmitPrompt"
echo "hook cannot inject context yet. Use kb-recall or kb-recall-multi from the agent when you need search."
echo ""
echo "Global commands (all projects): kb-tree, kb-recall, kb-recall-multi, kb-store, kb-get, …"
echo "sessionStart runs kb-recall-multi on words from workspace folder names (see integrations/cursor/README.md)."
echo ""
echo "KB as primary long-term memory in Cursor: add the project rule to each repo's .cursor/rules:"
echo "  mkdir -p .cursor/rules && ln -sf $RULE_HOME .cursor/rules/latadevin-kb-primary-memory.mdc"
echo "  (or: cp $RULE_HOME .cursor/rules/latadevin-kb-primary-memory.mdc)"
echo ""
echo "Use this KB in any project:  cp $KB_DIR/AGENTS.md ./AGENTS.md"
echo "Or symlink:  ln -s $KB_DIR/AGENTS.md ./AGENTS.md"
echo ""
