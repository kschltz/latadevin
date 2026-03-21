#!/usr/bin/env bash
set -euo pipefail

# Claude Code Integration Installer
# Sets up the Latadevin KB auto-recall hook and permissions for Claude Code.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KB_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
KB_DB="${DATALEVIN_KB_PATH:-${CLAUDE_KB_PATH:-$HOME/.local/share/datalevin-kb}}"
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

LEGACY_DB="$HOME/.claude/datalevin-kb"
if [ -d "$KB_DB" ]; then
  ok "Database already exists at $KB_DB"
elif [ -d "$LEGACY_DB" ]; then
  ok "Database already exists at $LEGACY_DB (legacy path)"
else
  cd "$KB_DIR" && bb scripts/kb.clj list >/dev/null 2>&1
  ok "Created database at $KB_DB"
fi

# ── Install hook into Claude Code settings ────────────────────────

info "Configuring Claude Code..."

mkdir -p "$CLAUDE_DIR"

HOOK_SCRIPT="$SCRIPT_DIR/hooks/kb-recall.sh"

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
  cat > "$SETTINGS_FILE" <<ENDJSON
{
  "permissions": {
    "allow": [
      "Bash(bb:*)",
      "Bash(kb-*:*)"
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
  BACKUP="$SETTINGS_FILE.bak.$(date +%s)"
  cp "$SETTINGS_FILE" "$BACKUP"
  ok "Backed up existing settings to $BACKUP"

  if grep -q "kb-recall.sh" "$SETTINGS_FILE" 2>/dev/null; then
    ok "Auto-recall hook already installed"
  else
    UPDATED=$(jq --argjson hook "$HOOK_ENTRY" '
      .hooks //= {} |
      .hooks.UserPromptSubmit //= [] |
      .hooks.UserPromptSubmit += [$hook]
    ' "$SETTINGS_FILE")
    echo "$UPDATED" > "$SETTINGS_FILE"
    ok "Added auto-recall hook to UserPromptSubmit"
  fi

  for PERM in 'Bash(bb:*)' 'Bash(kb-*:*)'; do
    if jq -e --arg p "$PERM" '.permissions.allow // [] | any(. == $p)' "$SETTINGS_FILE" >/dev/null 2>&1; then
      ok "$PERM permission already present"
    else
      UPDATED=$(jq --arg p "$PERM" '
        .permissions //= {} |
        .permissions.allow //= [] |
        .permissions.allow += [$p]
      ' "$SETTINGS_FILE")
      echo "$UPDATED" > "$SETTINGS_FILE"
      ok "Added $PERM permission"
    fi
  done
fi

# ── Patch hook script with correct KB path ──────────────────────────

sed -i "s|^BB_DIR=.*|BB_DIR=\"$KB_DIR\"|" "$HOOK_SCRIPT"
ok "Hook configured to use $KB_DIR"

# ── Install global kb-* shell wrappers ───────────────────────────────

info "Installing global kb-* commands..."

BIN_DIR="$HOME/.local/bin"
mkdir -p "$BIN_DIR"

KB_COMMANDS=(
  kb-store kb-abstract kb-summary
  kb-recall kb-recall-multi
  kb-get kb-list kb-tree kb-drill
  kb-forget kb-tags kb-by-tag
)

for cmd in "${KB_COMMANDS[@]}"; do
  WRAPPER="$BIN_DIR/$cmd"
  cat > "$WRAPPER" <<WRAPPER
#!/usr/bin/env bash
cd "$KB_DIR" && exec bb $cmd "\$@"
WRAPPER
  chmod +x "$WRAPPER"
done

ok "Installed ${#KB_COMMANDS[@]} commands to $BIN_DIR"

# Warn if BIN_DIR is not on PATH
if ! echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
  warn "$BIN_DIR is not on your PATH — add it to your shell profile"
fi

# ── Install Claude Code slash commands ──────────────────────────────

info "Installing Claude Code slash commands..."

COMMANDS_DIR="$CLAUDE_DIR/commands"
mkdir -p "$COMMANDS_DIR"

install_slash_cmd() {
  local name="$1"
  local body="$2"
  cat > "$COMMANDS_DIR/${name}.md" <<MD
${body}
MD
}

install_slash_cmd "kb-tree" 'Print the full knowledge base hierarchy (abstracts → summaries → notes).

Run this command using the Bash tool and display the output to the user:

```bash
kb-tree
```'

install_slash_cmd "kb-recall" 'Search the knowledge base by topic, content, and tags.

Run this command using the Bash tool and display the output to the user:

```bash
kb-recall "$ARGUMENTS"
```'

install_slash_cmd "kb-get" 'Get a specific KB entry by its exact topic ID (e.g. "arch/my-decision").

Run this command using the Bash tool and display the output to the user:

```bash
kb-get "$ARGUMENTS"
```'

install_slash_cmd "kb-list" 'List recent knowledge base entries sorted by update time.

Run this command using the Bash tool and display the output to the user:

```bash
kb-list
```'

install_slash_cmd "kb-drill" 'Show an entry and all its children. Works at any layer: abstract shows summaries+notes, summary shows notes.

Run this command using the Bash tool and display the output to the user:

```bash
kb-drill "$ARGUMENTS"
```'

install_slash_cmd "kb-store" 'Store a note in the knowledge base.

**Syntax**: `kb-store --parent <summary-topic> [--create-parents] <topic> <content> [tags...]`

- `--parent` is required — specifies which summary this note belongs under.
- `--create-parents` is optional — auto-creates missing summary and abstract if they do not exist.
- Run `kb-tree` first to see existing summaries, or use `--create-parents` to skip that step.

**Examples**:
```bash
# Store under existing summary:
kb-store --parent "summary/my-group" "ref/my-note" "Content here." tag1 tag2

# Auto-create parent hierarchy if missing:
kb-store --create-parents --parent "summary/my-group" "ref/my-note" "Content here." tag1 tag2
```

Run this command using the Bash tool and display the output to the user:

```bash
kb-store $ARGUMENTS
```'

install_slash_cmd "kb-abstract" 'Create a top-level abstract (domain anchor). Abstracts are the root of the hierarchy.

**Syntax**: `kb-abstract <topic> <content>`

Example: `kb-abstract "abstract/my-project" "Description of the project domain."`

Run this command using the Bash tool and display the output to the user:

```bash
kb-abstract $ARGUMENTS
```'

install_slash_cmd "kb-summary" 'Create a summary under an abstract. Summaries group related notes.

**Syntax**: `kb-summary [--create-parents] <topic> <content> <parent-abstract>`

- `--create-parents` auto-creates the parent abstract if it does not exist.

Example: `kb-summary "summary/my-group" "What this group covers." "abstract/my-project"`

Run this command using the Bash tool and display the output to the user:

```bash
kb-summary $ARGUMENTS
```'

install_slash_cmd "kb-tags" 'List all tags with counts.

Run this command using the Bash tool and display the output to the user:

```bash
kb-tags
```'

install_slash_cmd "kb-by-tag" 'Find all entries with a specific tag.

Run this command using the Bash tool and display the output to the user:

```bash
kb-by-tag "$ARGUMENTS"
```'

install_slash_cmd "kb-forget" 'Delete an entry by topic ID. Entry must have no children — delete or reparent children first.

Run this command using the Bash tool and display the output to the user:

```bash
kb-forget "$ARGUMENTS"
```'

ok "Installed slash commands to $COMMANDS_DIR"

# ── Done ────────────────────────────────────────────────────────────

info ""
info "Installation complete!"
echo ""
echo "Next steps:"
echo "  1. Restart Claude Code (or start a new session)"
echo "  2. The auto-recall hook will search the KB on every prompt"
echo "  3. Run 'kb-tree' to see your knowledge base (empty on first install)"
echo ""
echo "Global commands are available from any directory: kb-tree, kb-recall, kb-store, ..."
echo "Claude Code slash commands: /kb-tree, /kb-recall, /kb-store, ..."
echo ""
echo "To use this KB as primary memory in any project:"
echo "  Claude Code:   ln -s $KB_DIR/CLAUDE.md ./CLAUDE.md"
echo "  Other agents:  cp $KB_DIR/AGENTS.md ./AGENTS.md"
echo ""
echo "Quick start:"
echo "  kb-abstract \"abstract/my-project\" \"Description of my project.\""
echo "  kb-summary \"summary/my-group\" \"What this group covers.\" \"abstract/my-project\""
echo "  kb-store --parent \"summary/my-group\" \"arch/my-note\" \"Content here.\" tag1 tag2"
echo "  kb-tree"
