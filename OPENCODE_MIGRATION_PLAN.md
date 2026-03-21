# Opencode Adaptation Plan for `CLAUDE.md`

## Goal
Adapt the project guidance so the same Datalevin knowledge-base workflow works cleanly in Opencode sessions, while preserving backward compatibility for Claude Code users.

## Current State (from repo inspection)
- `CLAUDE.md` is written entirely for Claude Code behavior and terminology.
- `install.sh` configures Claude settings at `~/.claude/settings.json` and expects a Claude hook (`UserPromptSubmit`) at `.claude/hooks/kb-recall.sh`.
- The actual KB commands (`bb kb-*`) are tool-agnostic and already suitable for Opencode.

## Proposed Adaptation Strategy
1. Keep `CLAUDE.md` focused on knowledge-model semantics (layers, note format, naming, when to store/recall).
2. Move agent/runtime-specific instructions into a new companion doc: `AGENT_SETUP.md`.
3. Add an Opencode section that explains how to run the same `bb kb-*` commands inside Opencode.
4. Mark Claude-only hook behavior as optional/integration-specific, not universal.

## Concrete Documentation Changes

### 1) `CLAUDE.md` (minimal, stable core)
- Replace Claude-exclusive phrasing with neutral wording:
  - "for Claude Code" -> "for coding agents (Claude Code, Opencode, etc.)"
  - "Claude reads this file" -> "Your coding agent can follow this file"
- Keep all Zettelkasten rules and command examples unchanged.
- Rewrite "Auto-Recall Hook" section to be integration-neutral:
  - Clarify that automatic injection depends on host/client hook support.
  - Keep manual fallback: explicit `bb kb-recall` before/within tasks.
- Add a short "Agent Interop" subsection:
  - Works in Opencode via terminal command execution.
  - Works in Claude Code with existing `UserPromptSubmit` hook integration.

### 2) New `AGENT_SETUP.md`
Include runtime-specific setup matrix:

- Claude Code
  - Existing install flow (`install.sh`, `~/.claude/settings.json`, UserPromptSubmit hook).
- Opencode
  - Manual-first usage: run `bb kb-recall` / `bb kb-tree` at task start.
  - Optional automation patterns (wrapper alias/script) if Opencode supports prompt hooks in your environment.
  - Required env vars (`CLAUDE_KB_PATH`, `CLAUDE_KB_DIR`) and expected defaults.

### 3) `README.md`
- Change title/intro from Claude-only positioning to agent-agnostic positioning.
- Add a short "Using with Opencode" section that links to `AGENT_SETUP.md`.
- Keep existing Claude-specific install instructions but label them clearly as "Claude Code integration".

## Compatibility and Risk Notes
- No changes required to `bb.edn` or `scripts/kb.clj` for Opencode usage.
- `install.sh` remains Claude-specific; do not present it as universal installer.
- Main risk is user confusion from mixed terms (Claude vs Opencode). Mitigate by centralizing runtime-specific guidance in `AGENT_SETUP.md`.

## Validation Checklist
After docs updates:
1. A new user can understand KB hierarchy from `CLAUDE.md` without knowing Claude internals.
2. Opencode user can run end-to-end flow:
   - `bb kb-tree`
   - `bb kb-summary ...`
   - `bb kb-store --parent ...`
   - `bb kb-recall ...`
3. Claude user still has intact hook instructions and install path.
4. No command examples imply unsupported auto-hook behavior in Opencode.

## Deliverables
- Updated `CLAUDE.md` (agent-neutral core)
- New `AGENT_SETUP.md` (runtime-specific setup)
- Updated `README.md` sections for Opencode visibility

## Execution Order (once approved)
1. Edit `CLAUDE.md` for neutral language + interop section.
2. Add `AGENT_SETUP.md` with Claude/Opencode setup matrix.
3. Update `README.md` intro and add links.
4. Final docs pass for terminology consistency.
