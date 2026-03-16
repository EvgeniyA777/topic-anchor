(ns topic-anchor.fs-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.fs :as fs]))

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "topic-anchor-fs-test"
             (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile dir)]
    (try
      (f file)
      (finally
        (doseq [candidate (reverse (file-seq file))]
          (.delete candidate))))))

(deftest candidate-paths-filters-and-sorts
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")
            hidden-dir (io/file root ".hidden")
            anchor (io/file root "anchor.html")
            a-file (io/file root "b.html")
            z-file (io/file nested "a.htm")
            hidden-file (io/file hidden-dir "secret.html")
            txt-file (io/file root "skip.txt")]
        (.mkdir nested)
        (.mkdir hidden-dir)
        (spit anchor "<html><body>anchor</body></html>")
        (spit a-file "<html><body>b</body></html>")
        (spit z-file "<html><body>a</body></html>")
        (spit (io/file root "note.md") "# markdown note")
        (spit hidden-file "<html><body>hidden</body></html>")
        (spit txt-file "ignore")
        (testing "recursive discovery excludes anchor, hidden files, and unsupported files"
          (is (= ["b.html" "nested/a.htm" "note.md"]
                 (mapv #(fs/relative-display-path root %)
                       (fs/candidate-paths root {:recursive true
                                                 :include-hidden false
                                                 :anchor anchor})))))
        (testing "non-recursive discovery stays at the root"
          (is (= ["b.html" "note.md"]
                 (mapv #(fs/relative-display-path root %)
                       (fs/candidate-paths root {:recursive false
                                                 :include-hidden false
                                                 :anchor anchor})))))
        (testing "include-hidden exposes dot-directories"
          (is (= [".hidden/secret.html" "b.html" "nested/a.htm" "note.md"]
                 (mapv #(fs/relative-display-path root %)
                       (fs/candidate-paths root {:recursive true
                                                 :include-hidden true
                                                 :anchor anchor})))))))))

(deftest canonical-path-resolves-dot-segments
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")]
        (.mkdir nested)
        (is (= (.toPath (.getCanonicalFile nested))
               (fs/canonical-path (io/file nested "."))))))))
