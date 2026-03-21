# Datalevin Knowledge Base — Claude Code

This project provides a persistent Zettelkasten knowledge base backed by Datalevin.

**Full reference**: See `KNOWLEDGEBASE.md` for the complete layer system, note conventions, all commands, and when to store/recall.

## Quick Commands

```bash
bb kb-tree                           # Full hierarchy view
bb kb-recall "<query>"               # Search
bb kb-store --parent "<summary>" "<topic>" "<content>" [tags...]
bb kb-abstract "<topic>" "<content>"
bb kb-summary "<topic>" "<content>" "<parent-abstract>"
bb kb-forget "<topic>"               # Delete (no children)
```

## Auto-Recall Hook

A `UserPromptSubmit` hook automatically searches the knowledge base for keywords in every prompt. Relevant entries appear as `<knowledge-base-context>` in the conversation, with parent summary context included for navigation.

This is passive — search explicitly with `bb kb-recall` when you need deeper context.

## When to Use the KB

- Store what you learn: *"store that in the knowledge base"*
- Recall before non-trivial tasks: `bb kb-tree` for overview, `bb kb-recall "<topic>"` for specifics
- See `KNOWLEDGEBASE.md` for the full guide on what to store and how to structure notes

## Database Location

Default: `~/.local/share/datalevin-kb` (or `~/.claude/datalevin-kb` if that exists).
Override: set `DATALEVIN_KB_PATH=/your/path`.
