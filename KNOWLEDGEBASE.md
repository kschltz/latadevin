# Latadevin Knowledge Base

A persistent, hierarchical Zettelkasten knowledge base backed by Datalevin. Works with any coding agent that can run shell commands.

## Layer System

The knowledge base uses three layers for top-down navigation:

| Layer | Purpose | Has parent? | Has tags? |
|-------|---------|-------------|-----------|
| **abstract** | Broad domain anchor (e.g., "stonehenge", "payments") | No | No |
| **summary** | Subfield grouping under an abstract | Yes (abstract) | No |
| **note** | Atomic Zettelkasten note with actual content | Yes (summary) | Yes |

Every entry must belong to a layer. Notes and summaries must have a parent. This is enforced on store.

### Navigating the Hierarchy

```bash
bb kb-tree                    # Print full hierarchy: abstracts → summaries → notes
bb kb-drill "<topic>"         # Show entry and all its children (or parent context for notes)
```

### Creating Entries

```bash
# Create a top-level abstract
bb kb-abstract "<topic>" "<content>"

# Create a summary under an abstract
bb kb-summary "<topic>" "<content>" "<parent-abstract>"

# Store a note under a summary (--parent is required)
bb kb-store --parent "<parent-summary>" "<topic>" "<content>" [tag1 tag2 ...]
```

### Storing Notes

Notes require `--parent <summary-topic>` to place them in the hierarchy. Use `bb kb-tree` to find the right summary, or use `--create-parents` to auto-create missing hierarchy.

```bash
# First, check what summaries exist
bb kb-tree

# Store under an existing summary
bb kb-store --parent "summary/stonehenge-architecture" \
  "arch/new-service" \
  "Description of the new service.

Context: Why it matters.
See also: arch/stonehenge-services" \
  architecture services

# Or create a new summary first if needed
bb kb-summary "summary/new-group" "Description of this group." "abstract/stonehenge"
bb kb-store --parent "summary/new-group" "arch/new-thing" "Content." tag1 tag2

# Or use --create-parents to auto-create missing summary and abstract
bb kb-store --create-parents --parent "summary/new-group" "arch/new-thing" "Content." tag1 tag2
```

The `--create-parents` flag also works with `kb-summary` to auto-create a missing parent abstract.

## Zettelkasten Note Principles

Every note stored must follow these rules:

### 1. Atomic Notes

Each note captures **one idea, one fact, or one decision**. Never combine multiple concepts into a single entry. If you learn three things, store three notes.

### 2. Topic IDs Are Addresses, Not Titles

Use short, hierarchical, kebab-case IDs. Prefix with a category namespace:

| Prefix | Meaning | Examples |
|---|---|---|
| `arch/` | Architecture decisions | `arch/api-framework`, `arch/db-choice` |
| `ops/` | Deployment, infra, CI | `ops/staging-deploy`, `ops/db-backup` |
| `bug/` | Bug root causes & fixes | `bug/race-condition-orders`, `bug/oom-worker` |
| `how/` | Procedures & recipes | `how/run-migrations`, `how/rotate-keys` |
| `pref/` | User preferences | `pref/code-style`, `pref/pr-size` |
| `ref/` | Reference facts | `ref/api-endpoints`, `ref/env-vars` |
| `ctx/` | Project context & history | `ctx/why-monorepo`, `ctx/q3-rewrite` |
| `tool/` | Tool configs & gotchas | `tool/datalevin-pod`, `tool/docker-compose` |

Layer-specific prefixes:
- `abstract/` for abstracts
- `summary/` for summaries

### 3. Self-Contained Content

Each note must be understandable on its own. Include enough context that a future reader (you, in a new session) can act on it. Write in the present tense.

### 4. Link via Tags, Parents, and Explicit References

Connect notes through:
- **Parent**: Every note belongs to a summary, every summary to an abstract
- **Tags**: Shared tags create implicit clusters
- **Explicit references**: Mention related topic IDs in content: `"See also: arch/db-choice"`

### 5. Content Structure

```
<core statement — the one thing this note says>

Context: <why this matters or when it applies>
<optional: See also: related/topic-id, other/topic-id>
```

## Recalling Knowledge

```bash
bb kb-recall "<search-query>"    # Search across topics, content, and tags
bb kb-get "<exact-topic-id>"     # Get a specific entry
bb kb-list                       # List recent entries
bb kb-list 50                    # List more
bb kb-by-tag "<tag>"             # All notes with a tag
bb kb-tags                       # List all tags with counts
bb kb-tree                       # Full hierarchy view
bb kb-drill "<topic>"            # Drill into a topic and its children
```

## Removing Knowledge

```bash
bb kb-forget "<topic-id>"        # Delete an entry (must have no children)
```

Entries with children cannot be deleted. Delete or reparent children first.

## When to Store

Store a note when you encounter:
- An architecture decision and its rationale — `arch/`
- A non-obvious configuration or setup step — `tool/` or `ref/`
- A bug root cause and fix — `bug/`
- A user preference for how work should be done — `pref/`
- A project convention not captured in code — `ctx/`
- An external service detail (endpoint, quirk) — `ref/`
- A deployment or operational procedure — `ops/` or `how/`

**Split aggressively.** If you're writing "also" or "additionally" in a note, it should probably be two notes linked by tags and "See also" references.

When storing, always place the note under the most specific summary. If no summary fits, create one under the appropriate abstract. If no abstract fits, create one.

## When to Recall

- At the start of any non-trivial task, search for related context
- When the user references past work or decisions
- Before making architecture or tooling choices
- Use `bb kb-tree` to get an overview of what's known
- Use `bb kb-drill` to explore a specific domain top-down

## Database Location

The knowledge base path is resolved in this order:

1. `$DATALEVIN_KB_PATH` environment variable
2. `$CLAUDE_KB_PATH` environment variable (legacy)
3. `~/.claude/datalevin-kb` if it already exists (legacy migration)
4. `~/.local/share/datalevin-kb` (default for new installs)

Override with `DATALEVIN_KB_PATH=/your/path bb kb-tree`.

## Other Tools

This project also provides general-purpose Datalevin `bb` tasks (see `bb tasks`) for working with any Datalevin database — Datalog store, KV store, search. See `guide.md` for the full reference.

