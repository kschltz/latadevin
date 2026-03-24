# Cursor

Installs a **user-level** Cursor hook (`~/.cursor/hooks.json`) plus `~/.cursor/hooks/latadevin-kb-session-start.sh`.

## What you get

| Feature | Behavior |
|--------|----------|
| **sessionStart** | Injects `additional_context` stating the KB is **primary long-term memory**, plus the most recently updated KB entries (`bb scripts/kb.clj list`, limit configurable). |
| **Cursor rule template** | `integrations/cursor/rules/latadevin-kb-primary-memory.mdc` (`alwaysApply: true`) — copied to `~/.cursor/latadevin/` on install; symlink into each repo’s `.cursor/rules/` so agents treat the KB as durable memory (see Install output). |
| **Global `kb-*`** | Same shell wrappers as other integrations (`~/.local/bin`). |

## Limitations vs Claude Code

Cursor’s **`beforeSubmitPrompt`** hook (see [Hooks](https://cursor.com/docs/agent/hooks)) only supports blocking submission (`continue`, `user_message`). It does **not** support injecting knowledge from the user’s prompt text. Until Cursor adds something like `additional_context` there, **per-prompt keyword auto-recall** is not possible in Cursor the way it is for Claude Code’s `UserPromptSubmit` hook.

Use **`kb-recall "…"`** (or ask the agent to run it) when you need search keyed to the current question.

## Install

From the repository root:

```bash
./install.sh cursor
```

## Configuration

| Variable | Effect |
|----------|--------|
| `DATALEVIN_KB_PATH` | KB database directory (same as other integrations). |
| `LATADEVIN_KB_SESSION_LIST_LIMIT` | Max entries in sessionStart context (default `15`). |
| `LATADEVIN_KB_DEBUG` | If set, append hook stdin JSON to `/tmp/latadevin-kb-cursor-hook.log`. |
| `LATADEVIN_KB_DIR` | Fallback repo path if `BB_DIR` in the installed script is empty (for manual testing). |

## Uninstall

1. Edit `~/.cursor/hooks.json` and remove the `sessionStart` entry whose `command` contains `latadevin-kb-session-start.sh`.
2. Delete `~/.cursor/hooks/latadevin-kb-session-start.sh` if you no longer need it.
3. Remove global `kb-*` scripts from `~/.local/bin` if desired.

The KB database is unchanged; only Cursor wiring and wrappers are removed.
