# Cursor

Installs a **user-level** Cursor hook (`~/.cursor/hooks.json`) plus `~/.cursor/hooks/latadevin-kb-session-start.sh`.

## What you get

| Feature | Behavior |
|--------|----------|
| **sessionStart** | Injects `additional_context`: **primary long-term memory** framing, **`kb-recall-multi`** on keywords derived from each workspace root’s folder name (plus optional `LATADEVIN_KB_SESSION_RECALL_KEYWORDS`), then **`kb list`** (limit configurable). If folder-derived words match nothing in the KB, only the list section appears. |
| **Cursor rule template** | `integrations/cursor/rules/latadevin-kb-primary-memory.mdc` (`alwaysApply: true`) — copied to `~/.cursor/latadevin/` on install; symlink into each repo’s `.cursor/rules/` so agents treat the KB as durable memory (see Install output). |
| **Global `kb-*`** | Shell wrappers in `~/.local/bin`, including **`kb-recall-multi`** (same as Claude install). |

## Limitations vs Claude Code

Cursor’s **`beforeSubmitPrompt`** hook (see [Hooks](https://cursor.com/docs/agent/hooks)) only supports blocking submission (`continue`, `user_message`). It does **not** support injecting knowledge from the user’s prompt text. Until Cursor adds something like `additional_context` there, **per-prompt keyword auto-recall** is not possible in Cursor the way it is for Claude Code’s `UserPromptSubmit` hook.

Use **`kb-recall "…"`** or **`kb-recall-multi word1 word2`** (or ask the agent to run them) when you need search keyed to the current question. **`sessionStart` already runs `kb-recall-multi`** on workspace folder tokens when they match KB content.

## Install

From the repository root:

```bash
./install.sh cursor
```

## Configuration

| Variable | Effect |
|----------|--------|
| `DATALEVIN_KB_PATH` | KB database directory (same as other integrations). |
| `LATADEVIN_KB_SESSION_LIST_LIMIT` | Max entries from `kb list` in sessionStart context (default `15`). |
| `LATADEVIN_KB_SESSION_RECALL_WORKSPACE` | Set to `0` to skip deriving keywords from workspace folder names. |
| `LATADEVIN_KB_SESSION_RECALL_KEYWORDS` | Space-separated extra keywords for `kb-recall-multi` at session start. |
| `LATADEVIN_KB_SESSION_RECALL_TIMEOUT` | Seconds for `kb-recall-multi` (default `20`; uses `timeout` when available). |
| `LATADEVIN_KB_SESSION_RECALL_MAX` | Max keywords passed to `kb-recall-multi` (default `10`). |
| `LATADEVIN_KB_DEBUG` | If set, append hook stdin JSON to `/tmp/latadevin-kb-cursor-hook.log`. |
| `LATADEVIN_KB_DIR` | Overrides the latadevin repo path in the hook (for manual testing). |

## Uninstall

1. Edit `~/.cursor/hooks.json` and remove the `sessionStart` entry whose `command` contains `latadevin-kb-session-start.sh`.
2. Delete `~/.cursor/hooks/latadevin-kb-session-start.sh` if you no longer need it.
3. Remove global `kb-*` scripts from `~/.local/bin` if desired.

The KB database is unchanged; only Cursor wiring and wrappers are removed.
