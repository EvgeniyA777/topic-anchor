(ns topic-anchor.core
  (:gen-class)
  (:require [clojure.string :as str]
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

(defn validate-cli [{:keys [options errors]}]
  (cond
    (seq errors) {:ok? false :exit-code 2 :message (str/join \newline errors)}
    (:help options) {:ok? false :exit-code 0 :message (usage nil)}
    :else {:ok? true :options options}))

(defn -main [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)
        {:keys [ok? exit-code message]} (validate-cli {:options options :errors errors})]
    (if ok?
      (do
        (println "Bootstrap complete. Pipeline implementation is not wired yet.")
        (println "Run with --help to see the planned CLI surface.")
        0)
      (do
        (println (if (:help options) (usage summary) (or message (usage summary))))
        (System/exit exit-code)))))
