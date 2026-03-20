#!/usr/bin/env bb

;; Knowledge Base for Claude Code backed by Datalevin
;; Usage: bb scripts/kb.clj <command> [args...]
;;
;; Commands:
;;   store        --parent <parent> <topic> <content> [tags...]  Store a note under a summary
;;   abstract     <topic> <content>                              Create a top-level abstract
;;   summary      <topic> <content> <parent-abstract>            Create a summary under an abstract
;;   recall       <query>                                        Search by topic, content, and tags
;;   recall-multi <word1> [word2...]                              Search multiple keywords
;;   recall-with-context <word1> [word2...]                       Search with parent summaries
;;   get          <topic>                                        Get exact topic
;;   list         [limit]                                        List recent entries
;;   tree                                                        Print full hierarchy
;;   drill        <topic>                                        Show entry and all children
;;   forget       <topic>                                        Remove entries by topic
;;   tags                                                        List all tags
;;   by-tag       <tag>                                          Find entries by tag

(require '[babashka.pods :as pods])
(pods/load-pod "dtlv")
(require '[pod.huahaiy.datalevin :as d])

(def db-path (or (System/getenv "CLAUDE_KB_PATH")
                 (str (System/getenv "HOME") "/.claude/datalevin-kb")))

(def schema
  {:kb/topic   {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :kb/content {:db/valueType :db.type/string :db/fulltext true}
   :kb/tags    {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}
   :kb/source  {:db/valueType :db.type/string}
   :kb/created {:db/valueType :db.type/long}
   :kb/updated {:db/valueType :db.type/long}
   :kb/layer   {:db/valueType :db.type/string}
   :kb/parent  {:db/valueType :db.type/string}})

(defn now [] (System/currentTimeMillis))

(defn fmt-ts [ms]
  (when ms
    (str (java.time.Instant/ofEpochMilli ms))))

(defn with-conn [f]
  (let [conn (d/get-conn db-path schema)]
    (try (f conn)
         (finally (d/close conn)))))

;; ── Pull & Print ────────────────────────────────────────────────────

(defn pull-entry
  "Pull a complete entry by entity id, returning a normalized map."
  [db eid]
  (let [e (d/pull db '[:kb/topic :kb/content :kb/tags :kb/source :kb/created :kb/updated :kb/layer :kb/parent] eid)]
    (when (:kb/topic e)
      {:topic   (:kb/topic e)
       :content (:kb/content e)
       :tags    (let [t (:kb/tags e)] (cond (nil? t) [] (string? t) [t] :else (vec t)))
       :source  (:kb/source e)
       :created (:kb/created e)
       :updated (:kb/updated e)
       :layer   (:kb/layer e)
       :parent  (:kb/parent e)})))

(defn pull-by-topic
  "Pull an entry by its topic string."
  [db topic]
  (let [e (d/pull db '[:db/id :kb/topic] [:kb/topic topic])]
    (when (:db/id e)
      (pull-entry db (:db/id e)))))

(defn print-entry [{:keys [topic content tags updated layer parent]}]
  (println (str "## " topic))
  (when layer (println (str "Layer: " layer)))
  (when parent (println (str "Parent: " parent)))
  (when (seq tags) (println (str "Tags: " (clojure.string/join ", " tags))))
  (println (str "Updated: " (fmt-ts updated)))
  (println content)
  (println))

;; ── Validation ──────────────────────────────────────────────────────

(defn get-layer
  "Get the layer of a topic, or nil if it doesn't exist."
  [db topic]
  (ffirst (d/q '[:find ?layer :in $ ?topic
                 :where [?e :kb/topic ?topic]
                        [?e :kb/layer ?layer]]
               db topic)))

(defn validate-parent!
  "Validate parent exists with the expected layer. Returns true or exits."
  [db parent expected-parent-layer]
  (let [actual (get-layer db parent)]
    (cond
      (nil? actual)
      (do (println (str "Error: parent '" parent "' not found."))
          (System/exit 1))

      (not= actual expected-parent-layer)
      (do (println (str "Error: parent '" parent "' is layer '" actual "', expected '" expected-parent-layer "'."))
          (System/exit 1))

      :else true)))

;; ── Store ───────────────────────────────────────────────────────────

(defn store-entry!
  "Store an entry with layer and parent validation."
  [conn topic content tags source layer parent]
  (let [ts  (now)
        db  (d/db conn)
        existing (d/q '[:find ?e ?created
                        :in $ ?topic
                        :where [?e :kb/topic ?topic]
                               [?e :kb/created ?created]]
                      db topic)
        created (or (second (first existing)) ts)
        entity (cond-> {:kb/topic   topic
                        :kb/content content
                        :kb/created created
                        :kb/updated ts
                        :kb/layer   layer}
                 (seq tags)  (assoc :kb/tags (vec tags))
                 source      (assoc :kb/source source)
                 parent      (assoc :kb/parent parent))]
    (d/transact! conn [entity])
    (if (seq existing)
      (println (str "Updated: " topic " [" layer "]"))
      (println (str "Stored: " topic " [" layer "]")))))

(defn store-note! [topic content tags source parent]
  (with-conn
    (fn [conn]
      (validate-parent! (d/db conn) parent "summary")
      (store-entry! conn topic content tags source "note" parent))))

(defn store-abstract! [topic content]
  (with-conn
    (fn [conn]
      (store-entry! conn topic content nil nil "abstract" nil))))

(defn store-summary! [topic content parent]
  (with-conn
    (fn [conn]
      (validate-parent! (d/db conn) parent "abstract")
      (store-entry! conn topic content nil nil "summary" parent))))

;; ── Search ──────────────────────────────────────────────────────────

(defn match-entry?
  "Check if an entry matches a lowercased query string by topic, content, or tags."
  [entry lq]
  (let [lt (clojure.string/lower-case (or (:topic entry) ""))
        lc (clojure.string/lower-case (or (:content entry) ""))]
    (or (clojure.string/includes? lt lq)
        (clojure.string/includes? lc lq)
        (some #(clojure.string/includes? (clojure.string/lower-case %) lq)
              (:tags entry)))))

(defn all-entries-by-layer
  "Fetch all entries for a given layer."
  [db layer]
  (let [eids (d/q '[:find ?e :in $ ?layer
                     :where [?e :kb/layer ?layer]]
                   db layer)]
    (keep #(pull-entry db (first %)) eids)))

(defn children-eids
  "Get entity IDs of entries whose parent is one of the given topics."
  [db parent-topics]
  (when (seq parent-topics)
    (let [results (d/q '[:find ?e ?parent
                         :in $ [?parent ...]
                         :where [?e :kb/parent ?parent]]
                       db (vec parent-topics))]
      results)))

(defn search-entries
  "Cascading hierarchy-aware search.
   1. Match abstracts (few entries, cheap)
   2. Expand matching abstracts → their summaries
   3. Match summaries (direct + from step 2)
   4. Expand matching summaries → their notes
   5. Scan only uncovered notes as fallback (keywords only in note content/tags)
   6. Merge, deduplicate, return notes sorted by recency."
  [conn query-str]
  (let [db (d/db conn)
        lq (clojure.string/lower-case query-str)

        ;; Step 1: scan abstracts (few entries, cheap)
        matching-abstract-topics (->> (all-entries-by-layer db "abstract")
                                      (filter #(match-entry? % lq))
                                      (map :topic)
                                      set)

        ;; Step 2: summaries under matching abstracts (cascade)
        cascade-summary-eids (set (map first (children-eids db matching-abstract-topics)))

        ;; Step 3: also match summaries directly by content/topic
        all-summaries (all-entries-by-layer db "summary")
        ;; Build topic->eid index once (avoid repeated queries)
        summary-topic->eid (into {}
                             (d/q '[:find ?topic ?e
                                    :where [?e :kb/layer "summary"]
                                           [?e :kb/topic ?topic]]
                                  db))
        direct-match-summary-eids (->> all-summaries
                                       (filter #(match-entry? % lq))
                                       (map #(get summary-topic->eid (:topic %)))
                                       (remove nil?)
                                       set)

        ;; Combine summary eids from cascade + direct match
        all-matched-summary-eids (clojure.set/union cascade-summary-eids direct-match-summary-eids)
        matched-summary-topics (->> all-matched-summary-eids
                                    (keep #(:topic (pull-entry db %)))
                                    set)

        ;; Step 4: notes under matched summaries (cascade)
        cascade-note-eids (set (map first (children-eids db matched-summary-topics)))

        ;; Step 5: fallback — scan only notes NOT already reached by cascade
        all-note-eids (set (map first (d/q '[:find ?e :where [?e :kb/layer "note"]] db)))
        uncovered-note-eids (clojure.set/difference all-note-eids cascade-note-eids)
        direct-match-note-eids (when (seq uncovered-note-eids)
                                 (->> uncovered-note-eids
                                      (keep #(let [entry (pull-entry db %)]
                                               (when (match-entry? entry lq) %)))
                                      set))

        ;; Step 6: merge and pull
        final-note-eids (clojure.set/union
                          cascade-note-eids
                          (or direct-match-note-eids #{}))]
    (->> final-note-eids
         (keep #(pull-entry db %))
         (sort-by :updated >))))

(defn dedupe-results
  "Deduplicate entries by topic, preserving order."
  [entries]
  (:entries
   (reduce (fn [{:keys [seen entries]} entry]
             (if (seen (:topic entry))
               {:seen seen :entries entries}
               {:seen (conj seen (:topic entry))
                :entries (conj entries entry)}))
           {:seen #{} :entries []}
           entries)))

(defn recall [query-str]
  (with-conn
    (fn [conn]
      (let [results (search-entries conn query-str)]
        (if (seq results)
          (doseq [entry results]
            (print-entry entry))
          (println "No results found."))))))

(defn recall-multi
  "Search multiple keywords in a single invocation, deduplicate results."
  [keywords]
  (with-conn
    (fn [conn]
      (let [all-results (->> keywords
                             (mapcat #(search-entries conn %))
                             dedupe-results)]
        (if (seq all-results)
          (doseq [entry all-results]
            (print-entry entry))
          (println "No results found."))))))

(defn recall-with-context
  "Search multiple keywords and surface parent summaries for context."
  [keywords]
  (with-conn
    (fn [conn]
      (let [db (d/db conn)
            all-results (->> keywords
                             (mapcat #(search-entries conn %))
                             dedupe-results)
            ;; Collect unique parent topics for context
            parent-topics (->> all-results
                               (keep :parent)
                               (distinct))
            result-topics (set (map :topic all-results))
            parent-entries (->> parent-topics
                                (remove result-topics)
                                (keep #(pull-by-topic db %)))]
        (if (seq all-results)
          (do
            ;; Print parent context first
            (doseq [p parent-entries]
              (println (str "### Context: " (:topic p)))
              (println (:content p))
              (println))
            ;; Then print matching entries
            (doseq [entry all-results]
              (print-entry entry)))
          (println "No results found."))))))

;; ── Get ─────────────────────────────────────────────────────────────

(defn get-topic [topic]
  (with-conn
    (fn [conn]
      (let [entry (pull-by-topic (d/db conn) topic)]
        (if entry
          (do
            (println (str "## " (:topic entry)))
            (when (:layer entry) (println (str "Layer: " (:layer entry))))
            (when (:parent entry) (println (str "Parent: " (:parent entry))))
            (when (seq (:tags entry))
              (println (str "Tags: " (clojure.string/join ", " (:tags entry)))))
            (when (:source entry)
              (println (str "Source: " (:source entry))))
            (println (str "Created: " (fmt-ts (:created entry))))
            (println (str "Updated: " (fmt-ts (:updated entry))))
            (println)
            (println (:content entry)))
          (println (str "Not found: " topic)))))))

;; ── List ────────────────────────────────────────────────────────────

(defn list-entries [limit]
  (with-conn
    (fn [conn]
      (let [db (d/db conn)
            eids (d/q '[:find ?e ?updated
                        :where [?e :kb/topic _]
                               [?e :kb/updated ?updated]]
                      db)
            sorted (->> eids
                        (sort-by second >)
                        (take limit))]
        (if (seq sorted)
          (doseq [[eid _] sorted]
            (let [entry (pull-entry db eid)
                  layer-str (if (:layer entry) (str " (" (:layer entry) ")") "")
                  tag-str (if (seq (:tags entry))
                            (str " [" (clojure.string/join ", " (:tags entry)) "]")
                            "")]
              (println (str "  " (fmt-ts (:updated entry)) "  " (:topic entry) layer-str tag-str))))
          (println "Knowledge base is empty."))))))

;; ── Tree ────────────────────────────────────────────────────────────

(defn children-of
  "Find all entries whose parent is the given topic."
  [db parent-topic]
  (let [eids (d/q '[:find ?e
                     :in $ ?parent
                     :where [?e :kb/parent ?parent]]
                   db parent-topic)]
    (->> eids
         (map #(pull-entry db (first %)))
         (sort-by :topic))))

(defn tree []
  (with-conn
    (fn [conn]
      (let [db (d/db conn)
            ;; Get all abstracts
            abstracts (let [eids (d/q '[:find ?e
                                        :where [?e :kb/layer "abstract"]]
                                      db)]
                        (->> eids
                             (map #(pull-entry db (first %)))
                             (sort-by :topic)))
            ;; Get orphan notes (no parent, layer=note or nil layer)
            orphans (let [eids (d/q '[:find ?e
                                      :where [?e :kb/topic _]
                                             (not [?e :kb/parent _])]
                                    db)]
                      (->> eids
                           (map #(pull-entry db (first %)))
                           (filter #(not= (:layer %) "abstract"))
                           (sort-by :topic)))]
        ;; Print each abstract and its tree
        (doseq [ab abstracts]
          (println (str (:topic ab) " \u2014 " (:content ab)))
          (let [summaries (children-of db (:topic ab))]
            (doseq [sm summaries]
              (println (str "  " (:topic sm) " \u2014 " (:content sm)))
              (let [notes (children-of db (:topic sm))]
                (doseq [n notes]
                  (let [tag-str (if (seq (:tags n))
                                  (str " [" (clojure.string/join ", " (:tags n)) "]")
                                  "")]
                    (println (str "    " (:topic n) tag-str)))))))
          (println))
        ;; Print orphans if any
        (when (seq orphans)
          (println "UNPARENTED")
          (doseq [o orphans]
            (let [tag-str (if (seq (:tags o))
                            (str " [" (clojure.string/join ", " (:tags o)) "]")
                            "")]
              (println (str "  " (:topic o) tag-str))))
          (println))))))

;; ── Drill ───────────────────────────────────────────────────────────

(defn drill [topic]
  (with-conn
    (fn [conn]
      (let [db    (d/db conn)
            entry (pull-by-topic db topic)]
        (if (nil? entry)
          (println (str "Not found: " topic))
          (case (:layer entry)
            "abstract"
            (do
              (println (str "# " (:topic entry)))
              (println (:content entry))
              (println)
              (let [summaries (children-of db (:topic entry))]
                (doseq [sm summaries]
                  (println (str "## " (:topic sm)))
                  (println (:content sm))
                  (let [notes (children-of db (:topic sm))]
                    (println (str "  (" (count notes) " notes)"))
                    (doseq [n notes]
                      (println (str "  - " (:topic n))))
                    (println)))))

            "summary"
            (do
              (println (str "## " (:topic entry)))
              (when (:parent entry) (println (str "Parent: " (:parent entry))))
              (println (:content entry))
              (println)
              (let [notes (children-of db (:topic entry))]
                (doseq [n notes]
                  (print-entry n))))

            ;; note or nil layer
            (do
              ;; Show parent summary for context
              (when (:parent entry)
                (let [parent-entry (pull-by-topic db (:parent entry))]
                  (when parent-entry
                    (println (str "### Context: " (:topic parent-entry)))
                    (println (:content parent-entry))
                    (println))))
              (print-entry entry))))))))

;; ── Forget ──────────────────────────────────────────────────────────

(defn forget! [topic]
  (with-conn
    (fn [conn]
      (let [db (d/db conn)
            existing (d/q '[:find ?e :in $ ?topic :where [?e :kb/topic ?topic]]
                          db topic)
            ;; Check for children
            children (d/q '[:find ?e ?t :in $ ?topic :where [?e :kb/parent ?topic] [?e :kb/topic ?t]]
                          db topic)]
        (cond
          (empty? existing)
          (println (str "Not found: " topic))

          (seq children)
          (do
            (println (str "Error: cannot delete '" topic "' — it has " (count children) " children:"))
            (doseq [[_ child-topic] children]
              (println (str "  - " child-topic)))
            (println "Delete or reparent children first."))

          :else
          (do
            (d/transact! conn [[:db/retractEntity (ffirst existing)]])
            (println (str "Forgotten: " topic))))))))

;; ── Tags ────────────────────────────────────────────────────────────

(defn list-tags []
  (with-conn
    (fn [conn]
      (let [tags (d/q '[:find ?tag (count ?e)
                        :where [?e :kb/tags ?tag]]
                      (d/db conn))]
        (if (seq tags)
          (doseq [[tag cnt] (sort-by first tags)]
            (println (str "  " tag " (" cnt ")")))
          (println "No tags."))))))

(defn by-tag [tag]
  (with-conn
    (fn [conn]
      (let [db (d/db conn)
            eids (d/q '[:find ?e
                        :in $ ?tag
                        :where [?e :kb/tags ?tag]]
                      db tag)]
        (if (seq eids)
          (let [entries (->> eids
                             (map #(pull-entry db (first %)))
                             (sort-by :updated >))]
            (doseq [entry entries]
              (print-entry entry)))
          (println (str "No entries with tag: " tag)))))))

;; ── CLI Argument Parsing ────────────────────────────────────────────

(defn parse-flag
  "Extract a --flag value from args. Returns {:value v :rest-args remaining}."
  [args flag-name]
  (let [flag (str "--" flag-name)
        idx (.indexOf (vec args) flag)]
    (if (>= idx 0)
      {:value (nth args (inc idx))
       :rest-args (concat (take idx args) (drop (+ idx 2) args))}
      {:value nil :rest-args args})))

;; ── Main Dispatch ───────────────────────────────────────────────────

(let [[cmd & args] *command-line-args*]
  (case cmd
    "store"
    (let [{:keys [value rest-args]} (parse-flag args "parent")
          parent value
          [topic content & tags] rest-args]
      (when-not (and topic content parent)
        (println "Usage: bb scripts/kb.clj store --parent <parent-summary> <topic> <content> [tags...]")
        (System/exit 1))
      (store-note! topic content tags (System/getenv "CLAUDE_KB_SOURCE") parent))

    "abstract"
    (let [[topic content] args]
      (when-not (and topic content)
        (println "Usage: bb scripts/kb.clj abstract <topic> <content>")
        (System/exit 1))
      (store-abstract! topic content))

    "summary"
    (let [[topic content parent] args]
      (when-not (and topic content parent)
        (println "Usage: bb scripts/kb.clj summary <topic> <content> <parent-abstract>")
        (System/exit 1))
      (store-summary! topic content parent))

    "recall"
    (let [[query-str] args]
      (when-not query-str
        (println "Usage: bb scripts/kb.clj recall <query>")
        (System/exit 1))
      (recall query-str))

    "recall-multi"
    (do
      (when (empty? args)
        (println "Usage: bb scripts/kb.clj recall-multi <word1> [word2...]")
        (System/exit 1))
      (recall-multi args))

    "recall-with-context"
    (do
      (when (empty? args)
        (println "Usage: bb scripts/kb.clj recall-with-context <word1> [word2...]")
        (System/exit 1))
      (recall-with-context args))

    "get"
    (let [[topic] args]
      (when-not topic
        (println "Usage: bb scripts/kb.clj get <topic>")
        (System/exit 1))
      (get-topic topic))

    "list"
    (list-entries (or (some-> (first args) parse-long) 20))

    "tree"
    (tree)

    "drill"
    (let [[topic] args]
      (when-not topic
        (println "Usage: bb scripts/kb.clj drill <topic>")
        (System/exit 1))
      (drill topic))

    "forget"
    (let [[topic] args]
      (when-not topic
        (println "Usage: bb scripts/kb.clj forget <topic>")
        (System/exit 1))
      (forget! topic))

    "tags"
    (list-tags)

    "by-tag"
    (let [[tag] args]
      (when-not tag
        (println "Usage: bb scripts/kb.clj by-tag <tag>")
        (System/exit 1))
      (by-tag tag))

    (do
      (println "Datalevin Knowledge Base for Claude Code")
      (println)
      (println "Commands:")
      (println "  store        --parent <summary> <topic> <content> [tags...]  Store a note")
      (println "  abstract     <topic> <content>                              Create top-level abstract")
      (println "  summary      <topic> <content> <parent-abstract>            Create summary")
      (println "  recall       <query>                                        Search topics, content, tags")
      (println "  recall-multi <word1> [word2...]                              Multi-keyword search")
      (println "  recall-with-context <word1> [word2...]                       Search with parent context")
      (println "  get          <topic>                                        Get exact topic")
      (println "  list         [limit]                                        List recent entries")
      (println "  tree                                                        Print hierarchy")
      (println "  drill        <topic>                                        Show entry and children")
      (println "  forget       <topic>                                        Remove entry (no children)")
      (println "  tags                                                        List all tags")
      (println "  by-tag       <tag>                                          Find entries by tag")
      (println)
      (println (str "Database: " db-path)))))
