(ns topic-anchor.markdown-test
  (:require [clojure.test :refer [deftest is testing]]
            [topic-anchor.markdown :as markdown]))

(defn with-temp-file [suffix content f]
  (let [path (java.nio.file.Files/createTempFile
              "topic-anchor-markdown-test"
              suffix
              (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile path)]
    (try
      (spit file content)
      (f file)
      (finally
        (.delete file)))))

(deftest extract-text-reads-markdown-content
  (with-temp-file
    ".md"
    "# Hello\n\nThis is **topic-anchor**."
    (fn [file]
      (is (= {:ok? true :text "Hello This is topic-anchor."}
             (markdown/extract-text file))))))

(deftest extract-text-reports-empty-markdown
  (testing "markdown with only formatting markers is not comparable"
    (with-temp-file
      ".md"
      "   \n\n"
      (fn [file]
        (is (= {:ok? false :reason :empty-text}
               (markdown/extract-text file)))))))
