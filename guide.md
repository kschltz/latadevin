# Datalevin Agent Guide

Version: 0.10.7

## Recommended Approach: `bb` tasks

**Use the `bb` (Babashka) tasks defined in `bb.edn` as the primary interface to Datalevin.** The bb tasks wrap the Datalevin pod and provide clean, scriptable commands that avoid the quirks of `dtlv exec` (broken `println`, noisy intermediate output, shell quoting headaches).

For custom logic beyond what the tasks cover, write a `.clj` script and run it with `bb scripts/your_script.clj`.

Fall back to `dtlv` directly only for maintenance operations (dump, load, copy, stat) which work well as standalone commands.

## Quick Reference: `bb` Tasks

```
bb tasks                        # List all available tasks

# ── Datalog Store ──
bb create <db-path> <schema.edn>                     # Create DB with schema
bb transact <db-path> <data.edn>                     # Transact entity data
bb query <db-path> '<datalog-query>' [<args>...]      # Run Datalog query
bb pull <db-path> '<pattern>' '<entity-id>'           # Pull entity attributes
bb datoms <db-path> [eav|aev|ave|vae]                 # List raw datoms
bb schema <db-path>                                   # Show attributes & entity count
bb stats <db-path>                                    # Show DB stats

# ── Key-Value Store ──
bb kv-put <db-path> <table> <key> '<value-edn>'      # Put a key-value pair
bb kv-get <db-path> <table> <key>                     # Get value by key
bb kv-range <db-path> <table> '[range-spec]'          # Range query
bb kv-del <db-path> <table> <key>                     # Delete a key
bb kv-tables <db-path>                                # List tables

# ── Search ──
bb search <db-path> '<query-string>'                  # Full-text search

# ── Maintenance (use dtlv directly) ──
dtlv -d <path> stat                                   # LMDB page stats
dtlv -d <path> -g dump                                # Dump Datalog DB
dtlv -d <path> -l dump                                # List sub-databases
dtlv -d <path> copy <dest>                            # Hot backup
dtlv -d <path> -c copy <dest>                         # Backup with compaction
```

## Datalog Store

### Create Database with Schema

Write a schema EDN file:

```clojure
;; schema.edn
{:name {:db/valueType :db.type/string :db/unique :db.unique/identity}
 :age  {:db/valueType :db.type/long}
 :tags {:db/cardinality :db.cardinality/many :db/valueType :db.type/string}}
```

```bash
bb create /path/to/mydb schema.edn
```

Schema is optional (schema-on-write). Attributes without explicit types are stored as EDN blobs. Schema is persisted — subsequent opens use the stored schema.

### Schema Properties

| Property | Values | Notes |
|---|---|---|
| `:db/valueType` | `:db.type/string`, `:db.type/long`, `:db.type/double`, `:db.type/float`, `:db.type/boolean`, `:db.type/keyword`, `:db.type/symbol`, `:db.type/uuid`, `:db.type/instant`, `:db.type/ref`, `:db.type/bigint`, `:db.type/bigdec`, `:db.type/bytes` | Optional; untyped attrs stored as EDN |
| `:db/cardinality` | `:db.cardinality/one` (default), `:db.cardinality/many` | |
| `:db/unique` | `:db.unique/identity`, `:db.unique/value` | Identity enables upsert |
| `:db/isComponent` | `true` | Component entities cascade on pull/retract |

### Transact Data

Write a data EDN file:

```clojure
;; data.edn
[{:name "Alice" :age 30 :tags ["dev" "lead"]}
 {:name "Bob" :age 25 :tags ["dev"]}
 {:name "Carol" :age 35 :tags ["ops"]}]
```

```bash
bb transact /path/to/mydb data.edn
# Output: Transacted 9 datoms
```

Transaction format supports:
- Entity maps: `{:name "Alice" :age 30}`
- Explicit add: `[:db/add entity-id :attr value]`
- Retract: `[:db/retract entity-id :attr value]`
- Retract entity: `[:db/retractEntity entity-id]`
- Lookup refs for entities with `:db.unique/identity`: `[:name "Alice"]`

### Query (Datalog)

```bash
# Find all names and ages
bb query /path/to/mydb '[:find ?n ?a :where [?e :name ?n] [?e :age ?a]]'
# Output: #{["Alice" 30] ["Carol" 35] ["Bob" 25]}

# With predicates
bb query /path/to/mydb '[:find ?n ?r :where [?e :name ?n] [?e :role ?r] [?e :age ?a] [(> ?a 26)]]'

# With input parameters (extra args after query become inputs)
bb query /path/to/mydb '[:find ?n :in $ ?min-age :where [?e :name ?n] [?e :age ?a] [(>= ?a ?min-age)]]' '30'

# Aggregates
bb query /path/to/mydb '[:find (count ?e) (avg ?a) :where [?e :age ?a]]'
```

### Pull (Entity Retrieval)

```bash
# Pull all attributes by lookup ref
bb pull /path/to/mydb '[*]' '[:name "Alice"]'
# Output: {:db/id 1, :name "Alice", :age 30, :tags ["dev" "lead"]}

# Pull specific attributes by entity ID
bb pull /path/to/mydb '[:name :age]' '2'
# Output: {:name "Bob", :age 25}
```

### Datoms (Raw Index Access)

