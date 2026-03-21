# Plan: Adapting CLAUDE.md for Opencode

## Overview
This plan outlines how to adapt the existing CLAUDE.md file (designed for Claude Code) to work with Opencode while maintaining the same Datalevin-backed Zettelkasten knowledge base functionality.

## Key Changes Needed

### 1. Documentation Updates
- Replace all references to "Claude Code" with "Opencode"
- Update installation instructions if Opencode has different setup requirements
- Modify auto-recall hook explanation to match Opencode's capabilities
- Update any Claude Code-specific workflow references

### 2. Command Adaptations
- Verify if the `bb` commands (kb-tree, kb-drill, kb-store, etc.) work the same with Opencode
- Update examples to show Opencode usage patterns
- Ensure command-line instructions are still valid

### 3. Integration Points
- Document how Opencode agents can interact with the knowledge base
- Explain any Opencode-specific features that enhance KB usage
- Note any differences in how Opencode handles context vs Claude Code

### 4. Configuration Notes
- Verify database location (`~/.claude/datalevin-kb`) is still appropriate
- Check if environment variables need updating
- Confirm installation script compatibility

## Files to Modify
- `CLAUDE.md` - Main documentation file to adapt
- Potentially `README.md` if it contains Claude Code-specific references
- `install.sh` - May need updates for Opencode compatibility (but this is a shell script, so cannot modify in plan mode)

## Implementation Approach
Since I'm in plan mode and can only modify markdown files, I will:
1. Create a detailed plan (this file)
2. Submit the plan for review
3. After approval, create a modified version of CLAUDE.md for Opencode
4. Update any other markdown files as needed

## Questions for Clarification
Before proceeding, I should confirm:
1. Does Opencode use the same `bb` command interface for Datalevin operations?
2. Are there any Opencode-specific knowledge base features I should leverage?
3. Does Opencode have different settings/configuration locations for hooks/integrations?
4. Are there any Opencode conventions I should follow in the documentation?

## Next Steps
Once questions are clarified, I will:
1. Create an Opencode-adapted version of CLAUDE.md
2. Update README.md if needed
3. Ensure all documentation accurately reflects Opencode usage