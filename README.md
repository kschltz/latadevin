# Claude Code Knowledge Base

A local, persistent knowledge base for [Claude Code](https://claude.com/claude-code) backed by [Datalevin](https://github.com/juji-io/datalevin) — a fast, embedded Datalog database.

Claude Code's built-in memory is flat key-value pairs. This gives you a **hierarchical Zettelkasten** that Claude can search, browse, and build up over time across conversations. An auto-recall hook surfaces relevant knowledge on every prompt without you doing anything.

## What it does

- **Stores knowledge** in a 3-layer hierarchy: abstracts (domains) > summaries (subfields) > notes (atomic facts)
- **Auto-recalls** relevant entries on every prompt via a Claude Code hook
- **Full-text search** across topics, content, and tags
- **Works from any project** — the database lives in `~/.claude/datalevin-kb`, not in your repo
- **Survives context compression** — knowledge persists across conversations in a real database, not in the context window

## Prerequisites

1. **[Babashka](https://github.com/babashka/babashka#installation)** (bb) — Clojure scripting runtime
2. **[Datalevin](https://github.com/juji-io/datalevin/blob/master/doc/install.md)** (dtlv) v0.10.7 — the database engine, used as a Babashka pod

Verify both are installed:

```bash
bb --version    # should show 1.x.x
dtlv --version  # should show 0.10.7
```

## Install

```bash
git clone <this-repo> ~/claude-kb
cd ~/claude-kb
./install.sh
```

The install script will:
1. Check that `bb` and `dtlv` are installed
2. Create the database at `~/.claude/datalevin-kb`
3. Install the auto-recall hook into your Claude Code settings
4. Add `bb` permissions so Claude can use the KB commands

After installing, restart Claude Code (or start a new session) for the hook to take effect.

## Usage

### From the command line

```bash
# Browse
bb kb-tree                        # Full hierarchy view
bb kb-drill "summary/my-topic"    # Drill into a topic and its children
bb kb-list                        # Recent entries
bb kb-tags                        # All tags with counts

# Search
bb kb-recall "search query"       # Full-text search
bb kb-get "arch/my-decision"      # Get specific entry
bb kb-by-tag "architecture"       # All notes with a tag

# Create
bb kb-abstract "abstract/my-domain" "Description of this domain."
bb kb-summary "summary/my-group" "What this group covers." "abstract/my-domain"
bb kb-store --parent "summary/my-group" "arch/my-note" "The actual content." tag1 tag2

# Delete
bb kb-forget "arch/my-note"       # Must have no children
```

### From Claude Code

Claude reads the `CLAUDE.md` file and knows how to use all the KB commands. You can:

- Ask Claude to store what it learns: *"store that in the knowledge base"*
- Search explicitly: *"check the knowledge base for auth middleware"*
- The auto-recall hook automatically searches on every prompt — relevant entries appear as context

## How it works

### Layer system

| Layer | Purpose | Has parent? | Has tags? |
|-------|---------|-------------|-----------|
| **abstract** | Broad domain anchor (e.g., "my-backend", "payments") | No | No |
| **summary** | Subfield grouping under an abstract | Yes (abstract) | No |
| **note** | Atomic fact with actual content | Yes (summary) | Yes |

### Auto-recall hook

The install script configures a `UserPromptSubmit` hook that:
1. Extracts keywords from your prompt
2. Runs a cascading search (abstracts > summaries > notes)
3. Injects matching entries as `<knowledge-base-context>` that Claude sees

This is passive — it adds ~0-2 seconds per prompt and only injects when there are matches.

### Note conventions

Notes use prefixed topic IDs for easy navigation:

| Prefix | Meaning | Examples |
|---|---|---|
| `arch/` | Architecture decisions | `arch/api-framework` |
| `ops/` | Deployment, infra, CI | `ops/staging-deploy` |
| `bug/` | Bug root causes & fixes | `bug/race-condition` |
| `how/` | Procedures & recipes | `how/run-migrations` |
| `ref/` | Reference facts | `ref/api-endpoints` |
| `ctx/` | Project context & history | `ctx/why-monorepo` |
| `tool/` | Tool configs & gotchas | `tool/docker-compose` |
| `pref/` | User preferences | `pref/code-style` |

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `CLAUDE_KB_PATH` | `~/.claude/datalevin-kb` | Database location |
| `CLAUDE_KB_DIR` | `~/claude-kb` | Where this repo is cloned (used by the hook) |

## Uninstall

Remove the hook entry from your Claude Code settings and delete the database:

```bash
rm -rf ~/.claude/datalevin-kb
```

The hook is in either `.claude/settings.local.json` (project-level) or `~/.claude/settings.json` (global). Look for the `UserPromptSubmit` hook referencing `kb-recall.sh`.

## Also included

This project includes general-purpose Datalevin `bb` tasks (Datalog store, KV store, full-text search) that work with any Datalevin database. Run `bb tasks` to see them all, or see `guide.md` for the full reference.
