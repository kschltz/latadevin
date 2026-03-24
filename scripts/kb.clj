#!/usr/bin/env bb

;; Knowledge Base backed by Datalevin
;; Usage: bb scripts/kb.clj <command> [args...]
;;
;; Commands:
;;   store        --parent <parent> [--create-parents] <topic> <content> [tags...]  Store a note under a summary
;;   abstract     <topic> <content>                              Create a top-level abstract
;;   summary      [--create-parents] <topic> <content> <parent-abstract>            Create a summary under an abstract
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

(def db-path (or (System/getenv "DATALEVIN_KB_PATH")
                 (System/getenv "CLAUDE_KB_PATH")
                 (let [old (str (System/getenv "HOME") "/.claude/datalevin-kb")
                       new (str (System/getenv "HOME") "/.local/share/datalevin-kb")]
                   (if (.exists (java.io.File. old)) old new))))

(def schema
  {:kb/topic   {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :kb/content {:db/valueType :db.type/string :db/fulltext true}
   :kb/tags    {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}
   :kb/source  {:db/valueType :db.type/string}
   :kb/created {:db/valueType :db.type/long}
   :kb/updated {:db/valueType :db.type/long}
   :kb/layer   {:db/valueType :db.type/string}
   :kb/parent  {:db/valueType :db.type/string}
   :kb/links   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

(def max-entry-chars 250)

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
  (let [e (d/pull db '[:kb/topic :kb/content :kb/tags :kb/source :kb/created :kb/updated :kb/layer :kb/parent {:kb/links [:kb/topic]}] eid)]
    (when (:kb/topic e)
      {:topic   (:kb/topic e)
       :content (:kb/content e)
       :tags    (let [t (:kb/tags e)] (cond (nil? t) [] (string? t) [t] :else (vec t)))
       :links   (mapv :kb/topic (or (:kb/links e) []))
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

(defn print-entry [{:keys [topic content tags links updated layer parent]}]
  (println (str "## " topic))
  (when layer (println (str "Layer: " layer)))
  (when parent (println (str "Parent: " parent)))
  (when (seq tags) (println (str "Tags: " (clojure.string/join ", " tags))))
  (when (seq links) (println (str "Links: " (clojure.string/join ", " links))))
  (println (str "Updated: " (fmt-ts updated)))
  (println content)
  (println))

;; ── Validation ──────────────────────────────────────────────────────

(defn fit-entry-content
  "Clip s to max-entry-chars with ellipsis (for auto-generated placeholder text only)."
  [s]
  (let [n (count s)]
    (if (<= n max-entry-chars) s
      (str (subs s 0 (- max-entry-chars 3)) "..."))))

(defn validate-entry-length!
  "Reject entry content that exceeds max-entry-chars (abstract, summary, and note bodies)."
  [content]
  (let [len (count content)]
    (when (> len max-entry-chars)
      (println (str "Error: content is " len " characters (limit is " max-entry-chars ")."))
      (println)
      (println "Abstract, summary, and note bodies must stay within the limit.")
      (println "Shorten the text or split notes under a summary; link with 'See also:' and tags.")
      (println "  kb-abstract \"abstract/x\" \"Short blurb.\"")
      (println "  kb-summary \"summary/x\" \"Short blurb.\" \"abstract/parent\"")
      (println "  kb-store --parent \"summary/x\" \"topic/a\" \"One idea.\" tag1")
      (println "  kb-store --parent \"summary/x\" \"topic/b\" \"See also: topic/a\" tag1")
      (System/exit 1))))

;; ── Link Parsing ─────────────────────────────────────────────────

(defn parse-see-also
  "Extract topic IDs from 'See also: topic/a, topic/b' in content text."
  [content]
  (when-let [[_ refs-str] (re-find #"(?i)See also:\s*([^\n]+)" content)]
    (->> (clojure.string/split refs-str #",\s*")
         (map clojure.string/trim)
         (remove empty?)
         vec)))

(defn resolve-link-eids
  "Resolve topic strings to entity IDs. Silently skips non-existent topics."
  [db topics]
  (->> topics
       (keep #(ffirst (d/q '[:find ?e :in $ ?t :where [?e :kb/topic ?t]] db %)))
       vec))

(defn retract-old-links!
  "Remove all existing :kb/links from an entity before re-adding."
  [conn eid db]
  (let [old-links (d/q '[:find ?linked :in $ ?e :where [?e :kb/links ?linked]] db eid)]
    (when (seq old-links)
      (d/transact! conn (mapv (fn [[linked]] [:db/retract eid :kb/links linked]) old-links)))))

;; ── Datalog Rules ────────────────────────────────────────────────

(def hierarchy-rules
  "Recursive rule: find all descendants of an ancestor topic string via :kb/parent."
  '[[(descendant? ?anc-topic ?desc)
     [?desc :kb/parent ?anc-topic]]
    [(descendant? ?anc-topic ?desc)
     [?mid :kb/parent ?anc-topic]
     [?mid :kb/topic ?mid-topic]
     (descendant? ?mid-topic ?desc)]])

(def link-rules
  "Recursive rule: find all entities reachable via :kb/links refs."
  '[[(linked? ?src ?dst)
     [?src :kb/links ?dst]]
    [(linked? ?src ?dst)
     [?src :kb/links ?mid]
     (linked? ?mid ?dst)]])

(def all-rules
  "Combined rules for unified queries."
  (vec (concat hierarchy-rules link-rules)))

;; ── Validation ──────────────────────────────────────────────────────

(defn get-layer
  "Get the layer of a topic, or nil if it doesn't exist."
  [db topic]
  (ffirst (d/q '[:find ?layer :in $ ?topic
                 :where [?e :kb/topic ?topic]
                        [?e :kb/layer ?layer]]
               db topic)))

(defn list-topics-by-layer
  "List all topic strings for a given layer."
  [db layer]
  (->> (d/q '[:find ?topic :in $ ?layer
              :where [?e :kb/layer ?layer]
                     [?e :kb/topic ?topic]]
            db layer)
       (map first)
       sort))

(defn validate-parent!
  "Validate parent exists with the expected layer. Returns true or exits with helpful guidance."
  [db parent expected-parent-layer]
  (let [actual (get-layer db parent)]
    (cond
      (nil? actual)
      (let [available (list-topics-by-layer db expected-parent-layer)
            plural (if (= expected-parent-layer "summary") "summaries" (str expected-parent-layer "s"))]
        (println (str "Error: parent '" parent "' not found."))
        (if (seq available)
          (do (println (str "\nAvailable " plural ":"))
              (doseq [t available] (println (str "  " t)))
              (println (str "\nOr use --create-parents to auto-create missing hierarchy.")))
          (println (str "\nNo " plural " exist yet. Create one first:"
                        (if (= expected-parent-layer "abstract")
                          (str "\n  kb-abstract \"" parent "\" \"Description here\"")
                          (str "\n  kb-summary \"" parent "\" \"Description here\" \"<abstract-topic>\""
                               "\n\nOr use --create-parents to auto-create missing hierarchy.")))))
        (System/exit 1))

      (not= actual expected-parent-layer)
      (do (println (str "Error: parent '" parent "' is layer '" actual "', expected '" expected-parent-layer "'."))
          (System/exit 1))

      :else true)))

;; ── Store ───────────────────────────────────────────────────────────

(defn store-entry!
  "Store an entry with layer and parent validation.
   Auto-parses 'See also:' references in content and stores as :kb/links refs."
  [conn topic content tags source layer parent]
  (let [ts  (now)
        db  (d/db conn)
        existing (d/q '[:find ?e ?created
                        :in $ ?topic
                        :where [?e :kb/topic ?topic]
                               [?e :kb/created ?created]]
                      db topic)
        created (or (second (first existing)) ts)
        ;; Auto-parse "See also:" → :kb/links refs
        link-topics (parse-see-also content)
        link-eids   (when (seq link-topics) (resolve-link-eids db link-topics))
        entity (cond-> {:kb/topic   topic
                        :kb/content content
                        :kb/created created
                        :kb/updated ts
                        :kb/layer   layer}
                 (seq tags)      (assoc :kb/tags (vec tags))
                 source          (assoc :kb/source source)
                 parent          (assoc :kb/parent parent)
                 (seq link-eids) (assoc :kb/links link-eids))]
    ;; On update: retract old links before transacting new ones
    (when (seq existing)
      (retract-old-links! conn (ffirst existing) db))
    (d/transact! conn [entity])
    (if (seq existing)
      (println (str "Updated: " topic " [" layer "]"))
      (println (str "Stored: " topic " [" layer "]")))))

(defn infer-abstract-topic
  "Derive an abstract topic from a summary topic.
   e.g. 'summary/my-project-design' → 'abstract/my-project'"
  [summary-topic]
  (let [;; Strip 'summary/' prefix if present
        base (if (clojure.string/starts-with? summary-topic "summary/")
               (subs summary-topic (count "summary/"))
               summary-topic)
        ;; Try to get the project part (first segment before last hyphen group)
        parts (clojure.string/split base #"-")
        project (if (> (count parts) 1)
                  (clojure.string/join "-" (butlast parts))
                  base)]
    (str "abstract/" project)))

(defn ensure-parent-chain!
  "When --create-parents is used, auto-create missing abstract/summary parents.
   For a note: ensures parent summary exists (and its abstract).
   For a summary: ensures parent abstract exists."
  [conn parent expected-parent-layer]
  (let [db (d/db conn)
        actual (get-layer db parent)]
    (cond
      ;; Parent exists with correct layer — good
      (= actual expected-parent-layer)
      true

      ;; Parent exists with wrong layer — error
      (some? actual)
      (do (println (str "Error: parent '" parent "' is layer '" actual "', expected '" expected-parent-layer "'."))
          (System/exit 1))

      ;; Parent doesn't exist — create it
      (nil? actual)
      (case expected-parent-layer
        "summary"
        (do
          (println (str "Auto-creating missing summary: " parent))
          (let [abstract-topic (infer-abstract-topic parent)
                abstract-exists (get-layer db abstract-topic)]
            (when-not abstract-exists
              (println (str "Auto-creating missing abstract: " abstract-topic))
              (store-entry! conn abstract-topic
                            (fit-entry-content (str "Auto-created abstract for " parent))
                            nil nil "abstract" nil))
            (store-entry! conn parent
                          (str "Auto-created summary (update with real description)")
                          nil nil "summary" abstract-topic)))

        "abstract"
        (do
          (println (str "Auto-creating missing abstract: " parent))
          (store-entry! conn parent
                        (str "Auto-created abstract (update with real description)")
                        nil nil "abstract" nil))))))

(defn store-note! [topic content tags source parent & {:keys [create-parents]}]
  (validate-entry-length! content)
  (with-conn
    (fn [conn]
      (if create-parents
        (ensure-parent-chain! conn parent "summary")
        (validate-parent! (d/db conn) parent "summary"))
      (store-entry! conn topic content tags source "note" parent))))

(defn store-abstract! [topic content]
  (validate-entry-length! content)
  (with-conn
    (fn [conn]
      (store-entry! conn topic content nil nil "abstract" nil))))

(defn store-summary! [topic content parent & {:keys [create-parents]}]
  (validate-entry-length! content)
  (with-conn
    (fn [conn]
      (if create-parents
        (ensure-parent-chain! conn parent "abstract")
        (validate-parent! (d/db conn) parent "abstract"))
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

(defn find-match-eids
  "Find all entity IDs whose topic, content, or tags match the query."
  [db lq]
  (->> (d/q '[:find ?e :where [?e :kb/topic _]] db)
       (map first)
       (filter (fn [eid]
                 (let [entry (pull-entry db eid)]
                   (match-entry? entry lq))))
       vec))

(defn search-entries
  "Graph-aware search using recursive Datalog rules.
   1. Find all entities matching the query (any layer)
   2. Expand via hierarchy: descendants of matches (recursive :kb/parent)
   3. Expand via links: notes reachable through :kb/links refs (recursive)
   4. Include backlinks: notes that link TO matching entities
   5. Union, deduplicate, return notes sorted by recency."
  [conn query-str]
  (let [db (d/db conn)
        lq (clojure.string/lower-case query-str)

        ;; Step 1: find all matching entities (any layer)
        match-eids (find-match-eids db lq)]
    (if (empty? match-eids)
      []
      (let [;; Collect topics for hierarchy expansion
            match-topics (->> match-eids
                              (keep #(:topic (pull-entry db %)))
                              vec)

            ;; Step 2: hierarchy expansion — recursive descendant? rule
            descendant-eids
            (set (map first
              (d/q '[:find ?desc
                      :in $ % [?anc-topic ...]
                      :where (descendant? ?anc-topic ?desc)
                             [?desc :kb/layer "note"]]
                   db hierarchy-rules match-topics)))

            ;; Step 3: link expansion — recursive linked? rule
            linked-eids
            (set (map first
              (d/q '[:find ?dst
                      :in $ % [?src ...]
                      :where (linked? ?src ?dst)
                             [?dst :kb/layer "note"]]
                   db link-rules match-eids)))

            ;; Step 4: backlinks — notes that link TO any match
            backlink-eids
            (set (map first
              (d/q '[:find ?src
                      :in $ [?dst ...]
                      :where [?src :kb/links ?dst]
                             [?src :kb/layer "note"]]
                   db match-eids)))

            ;; Direct note matches
            direct-note-eids
            (set (filter
                   (fn [eid] (= "note" (:layer (pull-entry db eid))))
                   match-eids))

            ;; Union all note eids
            all-note-eids (clojure.set/union
                            direct-note-eids
                            descendant-eids
                            linked-eids
                            backlink-eids)]
        (->> all-note-eids
             (keep #(pull-entry db %))
             (sort-by :updated >))))))

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
            (when (seq (:links entry))
              (println (str "Links: " (clojure.string/join ", " (:links entry)))))
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

;; ── Links & Backlinks ──────────────────────────────────────────────

(defn backlinks [topic]
  (with-conn
    (fn [conn]
      (let [db  (d/db conn)
            eid (ffirst (d/q '[:find ?e :in $ ?t :where [?e :kb/topic ?t]] db topic))]
        (if-not eid
          (println (str "Not found: " topic))
          (let [;; Direct backlinks
                direct (d/q '[:find ?src-topic
                              :in $ ?dst
                              :where [?src :kb/links ?dst]
                                     [?src :kb/topic ?src-topic]]
                            db eid)
                ;; Transitive backlinks (everything that can reach this via links)
                transitive (d/q '[:find ?src-topic
                                  :in $ % ?dst
                                  :where (linked? ?src ?dst)
                                         [?src :kb/topic ?src-topic]]
                                db link-rules eid)
                all-topics (->> (concat (map first direct) (map first transitive))
                                distinct
                                (remove #(= % topic))
                                sort)]
            (if (seq all-topics)
              (do (println (str "Backlinks to " topic ":"))
                  (doseq [t all-topics]
                    (println (str "  <- " t))))
              (println (str "No backlinks to " topic)))))))))

(defn migrate-links!
  "Scan all entries with 'See also:' in content and populate :kb/links refs."
  []
  (with-conn
    (fn [conn]
      (let [db       (d/db conn)
            all-eids (map first (d/q '[:find ?e :where [?e :kb/topic _]] db))
            migrated (atom 0)]
        (doseq [eid all-eids]
          (let [entry       (pull-entry db eid)
                link-topics (parse-see-also (:content entry))
                link-eids   (when (seq link-topics)
                              (resolve-link-eids db link-topics))]
            (when (seq link-eids)
              ;; Retract old links, add new
              (retract-old-links! conn eid db)
              (d/transact! conn [{:db/id eid :kb/links link-eids}])
              (swap! migrated inc)
              (println (str "  Linked: " (:topic entry) " → " (pr-str link-topics))))))
        (println (str "Migration complete. " @migrated " entries linked."))))))

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

(defn parse-boolean-flag
  "Check for a boolean --flag (no value). Returns {:present? bool :rest-args remaining}."
  [args flag-name]
  (let [flag (str "--" flag-name)
        idx (.indexOf (vec args) flag)]
    (if (>= idx 0)
      {:present? true
       :rest-args (concat (take idx args) (drop (inc idx) args))}
      {:present? false :rest-args args})))

;; ── Main Dispatch ───────────────────────────────────────────────────

(let [[cmd & args] *command-line-args*]
  (case cmd
    "store"
    (let [{cp-present? :present? cp-args :rest-args} (parse-boolean-flag args "create-parents")
          {:keys [value rest-args]} (parse-flag cp-args "parent")
          parent value
          [topic content & tags] rest-args]
      (when-not (and topic content parent)
        (println "Usage: bb scripts/kb.clj store --parent <parent-summary> <topic> <content> [tags...]")
        (println "       --create-parents   Auto-create missing parent summary and abstract")
        (System/exit 1))
      (store-note! topic content tags (or (System/getenv "DATALEVIN_KB_SOURCE")
                                          (System/getenv "CLAUDE_KB_SOURCE")) parent
                   :create-parents cp-present?))

    "abstract"
    (let [[topic content] args]
      (when-not (and topic content)
        (println "Usage: bb scripts/kb.clj abstract <topic> <content>")
        (System/exit 1))
      (store-abstract! topic content))

    "summary"
    (let [{cp-present? :present? cp-args :rest-args} (parse-boolean-flag args "create-parents")
          [topic content parent] cp-args]
      (when-not (and topic content parent)
        (println "Usage: bb scripts/kb.clj summary <topic> <content> <parent-abstract>")
        (println "       --create-parents   Auto-create missing parent abstract")
        (System/exit 1))
      (store-summary! topic content parent :create-parents cp-present?))

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

    "backlinks"
    (let [[topic] args]
      (when-not topic
        (println "Usage: bb scripts/kb.clj backlinks <topic>")
        (System/exit 1))
      (backlinks topic))

    "migrate-links"
    (migrate-links!)

    (do
      (println "Latadevin Knowledge Base")
      (println)
      (println "Commands:")
      (println (str "  store        --parent <summary> [--create-parents] <topic> <content> [tags...]  (content <= " max-entry-chars " chars)"))
      (println (str "  abstract     <topic> <content>  (content <= " max-entry-chars " chars)"))
      (println (str "  summary      [--create-parents] <topic> <content> <parent-abstract>  (content <= " max-entry-chars " chars)"))
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
      (println "  backlinks    <topic>                                        Show what links to a topic")
      (println "  migrate-links                                               Populate :kb/links from See also: refs")
      (println)
      (println "Flags:")
      (println "  --create-parents   Auto-create missing parent abstract/summary hierarchy")
      (println)
      (println (str "Database: " db-path)))))
