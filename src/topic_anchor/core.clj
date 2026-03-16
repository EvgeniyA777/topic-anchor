(ns topic-anchor.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

(def cli-options
  [["-a" "--anchor PATH" "Path to one known-good in-topic HTML file"]
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

(defn usage [summary]
  (str/join
   \newline
   ["topic-anchor"
    ""
    "Semantic topic screening for HTML folders using one trusted anchor file."
    ""
    "Usage:"
    "  clojure -M -m topic-anchor.core --anchor ./good.html --dir ./batch --model nomic-embed-text"
    ""
   "Options:"
    summary]))

(def required-option-keys [:anchor :dir :model])

(defn- blank-option? [value]
  (or (nil? value)
      (and (string? value)
           (str/blank? value))))

(defn- option-label [k]
  (str "--" (name k)))

(defn- existing-file? [path]
  (.isFile (io/file path)))

(defn- existing-directory? [path]
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
          (conj! errors (str "Anchor file does not exist: " anchor)))))
    (when-let [dir (:dir options)]
      (when-not (blank-option? dir)
        (when-not (existing-directory? dir)
          (conj! errors (str "Directory to scan does not exist: " dir)))))
    (persistent! errors)))

(defn validate-cli [{:keys [options errors]}]
  (cond
    (seq errors) {:ok? false :exit-code 2 :message (str/join \newline errors)}
    (:help options) {:ok? false :exit-code 0 :message :help}
    (seq (validation-errors options)) {:ok? false
                                       :exit-code 2
                                       :message (str/join \newline (validation-errors options))}
    :else {:ok? true :options options}))

(defn -main [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)
        {:keys [ok? exit-code message]} (validate-cli {:options options :errors errors})]
    (if ok?
      (do
        (println "Bootstrap CLI check passed.")
        (println "Anchor:" (:anchor options))
        (println "Directory:" (:dir options))
        (println "Model:" (:model options))
        (println "Base URL:" (:base-url options))
        (println "Recursive:" (:recursive options))
        (println "Include hidden:" (:include-hidden options))
        (println "Top results:" (:top options))
        (println "Pipeline implementation is not wired yet.")
        0)
      (do
        (println (if (= :help message) (usage summary) (or message (usage summary))))
        (System/exit exit-code)))))
