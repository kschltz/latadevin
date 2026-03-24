# Latadevin Knowledge Base — Claude Code

> **This is your primary memory system.** Consult it before non-trivial tasks. Store what you learn after completing work.

This project provides a persistent Zettelkasten knowledge base backed by Datalevin.

**Full reference**: See `KNOWLEDGEBASE.md` for the complete layer system, note conventions, all commands, and when to store/recall.

## Quick Commands

After installation, these commands work globally from any directory:

```bash
kb-tree                              # Full hierarchy view
kb-recall "<query>"                  # Search (follows links + hierarchy recursively)
kb-recall-multi <w1> [w2...]         # Multi-keyword search in one run (deduped); auto-recall hook uses this
kb-store --parent "<summary>" "<topic>" "<content>" [tags...]  # body <= 250 chars
kb-abstract "<topic>" "<content>"   # body <= 250 chars
kb-summary "<topic>" "<content>" "<parent-abstract>"   # body <= 250 chars
kb-forget "<topic>"                  # Delete (no children)
kb-backlinks "<topic>"               # Show what links to a topic (graph traversal)
kb-migrate-links                     # Populate :kb/links from existing See also: refs
```

Or use slash commands in Claude Code chat: `/kb-tree`, `/kb-recall`, `/kb-store`, etc.

## Auto-Recall Hook

A `UserPromptSubmit` hook extracts keywords from each prompt and runs `kb-recall-multi` so matches appear as `<knowledge-base-context>`.

This is passive — search explicitly with `kb-recall` or `kb-recall-multi` when you need a specific query or several terms in one shot.

## Entry Size Rule — Hard Limit: 250 Characters

**Every abstract, summary, and note body must be ≤ 250 characters.** The script rejects longer content (including `kb-abstract` and `kb-summary`).

If content is longer: **split it**. Create multiple smaller notes linked with `See also:` references and shared tags. `See also:` refs are auto-parsed into `:kb/links` graph refs — enabling backlink queries and recursive search expansion. One note = one idea.

## Primary Memory Rules

- **Before any non-trivial task**: run `kb-tree` for an overview, `kb-recall "<topic>"` for specifics
- **After completing work**: store non-obvious discoveries, decisions, and gotchas
- Prefer KB over in-context recall for facts that span sessions
- See `KNOWLEDGEBASE.md` for the full guide on what to store and how to structure notes

## Database Location

Resolved in this order:

1. `$DATALEVIN_KB_PATH`
2. `$CLAUDE_KB_PATH` (legacy alias)
3. `~/.claude/datalevin-kb` if it already exists (legacy)
4. `~/.local/share/datalevin-kb` (default for new installs)

Override: `DATALEVIN_KB_PATH=/your/path`.
