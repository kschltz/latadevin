# Latadevin Knowledge Base

> **This is your primary memory system.** Consult it before non-trivial tasks. Store what you learn after completing work.

This project provides a persistent, hierarchical Zettelkasten knowledge base backed by Datalevin. It works with any coding agent that can run shell commands.

**Full reference**: See `KNOWLEDGEBASE.md` for the complete layer system, note conventions, all commands, and when to store/recall.

## Quick Commands

After installation, these commands work globally from any directory:

```bash
kb-tree                              # Full hierarchy view
kb-recall "<query>"                  # Search (phrase)
kb-recall-multi <w1> [w2...]         # Multi-keyword search in one run (deduped); Claude hook uses this
kb-store --parent "<summary>" "<topic>" "<content>" [tags...]  # body <= 250 chars
kb-abstract "<topic>" "<content>"   # body <= 250 chars
kb-summary "<topic>" "<content>" "<parent-abstract>"   # body <= 250 chars
kb-forget "<topic>"                  # Delete (no children)
kb-backlinks "<topic>"               # Show what links to a topic (graph traversal)
kb-migrate-links                     # Populate :kb/links from existing See also: refs
```

## Entry Size Rule — Hard Limit: 250 Characters

**Every abstract, summary, and note body must be ≤ 250 characters.** The script rejects longer content (including `kb-abstract` and `kb-summary`).

If content is longer: **split it**. Create multiple smaller notes linked with `See also:` references and shared tags. `See also:` refs are auto-parsed into `:kb/links` graph refs — enabling backlink queries and recursive search expansion. One note = one idea.

## Primary Memory Rules

- **Before any non-trivial task**: run `kb-tree` for an overview, `kb-recall "<topic>"` for specifics
- **After completing work**: store non-obvious discoveries, decisions, and gotchas
- Prefer KB over in-context recall for facts that span sessions
- See `KNOWLEDGEBASE.md` for the full guide on what to store and how to structure notes

## Auto-Recall

If your agent supports prompt hooks, configure it to run `kb-recall` on each prompt and inject results as context. Without a hook, run `kb-recall` manually at the start of relevant tasks.

**Cursor**: `./install.sh cursor` installs a `sessionStart` hook (**`kb-recall-multi`** on workspace-folder keywords + recent **`kb list`** + “primary long-term memory” framing), copies `integrations/cursor/rules/latadevin-kb-primary-memory.mdc` to `~/.cursor/latadevin/` for linking into `.cursor/rules/`, and global **`kb-*`** wrappers (including **`kb-recall-multi`**). Cursor’s `beforeSubmitPrompt` hook cannot inject text yet, so run **`kb-recall`** / **`kb-recall-multi`** when you need search keyed to the current message (see `integrations/cursor/README.md`).

## Database Location

Resolved in this order:

1. `$DATALEVIN_KB_PATH`
2. `$CLAUDE_KB_PATH` (legacy alias)
3. `~/.claude/datalevin-kb` if it already exists (legacy)
4. `~/.local/share/datalevin-kb` (default for new installs)

Override: `DATALEVIN_KB_PATH=/your/path` (or prefix commands, e.g. `DATALEVIN_KB_PATH=/your/path kb-tree`).
