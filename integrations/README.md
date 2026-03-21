# Agent Integrations

This directory contains agent-specific installers and hooks for the Datalevin knowledge base.

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
| Claude Code | `claude-code/` | `UserPromptSubmit` hook for auto-recall |

## Adding a New Agent

1. Create `integrations/<agent-name>/`
2. Add an `install.sh` that configures the agent's settings/hooks
3. Add a hook script under `hooks/` that reads the agent's prompt format and runs `bb scripts/kb.clj recall-multi <keywords>`
4. Output matching results wrapped in whatever context format the agent expects
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