```bash
bb datoms /path/to/mydb
# [1 :name "Alice"]
# [1 :age 30]
# [1 :tags "dev"]
# ...

bb datoms /path/to/mydb ave    # Attribute-Value-Entity order
```

Index types: `eav`, `aev`, `ave`, `vae`

## Key-Value Store

Datalevin exposes LMDB as a key-value store, independent of Datalog. Values can be any EDN data.

```bash
# Put values
bb kv-put /path/to/kvdb users alice '{:name "Alice" :email "alice@co.com"}'
bb kv-put /path/to/kvdb users bob '{:name "Bob" :email "bob@co.com"}'

# Get single value
bb kv-get /path/to/kvdb users alice
# {:name "Alice", :email "alice@co.com"}

# Range queries
bb kv-range /path/to/kvdb users                       # All entries (default [:all])
bb kv-range /path/to/kvdb users '[:at-least "b"]'     # From "b" onwards
bb kv-range /path/to/kvdb users '[:closed "a" "b"]'   # Inclusive range

# Delete
bb kv-del /path/to/kvdb users bob

# List tables
bb kv-tables /path/to/kvdb
```

Range specifiers:
- `[:all]` — all entries
- `[:at-least key]` — from key onwards
- `[:at-most key]` — up to key
- `[:closed k1 k2]` — inclusive range
- `[:open k1 k2]` — exclusive range
- `[:open-closed k1 k2]`, `[:closed-open k1 k2]` — half-open ranges
- `[:greater-than key]` — strictly greater

## Custom Scripts

For logic beyond what the tasks cover, write a `.clj` script. The `bb.edn` pod declaration ensures `dtlv` is available.

```clojure
#!/usr/bin/env bb
;; scripts/my_report.clj — Run with: bb scripts/my_report.clj <db-path>

(require '[babashka.pods :as pods])
(pods/load-pod "dtlv")
(require '[pod.huahaiy.datalevin :as d])

(let [db-path (first *command-line-args*)
      conn    (d/get-conn db-path)
      db      (d/db conn)]

  ;; Pull all entities
  (doseq [result (d/q '[:find (pull ?e [*]) :where [?e :name]] db)]
    (prn (first result)))

  ;; Aggregate
  (println "Counts by role:"
    (d/q '[:find ?r (count ?e) :where [?e :role ?r]] db))

  (d/close conn))
```

### Available Pod Functions

All `datalevin.core` functions are available via the pod as `d/<fn>`:

**Connections**: `get-conn`, `create-conn`, `close`, `db`, `conn?`
**Transactions**: `transact!`, `transact`, `transact-async`
**Queries**: `q`, `pull`, `pull-many`, `explain`
**Entities**: `entity`, `touch`, `entid`, `max-eid`
**Indexes**: `datoms`, `seek-datoms`, `index-range`, `count-datoms`
**Schema**: `schema`, `update-schema`
**KV Store**: `open-kv`, `close-kv`, `open-dbi`, `get-value`, `get-range`, `get-first`, `transact-kv`, `list-dbis`
**Search**: `new-search-engine`, `search`, `add-doc`, `remove-doc`
**Vectors**: `new-vector-index`, `add-vec`, `search-vec`

## Maintenance Commands (dtlv direct)

These work better as direct `dtlv` commands than through bb:

```bash
# Statistics
dtlv -d /path/to/db stat

# Dump Datalog database (human-readable: schema + datoms)
dtlv -d /path/to/db -g dump

# List sub-database names
dtlv -d /path/to/db -l dump

# Dump specific KV sub-database
dtlv -d /path/to/db dump my-table

# Dump all sub-databases
dtlv -d /path/to/db -a dump

# Dump to file / binary format
dtlv -d /path/to/db -f /tmp/backup.txt -g dump
dtlv -d /path/to/db -n -g dump

# Load from dump
dtlv -d /path/to/newdb -g load < dump.txt
dtlv -d /path/to/newdb -n -g load < dump.nippy

# Hot backup (works while DB is in use)
dtlv -d /path/to/db copy /path/to/backup
dtlv -d /path/to/db -c copy /path/to/backup   # with compaction

# Drop sub-database
dtlv -d /path/to/db drop my-sub-db
dtlv -d /path/to/db -D drop my-sub-db          # delete entirely
```

## Client/Server Mode

```bash
# Start server
dtlv -r /var/lib/datalevin serv
dtlv -r /var/lib/datalevin -p 8899 serv   # custom port

# Connect from script
(d/get-conn "dtlv://user:pass@host:8898/mydb" schema)
```

## dtlv exec (Fallback)

Use `dtlv exec` only when bb is unavailable. Key differences from bb:

- All `datalevin.core` functions are pre-imported (no require, no namespace prefix).
- Use `(quote [...])` instead of `'[...]` to avoid shell quoting conflicts.
- **`println`/`pr` crash** — rely on expression return values.
- **`doc` is unavailable.**
- Don't use `schema` as a variable name (shadows the function).
- Every expression prints its return value (noisy output).

```bash
# One-liner query
echo '(def conn (get-conn "/path/to/db")) (q (quote [:find ?n ?a :where [?e :name ?n] [?e :age ?a]]) (db conn))' | dtlv exec

# Multi-expression
echo '
(def conn (get-conn "/path/to/db"))
(transact! conn [{:name "Dave" :age 28}])
(q (quote [:find ?n ?a :where [?e :name ?n] [?e :age ?a]]) (db conn))
' | dtlv exec
```
