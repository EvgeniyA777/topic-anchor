(ns topic-anchor.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [topic-anchor.document :as document]
            [topic-anchor.fs :as fs]
            [topic-anchor.ollama :as ollama]
            [topic-anchor.scoring :as scoring]))

(def cli-options
  [["-a" "--anchor PATH" "Path to one known-good in-topic HTML or Markdown file"]
   ["-d" "--dir PATH" "Directory to scan"]
   ["-m" "--model MODEL" "Ollama embedding model name"]
   ["-b" "--base-url URL" "Ollama base URL" :default "http://127.0.0.1:11434"]
   [nil "--recursive BOOLEAN" "Recurse into subdirectories"
    :default true
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

(defn embed-or-error [base-url model text]
  (let [result (ollama/embed-text base-url model text)]
    (if (:ok? result)
      result
      (runtime-error (format-ollama-error base-url model result)))))

(defn classify-results [results]
  (let [scores (mapv :score results)
        policy (scoring/threshold-policy scores)]
    (if policy
      (mapv #(assoc % :status (scoring/classify-score (:score %) policy))
            results)
      (let [review-idx (scoring/review-index scores)]
        (mapv (fn [idx result]
                (assoc result :status (when (= idx review-idx) "REVIEW")))
              (range)
              results)))))

(defn format-result-row [{:keys [status score relative-path]}]
  (format "%s\t%.4f\t%s" (or status "-") (double score) relative-path))

(defn format-skipped-row [{:keys [reason relative-path]}]
  (str reason "\t" relative-path))

(defn status-counts [classified]
  (reduce
   (fn [counts {:keys [status]}]
     (update counts (or status "-") (fnil inc 0)))
   {}
   classified))

(defn summary-row [counts label]
  (str label ": " (get counts label 0)))

(defn report-lines [options classified skipped total-html top]
  (let [highlights (take top classified)
        counts (status-counts classified)
        sample-mode (if (>= (count classified) 6) "threshold-policy" "small-sample")]
    (vec
     (concat
      [(str "Anchor: " (:anchor options))
       (str "Directory: " (:dir options))
       (str "Model: " (:model options))
       (str "Mode: " sample-mode)
       ""
       (format "Supported candidates: %d" total-html)
       (format "Comparable: %d" (count classified))
       (format "Skipped: %d" (count skipped))
       ""
       "Highlights"
       "STATUS\tSCORE\tPATH"]
      (map format-result-row highlights)
      ["" "Full ranking" "STATUS\tSCORE\tPATH"]
      (map format-result-row classified)
      (when (seq skipped)
        ["" "Skipped" "REASON\tPATH"])
      (map format-skipped-row skipped)
      ["" "Summary"
       (summary-row counts "OK")
       (summary-row counts "SUSPECT")
       (summary-row counts "OUTLIER")
       (summary-row counts "REVIEW")]))))

(defn score-candidates [options anchor-text comparable]
  (let [anchor-embedding (embed-or-error (:base-url options)
                                         (:model options)
                                         anchor-text)]
    (if-not (:ok? anchor-embedding)
      anchor-embedding
      (loop [remaining comparable
             acc []]
        (if-let [candidate (first remaining)]
          (let [embedding-result (embed-or-error (:base-url options)
                                                 (:model options)
                                                 (:text candidate))]
            (if-not (:ok? embedding-result)
              embedding-result
              (recur (next remaining)
                     (conj acc (assoc candidate
                                      :score (double
                                              (scoring/cosine-similarity
                                               (:embedding anchor-embedding)
                                               (:embedding embedding-result))))))))
          {:ok? true
           :results (vec (sort-by :score acc))})))))

(defn run-command [options]
  (let [anchor-result (document/extract-text (:anchor options))]
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
              (let [scored (score-candidates options (:text anchor-result) comparable)]
                (if-not (:ok? scored)
                  scored
                  {:ok? true
                   :exit-code 0
                   :lines (report-lines options
                                        (classify-results (:results scored))
                                        skipped
                                        total-html
                                        (:top options))})))))))))

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
