(ns topic-anchor.core-integration-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.core :as core]
            [topic-anchor.test-support :as test-support]))

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "topic-anchor-integration-test"
             (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile dir)]
    (try
      (f file)
      (finally
        (doseq [candidate (reverse (file-seq file))]
          (.delete candidate))))))

(defn write-html! [root name body]
  (let [file (io/file root name)]
    (spit file (str "<html><body>" body "</body></html>"))
    file))

(defn write-md! [root name body]
  (let [file (io/file root name)]
    (spit file body)
    file))

(defn embedding-for-score [score]
  [score (Math/sqrt (double (max 0.0 (- 1.0 (* score score)))) )])

(defn embedding-handler [embeddings]
  (fn [exchange]
    (let [payload (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
          input (:input payload)]
      (if-let [embedding (get embeddings input)]
        (test-support/write-json! exchange 200 {:embeddings [embedding]})
        (test-support/write-json! exchange 404 {:error (str "no embedding for input: " input)})))))

(defn tags-handler [models]
  (fn [exchange]
    (test-support/write-json! exchange 200 {:models (mapv (fn [model] {:name model}) models)})))

(defn run-with-server [options handler]
  (test-support/with-http-server
    {:embed handler
     :tags (tags-handler [(:model options)])}
    (fn [{:keys [base-url]}]
      (core/run-command (assoc options :base-url base-url)))))

(deftest run-command-reports-small-sample-results-and-skips
  (with-temp-dir
    (fn [root]
      (let [anchor (write-html! root "anchor.html" "anchor topic")
            near (write-html! root "near.html" "related topic")
            other (write-html! root "other.html" "other topic")
            empty-file (write-html! root "empty.html" "   ")
            options {:anchor (.getPath anchor)
                     :dir (.getPath root)
                     :model "demo"
                     :recursive true
                     :include-hidden false
                     :top 5}
            result (run-with-server
                    options
                    (embedding-handler {"anchor topic" [1.0 0.0]
                                        "related topic" (embedding-for-score 0.99)
                                        "other topic" (embedding-for-score 0.0)}))]
        (is (:ok? result))
        (is (= 0 (:exit-code result)))
        (is (.contains (str/join "\n" (:lines result)) "Target verdict: IN_CLUSTER"))
        (is (some #(re-matches #"Processing time: \d+ ms" %) (:lines result)))
        (is (.contains (str/join "\n" (:lines result)) "Supported candidates: 3"))
        (is (.contains (str/join "\n" (:lines result)) "Comparable: 2"))
        (is (.contains (str/join "\n" (:lines result)) "Skipped: 1"))
        (is (.contains (str/join "\n" (:lines result)) "near.html"))
        (is (.contains (str/join "\n" (:lines result)) "other.html"))
        (is (.contains (str/join "\n" (:lines result)) "EMPTY_TEXT\tempty.html"))))))

(deftest run-command-reports-threshold-based-statuses
  (with-temp-dir
    (fn [root]
      (let [anchor (write-html! root "anchor.html" "anchor topic")
            scores {"a.html" 0.95
                    "b.html" 0.94
                    "c.html" 0.93
                    "d.html" 0.90
                    "e.html" 0.85
                    "f.html" 0.70}
            _ (doseq [[name _] scores]
                (write-html! root name name))
            options {:anchor (.getPath anchor)
                     :dir (.getPath root)
                     :model "demo"
                     :recursive true
                     :include-hidden false
                     :top 3}
            result (run-with-server
                    options
                    (embedding-handler
                     (into {"anchor topic" [1.0 0.0]}
                           (map (fn [[name score]]
                                  [name (embedding-for-score score)]))
                           scores)))
            text (str/join "\n" (:lines result))]
        (is (:ok? result))
        (is (.contains text "Target verdict: IN_CLUSTER"))
        (is (.contains text "Clusters: 1"))
        (is (.contains text "d.html"))
        (is (.contains text "e.html"))
        (is (.contains text "f.html"))))))

(deftest run-command-fails-for-empty-anchor
  (with-temp-dir
    (fn [root]
      (let [anchor (write-html! root "anchor.html" "   ")
            result (core/run-command {:anchor (.getPath anchor)
                                      :dir (.getPath root)
                                      :model "demo"
                                      :base-url "http://127.0.0.1:11434"
                                      :recursive true
                                      :include-hidden false
                                      :top 5})]
        (is (false? (:ok? result)))
        (is (= 2 (:exit-code result)))
        (is (.contains (:message result) "Anchor file could not be used: EMPTY_TEXT"))))))

(deftest run-command-fails-when-no-comparable-files-remain
  (with-temp-dir
    (fn [root]
      (let [anchor (write-html! root "anchor.html" "anchor topic")
            _ (write-html! root "empty.html" "   ")
            result (run-with-server
                    {:anchor (.getPath anchor)
                     :dir (.getPath root)
                     :model "demo"
                     :recursive true
                     :include-hidden false
                     :top 5}
                    (embedding-handler {"anchor topic" [1.0 0.0]}))]
        (is (false? (:ok? result)))
        (is (= 2 (:exit-code result)))
        (is (= "No comparable .html, .htm, or .md files were found after filtering and extraction"
               (:message result)))))))

(deftest run-command-supports-markdown-batches
  (with-temp-dir
    (fn [root]
      (let [anchor (write-md! root "anchor.md" "# anchor topic")
            near (write-md! root "near.md" "## related topic")
            other (write-md! root "other.md" "## other topic")
            options {:anchor (.getPath anchor)
                     :dir (.getPath root)
                     :model "demo"
                     :recursive true
                     :include-hidden false
                     :top 5}
            result (run-with-server
                    options
                    (embedding-handler {"anchor topic" [1.0 0.0]
                                        "related topic" (embedding-for-score 0.97)
                                        "other topic" (embedding-for-score 0.10)}))
            text (str/join "\n" (:lines result))]
        (is (:ok? result))
        (is (.contains text "Target verdict: IN_CLUSTER"))
        (is (.contains text "near.md"))
        (is (.contains text "other.md"))))))

(deftest run-command-fails-for-http-and-malformed-ollama-responses
  (with-temp-dir
    (fn [root]
      (let [anchor (write-html! root "anchor.html" "anchor topic")
            candidate (write-html! root "candidate.html" "candidate topic")
            options {:anchor (.getPath anchor)
                     :dir (.getPath root)
                     :model "demo"
                     :recursive true
                     :include-hidden false
                     :top 5}]
        (testing "HTTP failures propagate as runtime errors"
          (let [result (run-with-server
                        options
                        (fn [exchange]
                          (test-support/write-json! exchange 500 {:error "boom"})))]
            (is (false? (:ok? result)))
            (is (= 3 (:exit-code result)))
            (is (.contains (:message result) "http-error"))))
        (testing "malformed embedding responses propagate as runtime errors"
          (let [result (run-with-server
                        options
                        (fn [exchange]
                          (test-support/write-json! exchange 200 {:embeddings []})))]
            (is (false? (:ok? result)))
            (is (= 3 (:exit-code result)))
            (is (.contains (:message result) "missing-embedding"))))))))

(deftest run-command-fails-when-model-is-missing
  (with-temp-dir
    (fn [root]
      (let [anchor (write-html! root "anchor.html" "anchor topic")
            candidate (write-html! root "candidate.html" "candidate topic")]
        (test-support/with-http-server
          {:embed (embedding-handler {"anchor topic" [1.0 0.0]
                                      "candidate topic" (embedding-for-score 0.9)})
           :tags (tags-handler ["qwen2.5:7b"])}
          (fn [{:keys [base-url]}]
            (let [result (core/run-command {:anchor (.getPath anchor)
                                            :dir (.getPath root)
                                            :model "nomic-embed-text"
                                            :base-url base-url
                                            :recursive true
                                            :include-hidden false
                                            :top 5})]
              (is (false? (:ok? result)))
              (is (= 3 (:exit-code result)))
              (is (.contains (:message result) "nomic-embed-text"))
              (is (.contains (:message result) "ollama pull nomic-embed-text")))))))))

(deftest run-command-chunks-documents-with-overlap-before-embedding
  (with-temp-dir
    (fn [root]
      (let [anchor (write-md! root "anchor.md" "abcdefghij")
            near (write-md! root "near.md" "abcdwxyzij")
            seen-inputs (atom [])
            embeddings {"abcdefghij" [1.0 0.0]
                        "abcd" [1.0 0.0]
                        "cdef" [1.0 0.0]
                        "efgh" [0.0 1.0]
                        "ghij" [0.0 1.0]
                        "ij" [1.0 0.0]
                        "abcdwxyzij" [0.0 1.0]
                        "cdwx" [0.0 1.0]
                        "wxyz" [0.0 1.0]
                        "yzij" [1.0 0.0]}
            options {:anchor (.getPath anchor)
                     :dir (.getPath root)
                     :model "demo"
                     :recursive true
                     :include-hidden false
                     :top 5
                     :chunk-size 4
                     :chunk-overlap 2}
            result (run-with-server
                    options
                    (fn [exchange]
                      (let [payload (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
                            input (:input payload)]
                        (swap! seen-inputs conj input)
                        (if-let [embedding (get embeddings input)]
                          (test-support/write-json! exchange 200 {:embeddings [embedding]})
                          (test-support/write-json! exchange 404 {:error (str "no embedding for input: " input)})))))
            text (str/join "\n" (:lines result))]
        (is (:ok? result))
        (is (= ["abcd" "cdef" "efgh" "ghij" "ij"
                "abcd" "cdwx" "wxyz" "yzij" "ij"]
               @seen-inputs))
        (is (.contains text "Target verdict: IN_CLUSTER"))
        (is (.contains text "near.md"))))))
