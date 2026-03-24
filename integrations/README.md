# Agent Integrations

This directory contains agent-specific installers and hooks for the Latadevin knowledge base.

## Structure

```
integrations/
  <agent-name>/
    install.sh        # Agent-specific installer
    hooks/            # Hook scripts for the agent
```

## Supported Agents

| Agent | Directory | Notes |
|-------|-----------|-------|
| Claude Code | `claude-code/` | `UserPromptSubmit` hook for keyword auto-recall |
| Cursor | `cursor/` | `sessionStart` hook injects recent entries; see `cursor/README.md` |

## Adding a New Agent

1. Create `integrations/<agent-name>/`
2. Add an `install.sh` that configures the agent's settings/hooks
3. Add hook script(s) under `hooks/` that read the agent's stdin format and either run `bb scripts/kb.clj recall-multi <keywords>` (when the platform can inject context from the user prompt) or use lifecycle hooks that support `additional_context` (e.g. Cursor `sessionStart`)
4. Output results in the format the agent expects (plain text for Claude Code hooks, JSON on stdout for Cursor)
5. Update `README.md` to document the new integration

## Environment Variables

All integrations share these variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DATALEVIN_KB_PATH` | auto-detected | Path to the KB database |
| `CLAUDE_KB_PATH` | — | Legacy alias for `DATALEVIN_KB_PATH` (still supported) |

The database is auto-detected in this order:
1. `$DATALEVIN_KB_PATH`
2. `$CLAUDE_KB_PATH` (legacy)
3. `~/.claude/datalevin-kb` if it exists (legacy migration)
4. `~/.local/share/datalevin-kb` (new default)
