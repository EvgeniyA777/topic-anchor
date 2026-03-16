(ns topic-anchor.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.core :as core]
            [topic-anchor.test-support :as test-support]))

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "topic-anchor-core-test"
             (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile dir)]
    (try
      (f file)
      (finally
        (doseq [candidate (reverse (file-seq file))]
          (.delete candidate))))))

(deftest validate-cli-accepts-existing-inputs
  (with-temp-dir
    (fn [root]
      (let [anchor (io/file root "anchor.md")]
        (spit anchor "# anchor")
        (let [{:keys [ok? options]} (core/validate-cli {:options {:anchor (.getPath anchor)
                                                                   :dir (.getPath root)
                                                                   :model "nomic-embed-text"}
                                                        :errors nil})]
          (is ok?)
          (is (= (.getPath anchor) (:anchor options)))
          (is (= "nomic-embed-text" (:model options))))))))

(deftest validate-cli-rejects-missing-required-inputs
  (let [{:keys [ok? message]} (core/validate-cli {:options {}
                                                  :errors nil})]
    (is (false? ok?))
    (is (.contains message "--anchor"))
    (is (.contains message "--dir"))
    (is (.contains message "--model"))))

(deftest usage-text-documents-command-shape
  (let [text (core/usage "summary")]
    (is (.contains text "topic-anchor"))
    (is (.contains text "--anchor ./good.html"))
    (is (.contains text "summary"))))

(deftest model-installed-accepts-latest-tag-for-untagged-request
  (is (true? (core/model-installed? "nomic-embed-text" "nomic-embed-text:latest")))
  (is (true? (core/model-installed? "qwen2.5:7b" "qwen2.5:7b")))
  (is (false? (core/model-installed? "nomic-embed-text" "mistral:7b"))))

(deftest run-command-renders-review-and-skipped-sections-for-small-batches
  (with-temp-dir
    (fn [root]
      (let [anchor (io/file root "anchor.html")
            good (io/file root "good.html")
            weak (io/file root "weak.html")
            empty (io/file root "empty.html")]
        (spit anchor "<html><body>anchor</body></html>")
        (spit good "<html><body>good</body></html>")
        (spit weak "<html><body>weak</body></html>")
        (spit empty "<html><body>   </body></html>")
        (test-support/with-http-server
          {:embed (fn [exchange]
                    (let [body (slurp (.getRequestBody exchange))]
                      (cond
                        (.contains body "\"anchor\"") (test-support/write-json! exchange 200 {:embeddings [[1.0 0.0]]})
                        (.contains body "\"good\"") (test-support/write-json! exchange 200 {:embeddings [[0.98 0.2]]})
                        :else (test-support/write-json! exchange 200 {:embeddings [[0.60 0.8]]})))
                    )
           :tags (fn [exchange]
                   (test-support/write-json! exchange 200 {:models [{:name "demo-model"}]}))}
          (fn [{:keys [base-url]}]
            (let [result (core/run-command {:anchor (.getPath anchor)
                                            :dir (.getPath root)
                                            :model "demo-model"
                                            :base-url base-url
                                            :recursive true
                                            :include-hidden false
                                            :top 2})]
              (is (:ok? result))
              (is (some #{"Highlights"} (:lines result)))
              (is (some #{"REVIEW\t0.6000\tweak.html"} (:lines result)))
              (is (some #{"-\t0.9798\tgood.html"} (:lines result)))
              (is (some #{"Skipped"} (:lines result)))
              (is (some #{"EMPTY_TEXT\tempty.html"} (:lines result))))))))))

(deftest run-command-supports-markdown-anchor-and-candidates
  (with-temp-dir
    (fn [root]
      (let [anchor (io/file root "anchor.md")
            near (io/file root "near.md")
            far (io/file root "far.md")]
        (spit anchor "# Anchor topic")
        (spit near "## Related topic")
        (spit far "## Unrelated topic")
        (test-support/with-http-server
          {:embed (fn [exchange]
                    (let [body (slurp (.getRequestBody exchange))]
                      (cond
                        (.contains body "\"Anchor topic\"") (test-support/write-json! exchange 200 {:embeddings [[1.0 0.0]]})
                        (.contains body "\"Related topic\"") (test-support/write-json! exchange 200 {:embeddings [[0.95 0.31]]})
                        :else (test-support/write-json! exchange 200 {:embeddings [[0.2 0.98]]}))))
           :tags (fn [exchange]
                   (test-support/write-json! exchange 200 {:models [{:name "demo-model"}]}))}
          (fn [{:keys [base-url]}]
            (let [result (core/run-command {:anchor (.getPath anchor)
                                            :dir (.getPath root)
                                            :model "demo-model"
                                            :base-url base-url
                                            :recursive true
                                            :include-hidden false
                                            :top 2})]
              (is (:ok? result))
              (is (some #{"REVIEW\t0.2000\tfar.md"} (:lines result)))
              (is (some #{"-\t0.9507\tnear.md"} (:lines result))))))))))

(deftest run-command-classifies-large-batches
  (with-temp-dir
    (fn [root]
      (let [anchor (io/file root "anchor.html")
            candidates [["ok-1.html" "ok-1"]
                        ["ok-2.html" "ok-2"]
                        ["ok-3.html" "ok-3"]
                        ["ok-4.html" "ok-4"]
                        ["suspect.html" "suspect"]
                        ["outlier.html" "outlier"]]
            embeddings {"anchor" [1.0 0.0]
                        "ok-1" [0.99 0.1410673598]
                        "ok-2" [0.98 0.1989974874]
                        "ok-3" [0.97 0.2431049156]
                        "ok-4" [0.95 0.3122498999]
                        "suspect" [0.90 0.4358898944]
                        "outlier" [0.70 0.7141428429]}]
        (spit anchor "<html><body>anchor</body></html>")
        (doseq [[filename token] candidates]
          (spit (io/file root filename) (str "<html><body>" token "</body></html>")))
        (test-support/with-http-server
          {:embed (fn [exchange]
                    (let [body (slurp (.getRequestBody exchange))
                          token (some (fn [[name _]]
                                        (when (.contains body (str "\"" name "\""))
                                          name))
                                      embeddings)
                          [x y] (get embeddings token)]
                      (test-support/write-json! exchange 200 {:embeddings [[x y]]})))
           :tags (fn [exchange]
                   (test-support/write-json! exchange 200 {:models [{:name "demo-model"}]}))}
          (fn [{:keys [base-url]}]
            (let [result (core/run-command {:anchor (.getPath anchor)
                                            :dir (.getPath root)
                                            :model "demo-model"
                                            :base-url base-url
                                            :recursive true
                                            :include-hidden false
                                            :top 3})]
              (is (:ok? result))
              (is (some #{"OUTLIER\t0.7000\toutlier.html"} (:lines result)))
              (is (some #{"SUSPECT\t0.9000\tsuspect.html"} (:lines result)))
              (is (some #{"OK\t0.9500\tok-4.html"} (:lines result))))))))))
