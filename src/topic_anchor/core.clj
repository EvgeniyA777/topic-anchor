(ns topic-anchor.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [topic-anchor.document :as document]
            [topic-anchor.fs :as fs]
            [topic-anchor.ollama :as ollama]
            [topic-anchor.scoring :as scoring]))

(def default-chunk-size 1800)
(def default-chunk-overlap 200)
(def default-cluster-threshold 0.9)

(def cli-options
  [["-a" "--anchor PATH" "Path to one known-good in-topic HTML or Markdown file"]
   ["-d" "--dir PATH" "Directory to scan"]
   ["-m" "--model MODEL" "Ollama embedding model name"]
   [nil "--cluster-threshold SCORE" "Similarity threshold for strong-neighbor edges"
    :default default-cluster-threshold
    :parse-fn #(Double/parseDouble %)]
   [nil "--chunk-size N" "Characters per embedding chunk"
    :default default-chunk-size
    :parse-fn #(Integer/parseInt %)]
   [nil "--chunk-overlap N" "Characters of overlap between chunks"
    :default default-chunk-overlap
    :parse-fn #(Integer/parseInt %)]
   ["-b" "--base-url URL" "Ollama base URL" :default "http://127.0.0.1:11434"]
   [nil "--recursive BOOLEAN" "Recurse into subdirectories"
    :default false
    :parse-fn #(Boolean/parseBoolean %)]
   [nil "--include-hidden BOOLEAN" "Include hidden files and directories"
    :default false
    :parse-fn #(Boolean/parseBoolean %)]
   [nil "--top N" "Number of lowest-score results to highlight"
    :default 5
    :parse-fn #(Integer/parseInt %)]
   ["-h" "--help"]])

(def required-option-keys [:anchor :dir :model])

(defn usage [summary]
  (str/join
   \newline
   ["topic-anchor"
    ""
    "Semantic topic screening for HTML and Markdown folders using one trusted anchor file."
    ""
    "Usage:"
    "  clojure -M -m topic-anchor.core --anchor ./good.html --dir ./batch --model nomic-embed-text"
    ""
    "Options:"
    (or summary "")]))

(defn input-error [message]
  {:ok? false :exit-code 2 :message message})

(defn runtime-error [message]
  {:ok? false :exit-code 3 :message message})

(defn model-pull-command [model]
  (str "ollama pull " model))

(defn model-installed? [requested installed]
  (or (= requested installed)
      (and (not (str/includes? requested ":"))
           (= (str requested ":latest") installed))))

(defn blank-option? [value]
  (or (nil? value)
      (and (string? value)
           (str/blank? value))))

(defn option-label [k]
  (str "--" (name k)))

(defn existing-file? [path]
  (.isFile (io/file path)))

(defn existing-directory? [path]
  (.isDirectory (io/file path)))

(defn non-negative-int? [value]
  (and (integer? value)
       (not (neg? value))))

(defn similarity-threshold? [value]
  (and (number? value)
       (<= 0.0 (double value) 1.0)))

(defn validation-errors [options]
  (let [missing (->> required-option-keys
                     (filter #(blank-option? (get options %)))
                     (map option-label))
        errors (transient [])]
    (when (seq missing)
      (conj! errors (str "Missing required option(s): " (str/join ", " missing))))
    (when-let [anchor (:anchor options)]
      (when-not (blank-option? anchor)
        (when-not (existing-file? anchor)
          (conj! errors (str "Anchor file does not exist: " anchor)))
        (when-not (fs/supported-path? anchor)
          (conj! errors (str "Anchor file must be .html, .htm, or .md: " anchor)))))
    (when-let [dir (:dir options)]
      (when-not (blank-option? dir)
        (when-not (existing-directory? dir)
          (conj! errors (str "Directory to scan does not exist: " dir)))))
    (when (and (contains? options :top)
               (not (pos-int? (:top options))))
      (conj! errors "--top must be a positive integer"))
    (when (and (contains? options :cluster-threshold)
               (not (similarity-threshold? (:cluster-threshold options))))
      (conj! errors "--cluster-threshold must be between 0.0 and 1.0"))
    (when (and (contains? options :chunk-size)
               (not (pos-int? (:chunk-size options))))
      (conj! errors "--chunk-size must be a positive integer"))
    (when (and (contains? options :chunk-overlap)
               (not (non-negative-int? (:chunk-overlap options))))
      (conj! errors "--chunk-overlap must be a non-negative integer"))
    (when (and (pos-int? (:chunk-size options))
               (non-negative-int? (:chunk-overlap options))
               (>= (:chunk-overlap options) (:chunk-size options)))
      (conj! errors "--chunk-overlap must be smaller than --chunk-size"))
    (persistent! errors)))

(defn validate-cli [{:keys [options errors]}]
  (cond
    (seq errors) {:ok? false :exit-code 2 :message (str/join \newline errors)}
    (:help options) {:ok? false :exit-code 0 :message :help}
    (seq (validation-errors options)) {:ok? false
                                       :exit-code 2
                                       :message (str/join \newline (validation-errors options))}
    :else {:ok? true :options options}))

(defn reason-code [reason]
  ({:empty-text "EMPTY_TEXT"
    :read-error "READ_ERROR"
    :http-error "HTTP_ERROR"
    :request-failed "REQUEST_FAILED"
    :missing-embedding "MALFORMED_RESPONSE"
    :malformed-response "MALFORMED_RESPONSE"}
   reason
   "UNKNOWN"))

(defn prepare-candidates [options]
  (let [candidates (fs/candidate-paths (:dir options)
                                       {:recursive (:recursive options)
                                        :include-hidden (:include-hidden options)
                                        :anchor (:anchor options)})]
    (assoc
     (reduce
      (fn [{:keys [comparable skipped]} path]
        (let [relative-path (fs/relative-display-path (:dir options) path)
              result (document/extract-text path)]
          (if (:ok? result)
            {:comparable (conj comparable {:path path
                                           :relative-path relative-path
                                           :text (:text result)})
             :skipped skipped}
            {:comparable comparable
             :skipped (conj skipped {:relative-path relative-path
                                     :reason (reason-code (:reason result))})})))
      {:comparable [] :skipped []}
     candidates)
     :total-html (count candidates))))

(defn format-ollama-error [base-url model {:keys [reason message]}]
  (case reason
    :model-not-found
    (format "Ollama model '%s' is not installed at %s. Run: %s"
            model
            base-url
            (model-pull-command model))

    :service-unreachable
    (format "Ollama is not reachable at %s. Start it with 'ollama serve' and verify /api/tags responds."
            base-url)

    (format "Ollama request failed (%s): %s"
            (name reason)
            (or message "no details"))))

(defn ensure-model-available [options]
  (let [result (ollama/list-models (:base-url options))]
    (cond
      (:ok? result)
      (if (some #(model-installed? (:model options) %) (:models result))
        {:ok? true}
        (runtime-error
         (format "Ollama model '%s' is not installed at %s. Run: %s"
                 (:model options)
                 (:base-url options)
                 (model-pull-command (:model options)))))

      :else
      (runtime-error (format-ollama-error (:base-url options) (:model options) result)))))

(defn embed-or-error
  ([base-url model text]
   (embed-or-error (ollama/make-client) base-url model text))
  ([client base-url model text]
   (let [result (ollama/embed-text client base-url model text)]
     (if (:ok? result)
       result
       (runtime-error (format-ollama-error base-url model result))))))

(defn average-embeddings [embeddings]
  (let [sample-count (double (count embeddings))
        width (count (first embeddings))]
    (vec
     (for [idx (range width)]
       (/ (reduce + (map (fn [embedding]
                           (double (nth embedding idx)))
                         embeddings))
          sample-count)))))

(defn document-embedding-or-error [client options text]
  (let [chunk-size (or (:chunk-size options) default-chunk-size)
        chunk-overlap (or (:chunk-overlap options) default-chunk-overlap)
        chunks (document/chunk-text text chunk-size chunk-overlap)]
    (if (empty? chunks)
      (runtime-error "Document produced no embedding chunks after extraction")
      (loop [remaining chunks
             acc []]
        (if-let [chunk (first remaining)]
          (let [result (embed-or-error client
                                       (:base-url options)
                                       (:model options)
                                       chunk)]
            (if-not (:ok? result)
              result
              (recur (next remaining)
                     (conj acc (:embedding result)))))
          {:ok? true
           :embedding (average-embeddings acc)})))))

(defn format-skipped-row [{:keys [reason relative-path]}]
  (str reason "\t" relative-path))

(defn mean [values]
  (if (seq values)
    (/ (reduce + (map double values))
       (double (count values)))
    0.0))

(defn prepare-documents [options anchor-result comparable]
  (vec
   (cons {:path (:anchor options)
          :relative-path (fs/relative-display-path (:dir options) (:anchor options))
          :text (:text anchor-result)
          :target? true}
         comparable)))

(defn embed-documents-or-error [options documents]
  (let [client (ollama/make-client)]
    (loop [remaining documents
           acc []]
      (if-let [document (first remaining)]
        (let [embedding-result (document-embedding-or-error client options (:text document))]
          (if-not (:ok? embedding-result)
            embedding-result
            (recur (next remaining)
                   (conj acc (assoc document :embedding (:embedding embedding-result))))))
        {:ok? true
         :documents (vec acc)}))))

(defn pairwise-similarity-matrix [documents]
  (reduce
   (fn [matrix [left right]]
     (let [score (double (scoring/cosine-similarity (:embedding (nth documents left))
                                                    (:embedding (nth documents right))))]
       (-> matrix
           (update left assoc right score)
           (update right assoc left score))))
   (vec (repeat (count documents) {}))
   (for [left (range (count documents))
         right (range (inc left) (count documents))]
     [left right])))

(defn strong-neighbor-indices [matrix idx threshold]
  (->> (get matrix idx)
       (keep (fn [[neighbor score]]
               (when (>= score threshold)
                 neighbor)))
       sort
       vec))

(defn connected-components [matrix threshold]
  (loop [remaining (set (range (count matrix)))
         components []]
    (if-let [start (first remaining)]
      (let [component (loop [queue [start]
                             seen #{start}]
                        (if-let [idx (first queue)]
                          (let [neighbors (remove seen (strong-neighbor-indices matrix idx threshold))]
                            (recur (into (vec (rest queue)) neighbors)
                                   (into seen neighbors)))
                          (vec (sort seen))))]
        (recur (reduce disj remaining component)
               (conj components component)))
      (vec components))))

(defn member-stats [documents matrix threshold member-indices idx]
  (let [document (nth documents idx)
        all-scores (vals (get matrix idx))
        cluster-scores (keep #(get-in matrix [idx %])
                             (remove #{idx} member-indices))]
    {:idx idx
     :document document
     :avg-similarity (mean all-scores)
     :avg-cluster-similarity (mean cluster-scores)
     :strong-neighbor-count (count (filter #(>= % threshold) all-scores))}))

(defn cluster-cohesion [matrix member-indices]
  (let [pairs (for [left member-indices
                    right member-indices
                    :when (< left right)]
                (get-in matrix [left right]))]
    (mean pairs)))

(defn rank-cluster-members [members]
  (->> members
       (sort-by (juxt (comp - :avg-cluster-similarity)
                      (comp - :strong-neighbor-count)
                      (comp - :avg-similarity)
                      (comp :relative-path :document)))
       vec
       (map-indexed (fn [idx member]
                      (assoc member :member-rank (inc idx))))
       vec))

(defn analyze-documents [options documents]
  (let [threshold (or (:cluster-threshold options) default-cluster-threshold)
        matrix (pairwise-similarity-matrix documents)
        clusters (->> (connected-components matrix threshold)
                      (map-indexed
                       (fn [cluster-id member-indices]
                         (let [members (rank-cluster-members
                                        (mapv #(member-stats documents matrix threshold member-indices %)
                                              member-indices))]
                           {:cluster-id cluster-id
                            :size (count member-indices)
                            :cohesion (cluster-cohesion matrix member-indices)
                            :members members
                            :target? (boolean (some #(get-in % [:document :target?]) members))
                            :mean-similarity (mean (map :avg-similarity members))})))
                      (sort-by (juxt (comp - :size)
                                     (comp - :cohesion)
                                     (comp - :mean-similarity)
                                     :cluster-id))
                      vec)
        ranked-clusters (map-indexed (fn [idx cluster]
                                       (assoc cluster :cluster-rank (inc idx)))
                                     clusters)
        target-cluster (first (filter :target? ranked-clusters))
        target-member (first (filter #(get-in % [:document :target?]) (:members target-cluster)))
        target-verdict (cond
                         (= 1 (:size target-cluster)) "OUTLIER"
                         (= 1 (:cluster-rank target-cluster)) "IN_CLUSTER"
                         :else "SEPARATE_CLUSTER")]
    {:ok? true
     :documents documents
     :clusters ranked-clusters
     :threshold threshold
     :target-cluster target-cluster
     :target-member target-member
     :target-verdict target-verdict}))

(defn format-cluster-row [{:keys [cluster-rank size cohesion target?]}]
  (format "%d\t%d\t%.4f\t%s"
          cluster-rank
          size
          (double cohesion)
          (if target? "TARGET" "-")))

(defn format-member-row [{:keys [member-rank document avg-similarity avg-cluster-similarity strong-neighbor-count]}]
  (format "%d\t%s\t%.4f\t%.4f\t%d\t%s"
          member-rank
          (if (:target? document) "TARGET" "-")
          (double avg-similarity)
          (double avg-cluster-similarity)
          strong-neighbor-count
          (:relative-path document)))

(defn elapsed-millis [start-nanos]
  (long (/ (- (System/nanoTime) start-nanos) 1000000)))

(defn report-lines [options analysis skipped total-html elapsed-ms]
  (let [{:keys [clusters threshold target-cluster target-member target-verdict documents]} analysis]
    (vec
     (concat
      [(str "Target: " (:anchor options))
       (str "Directory: " (:dir options))
       (str "Model: " (:model options))
       (format "Cluster threshold: %.4f" (double threshold))
       (format "Processing time: %d ms" elapsed-ms)
       ""
       (format "Supported candidates: %d" total-html)
       (format "Comparable: %d" (dec (count documents)))
       (format "Skipped: %d" (count skipped))
       (format "Clusters: %d" (count clusters))
       ""
       "Target analysis"
       (str "Target verdict: " target-verdict)
       (format "Target cluster rank: %d/%d" (:cluster-rank target-cluster) (count clusters))
       (format "Target cluster size: %d" (:size target-cluster))
       (format "Target rank in cluster: %d/%d" (:member-rank target-member) (:size target-cluster))
       (format "Target average similarity: %.4f" (double (:avg-similarity target-member)))
       (format "Target strong neighbors: %d" (:strong-neighbor-count target-member))
       ""
       "Clusters"
       "CLUSTER\tSIZE\tCOHESION\tTARGET"]
      (map format-cluster-row clusters)
      (mapcat (fn [cluster]
                (concat
                 ["" (format "Cluster %d Members" (:cluster-rank cluster))
                  "RANK\tROLE\tAVG_ALL\tAVG_CLUSTER\tSTRONG\tPATH"]
                 (map format-member-row (:members cluster))))
              clusters)
      (when (seq skipped)
        ["" "Skipped" "REASON\tPATH"])
      (map format-skipped-row skipped)))))

(defn run-command [options]
  (let [start-nanos (System/nanoTime)
        anchor-result (document/extract-text (:anchor options))]
    (cond
      (not (:ok? anchor-result))
      (input-error
       (str "Anchor file could not be used: " (reason-code (:reason anchor-result))
            (when-let [message (:message anchor-result)]
              (str " (" message ")"))))

      :else
      (let [{:keys [comparable skipped total-html]} (prepare-candidates options)]
        (if (empty? comparable)
          (input-error "No comparable .html, .htm, or .md files were found after filtering and extraction")
          (let [model-check (ensure-model-available options)]
            (if-not (:ok? model-check)
              model-check
              (let [embedded (embed-documents-or-error options
                                                       (prepare-documents options anchor-result comparable))]
                (if-not (:ok? embedded)
                  embedded
                  {:ok? true
                   :exit-code 0
                   :lines (report-lines options
                                        (analyze-documents options (:documents embedded))
                                        skipped
                                        total-html
                                        (elapsed-millis start-nanos))})))))))))

(defn -main [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)
        validation (validate-cli {:options options :errors errors})]
    (if (:ok? validation)
      (let [{:keys [ok? exit-code lines message]} (run-command (:options validation))]
        (doseq [line (or lines [message])]
          (println line))
        (when-not ok?
          (System/exit exit-code)))
      (do
        (println (if (= :help (:message validation))
                   (usage summary)
                   (or (:message validation) (usage summary))))
        (System/exit (:exit-code validation))))))
