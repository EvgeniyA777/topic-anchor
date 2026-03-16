(ns topic-anchor.launcher
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [topic-anchor.fs :as fs]))

(def default-model "nomic-embed-text")
(def default-top 5)
(def default-chunk-size 1800)
(def default-chunk-overlap 200)
(def default-report-name "topic-anchor-semantic-report.txt")

(def command-option-specs
  [{:env "TOPIC_ANCHOR_MODEL" :default default-model :flag "--model"}
   {:env "TOPIC_ANCHOR_TOP" :default (str default-top) :flag "--top"}
   {:env "TOPIC_ANCHOR_CHUNK_SIZE" :default (str default-chunk-size) :flag "--chunk-size"}
   {:env "TOPIC_ANCHOR_CHUNK_OVERLAP" :default (str default-chunk-overlap) :flag "--chunk-overlap"}
   {:env "TOPIC_ANCHOR_CLUSTER_THRESHOLD" :flag "--cluster-threshold"}
   {:env "TOPIC_ANCHOR_BASE_URL" :flag "--base-url"}
   {:env "TOPIC_ANCHOR_RECURSIVE" :flag "--recursive"}
   {:env "TOPIC_ANCHOR_INCLUDE_HIDDEN" :flag "--include-hidden"}])

(defn trim-to-nil [value]
  (when (some? value)
    (let [trimmed (str/trim (str value))]
      (when-not (str/blank? trimmed)
        trimmed))))

(defn canonicalize-directory [path]
  (let [trimmed (trim-to-nil path)]
    (when-not trimmed
      (throw (ex-info "Folder path is required" {:kind :input})))
    (let [candidate (io/file trimmed)
          display-path (str (fs/normalize-path trimmed))]
      (when-not (.exists candidate)
        (throw (ex-info (str "Directory does not exist: " display-path)
                        {:kind :input :path display-path})))
      (when-not (.isDirectory candidate)
        (throw (ex-info (str "Path is not a directory: " display-path)
                        {:kind :input :path display-path})))
      (str (fs/canonical-path candidate)))))

(defn selectable-targets [dir]
  (let [root (canonicalize-directory dir)
        candidates (fs/candidate-paths root {:recursive true
                                             :include-hidden false
                                             :anchor nil})
        entries (->> candidates
                     (mapv (fn [path]
                             {:path (str (fs/canonical-path path))
                              :relative-path (fs/relative-display-path root path)
                              :file-name (.getName (io/file path))})))]
    (when (empty? entries)
      (throw (ex-info (str "No supported .html, .htm, or .md files found in " root)
                      {:kind :input :path root})))
    entries))

(defn selection->entry [entries selection]
  (let [trimmed (trim-to-nil selection)]
    (when-not trimmed
      (throw (ex-info "Target file selection is required" {:kind :input})))
    (if-let [index (try
                     (parse-long trimmed)
                     (catch NumberFormatException _ nil))]
      (let [entry (nth entries (dec index) nil)]
        (when-not entry
          (throw (ex-info (format "Selection %d is out of range 1..%d"
                                  index
                                  (count entries))
                          {:kind :input :selection trimmed})))
        entry)
      (let [exact-relative (first (filter #(= trimmed (:relative-path %)) entries))
            exact-absolute (let [candidate (io/file trimmed)]
                             (when (.isAbsolute candidate)
                               (let [canonical (str (fs/canonical-path candidate))]
                                 (first (filter #(= canonical (:path %)) entries)))))
            canonical-relative (first (filter #(= trimmed (:file-name %)) entries))]
        (cond
          exact-relative exact-relative
          exact-absolute exact-absolute
          (= 1 (count (filter #(= trimmed (:file-name %)) entries)))
          canonical-relative
          (< 1 (count (filter #(= trimmed (:file-name %)) entries)))
          (throw (ex-info (str "Filename is ambiguous; use the listed relative path instead: " trimmed)
                          {:kind :input :selection trimmed}))
          :else
          (throw (ex-info (str "Target file was not found in the selected folder: " trimmed)
                          {:kind :input :selection trimmed})))))))

(defn env-value [{:keys [env default]}]
  (or (trim-to-nil (System/getenv env))
      default))

(defn clojure-command []
  (or (trim-to-nil (System/getenv "TOPIC_ANCHOR_CLOJURE_CMD"))
      "clojure"))

(defn command-args [dir target-path]
  (into [(clojure-command)
         "-M"
         "-m"
         "topic-anchor.core"
         "--anchor"
         target-path
         "--dir"
         dir]
        (mapcat (fn [{:keys [flag] :as spec}]
                  (when-let [value (env-value spec)]
                    [flag value]))
                command-option-specs)))

(defn prompt! [message]
  (print message)
  (flush)
  (trim-to-nil (read-line)))

(defn print-targets! [dir entries]
  (println (str "Supported files in " dir ":"))
  (doseq [[idx entry] (map-indexed vector entries)]
    (println (format "%d. %s" (inc idx) (:relative-path entry)))))

(defn report-path [dir]
  (str (io/file dir default-report-name)))

(defn run-core-command! [dir target-path]
  (let [process-builder (doto (ProcessBuilder. ^java.util.List (command-args dir target-path))
                          (.directory (io/file (System/getProperty "user.dir")))
                          (.redirectErrorStream true))
        process (.start process-builder)
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)
        report-file (report-path dir)]
    (spit report-file output)
    (print output)
    (when (and (seq output) (not (str/ends-with? output "\n")))
      (println))
    (println (str "Report saved: " report-file))
    exit-code))

(defn choose-directory [args]
  (canonicalize-directory
   (or (first args)
       (prompt! "Paste folder path to compare: "))))

(defn choose-target [entries]
  (selection->entry
   entries
   (prompt! "Select target by number, listed path, or absolute path: ")))

(defn run-launcher! [args]
  (try
    (let [dir (choose-directory args)
          entries (selectable-targets dir)]
      (print-targets! dir entries)
      (let [{:keys [path relative-path]} (choose-target entries)
            exit-code (do
                        (println (str "Target: " relative-path))
                        (run-core-command! dir path))]
        (when-not (zero? exit-code)
          (throw (ex-info (str "semantic-compare failed with exit code " exit-code)
                          {:kind :runtime :exit-code exit-code})))))
    (catch clojure.lang.ExceptionInfo ex
      (binding [*out* *err*]
        (println (.getMessage ex)))
      (throw ex))))

(defn -main [& args]
  (try
    (run-launcher! args)
    (System/exit 0)
    (catch clojure.lang.ExceptionInfo ex
      (System/exit (if (= :runtime (:kind (ex-data ex))) 1 2)))))
