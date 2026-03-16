(ns topic-anchor.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.core :as core]))

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
      (let [anchor (io/file root "anchor.html")]
        (spit anchor "<html><body>anchor</body></html>")
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
