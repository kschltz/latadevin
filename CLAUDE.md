# Latadevin Knowledge Base — Claude Code

> **This is your primary memory system.** Consult it before non-trivial tasks. Store what you learn after completing work.

This project provides a persistent Zettelkasten knowledge base backed by Datalevin.

**Full reference**: See `KNOWLEDGEBASE.md` for the complete layer system, note conventions, all commands, and when to store/recall.

## Quick Commands

After installation, these commands work globally from any directory:

```bash
kb-tree                              # Full hierarchy view
kb-recall "<query>"                  # Search (follows links + hierarchy recursively)
kb-store --parent "<summary>" "<topic>" "<content>" [tags...]  # content <= 1000 chars — split larger notes
kb-abstract "<topic>" "<content>"
kb-summary "<topic>" "<content>" "<parent-abstract>"
kb-forget "<topic>"                  # Delete (no children)
kb-backlinks "<topic>"               # Show what links to a topic (graph traversal)
kb-migrate-links                     # Populate :kb/links from existing See also: refs
```

Or use slash commands in Claude Code chat: `/kb-tree`, `/kb-recall`, `/kb-store`, etc.

## Auto-Recall Hook

A `UserPromptSubmit` hook automatically searches the knowledge base for keywords in every prompt. Relevant entries appear as `<knowledge-base-context>` in the conversation, with parent summary context included for navigation.

This is passive — search explicitly with `kb-recall` when you need deeper context.

## Note Size Rule — Hard Limit: 1000 Characters

**Every note must be ≤ 1000 characters.** This is enforced by the script — oversized content is rejected with an error.

If content is longer: **split it**. Create multiple smaller notes linked with `See also:` references and shared tags. `See also:` refs are auto-parsed into `:kb/links` graph refs — enabling backlink queries and recursive search expansion. One note = one idea.

## Primary Memory Rules

- **Before any non-trivial task**: run `kb-tree` for an overview, `kb-recall "<topic>"` for specifics
- **After completing work**: store non-obvious discoveries, decisions, and gotchas
- Prefer KB over in-context recall for facts that span sessions
- See `KNOWLEDGEBASE.md` for the full guide on what to store and how to structure notes

## Database Location

Default: `~/.local/share/datalevin-kb` (or `~/.claude/datalevin-kb` if that exists).
Override: set `DATALEVIN_KB_PATH=/your/path`.
