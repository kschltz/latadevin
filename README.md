# Latadevin Knowledge Base

A local, persistent knowledge base for coding agents backed by [Datalevin](https://github.com/juji-io/datalevin) — a fast, embedded Datalog database.

Coding agents typically have flat or session-limited memory. This gives you a **hierarchical Zettelkasten** that any agent can search, browse, and build up over time across sessions. An auto-recall hook (where supported) surfaces relevant knowledge on every prompt without you doing anything.

## What it does

- **Stores knowledge** in a 3-layer hierarchy: abstracts (domains) > summaries (subfields) > notes (atomic facts)
- **Auto-recalls** relevant entries on every prompt via an agent hook (Claude Code supported; extensible to others)
- **Full-text search** across topics, content, and tags
- **Works from any project** — the database lives in `~/.local/share/datalevin-kb`, not in your repo
- **Survives context compression** — knowledge persists across sessions in a real database, not in the context window

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
git clone <this-repo> ~/latadevin-kb
cd ~/latadevin-kb
./install.sh
```

The installer defaults to Claude Code. To install for a specific agent:

```bash
./install.sh claude-code   # Claude Code (default)
./install.sh cursor        # Cursor (sessionStart hook + kb-* wrappers)
./install.sh <agent>       # Other agents (see integrations/)
```

The install script will:
1. Check that `bb` and `dtlv` are installed
2. Create the database at `~/.local/share/datalevin-kb`
3. Install the auto-recall hook into your agent's settings
4. Add `bb` permissions so the agent can use the KB commands
5. Install global `kb-*` commands to `~/.local/bin/` (work from any directory)
6. Install `/kb-*` slash commands for Claude Code (`~/.claude/commands/`)

After installing, restart your agent (or start a new session) for the hook to take effect.

## Usage

### From the command line

After install, commands work globally from any directory:

```bash
# Browse
kb-tree                           # Full hierarchy view
kb-drill "summary/my-topic"       # Drill into a topic and its children
kb-list                           # Recent entries
kb-tags                           # All tags with counts

# Search
kb-recall "search query"          # Full-text search
kb-get "arch/my-decision"         # Get specific entry
kb-by-tag "architecture"          # All notes with a tag

# Create
kb-abstract "abstract/my-domain" "Description of this domain."
kb-summary "summary/my-group" "What this group covers." "abstract/my-domain"
kb-store --parent "summary/my-group" "arch/my-note" "The actual content." tag1 tag2  # content <= 1000 chars

# Delete
kb-forget "arch/my-note"          # Must have no children

# Graph links (See also: refs are auto-parsed into :kb/links)
kb-backlinks "arch/my-note"       # What links to this topic?
kb-migrate-links                  # Backfill links from existing See also: refs
```

### From your coding agent

Agents read `CLAUDE.md` (Claude Code) or `AGENTS.md` (all others) and treat the KB as their primary memory. You can:

- Ask the agent to store what it learns: *"store that in the knowledge base"*
- Search explicitly: *"check the knowledge base for auth middleware"*
- The auto-recall hook automatically searches on every prompt — relevant entries appear as context
- In Claude Code chat, use `/kb-tree`, `/kb-recall`, etc. as slash commands

See `KNOWLEDGEBASE.md` for the complete guide.

### Using this KB from any project

Drop the right instruction file into any project root so agents in that project treat this KB as primary:

```bash
# Claude Code
ln -s ~/latadevin-kb/CLAUDE.md ./CLAUDE.md

# All other agents (Cursor, Copilot, Gemini CLI, OpenCode, etc.)
cp ~/latadevin-kb/AGENTS.md ./AGENTS.md
```

The global `kb-*` commands work from any directory once installed, so no path setup is needed.

## How it works

### Layer system

| Layer | Purpose | Has parent? | Has tags? |
|-------|---------|-------------|-----------|
| **abstract** | Broad domain anchor (e.g., "my-backend", "payments") | No | No |
| **summary** | Subfield grouping under an abstract | Yes (abstract) | No |
| **note** | Atomic fact with actual content | Yes (summary) | Yes |

### Auto-recall hook

Where supported, the install script configures a hook that:
1. Extracts keywords from your prompt
2. Runs a cascading search (abstracts > summaries > notes)
3. Injects matching entries as context that the agent sees

This is passive — it adds ~0-2 seconds per prompt and only injects when there are matches.

### Note conventions

**Notes are hard-capped at 1000 characters.** The script rejects anything longer. If content is too large, split it into multiple notes linked with `See also:` references and shared tags.

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
| `DATALEVIN_KB_PATH` | `~/.local/share/datalevin-kb` | Database location |
| `CLAUDE_KB_PATH` | — | Legacy alias (still supported) |

## Supported Agents

| Agent | Integration | Auto-recall hook |
|-------|-------------|-----------------|
| Claude Code | `integrations/claude-code/` | Yes — `UserPromptSubmit` keyword search |
| Cursor | `integrations/cursor/` | Partial — `sessionStart` injects recent KB entries; per-prompt injection is not supported by Cursor’s `beforeSubmitPrompt` API yet (see `integrations/cursor/README.md`) |

To add a new agent, see `integrations/README.md`.

## Uninstall

Remove the hook entry from your agent's settings and delete the database:

```bash
rm -rf ~/.local/share/datalevin-kb
# or if using legacy path:
rm -rf ~/.claude/datalevin-kb
```

For Claude Code: remove the `UserPromptSubmit` hook referencing `kb-recall.sh` from `~/.claude/settings.json`.

For Cursor: remove the `sessionStart` entry for `latadevin-kb-session-start.sh` from `~/.cursor/hooks.json` and delete `~/.cursor/hooks/latadevin-kb-session-start.sh` if desired (see `integrations/cursor/README.md`).

## Also included

This project includes general-purpose Datalevin `bb` tasks (Datalog store, KV store, full-text search) that work with any Datalevin database. Run `bb tasks` to see them all, or see `guide.md` for the full reference.

