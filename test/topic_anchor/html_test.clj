(ns topic-anchor.html-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.html :as html]))

(defn with-temp-file [content f]
  (let [path (java.nio.file.Files/createTempFile
              "topic-anchor-html-test"
              ".html"
              (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile path)]
    (try
      (spit file content)
      (f file)
      (finally
        (.delete file)))))

(deftest normalize-text-collapses-whitespace
  (is (= "a b c" (html/normalize-text " a \n b\t c "))))

(deftest extract-text-reads-meaningful-html
  (with-temp-file
    "<html><body><h1>Hello</h1><p>world</p></body></html>"
    (fn [file]
      (is (= {:ok? true :text "Hello world"}
             (html/extract-text file))))))

(deftest extract-text-reports-empty-text
  (testing "markup-only documents are treated as not comparable"
    (with-temp-file
      "<html><body>   </body></html>"
      (fn [file]
        (is (= {:ok? false :reason :empty-text}
               (html/extract-text file)))))))
