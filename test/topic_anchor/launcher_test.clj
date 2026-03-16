(ns topic-anchor.launcher-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.launcher :as launcher]))

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "topic-anchor-launcher-test"
             (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile dir)]
    (try
      (f file)
      (finally
        (doseq [candidate (reverse (file-seq file))]
          (.delete candidate))))))

(deftest canonicalize-directory-normalizes-existing-folders
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")]
        (.mkdir nested)
        (is (= (.getCanonicalPath nested)
               (launcher/canonicalize-directory (str (io/file nested ".")))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Directory does not exist"
                              (launcher/canonicalize-directory (str (io/file root "missing")))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Path is not a directory"
                              (do
                                (spit (io/file root "note.md") "# note")
                                (launcher/canonicalize-directory (str (io/file root "note.md"))))))))))

(deftest parse-launch-args-supports-recursive-flag-and-one-directory
  (is (= {:dir nil :recursive false}
         (launcher/parse-launch-args [])))
  (is (= {:dir "./out" :recursive false}
         (launcher/parse-launch-args ["./out"])))
  (is (= {:dir "./out" :recursive true}
         (launcher/parse-launch-args ["--recursive" "./out"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown launcher option"
                        (launcher/parse-launch-args ["--wat"]))))

(deftest selectable-targets-defaults-to-current-directory-only
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")
            hidden (io/file root ".hidden")]
        (.mkdir nested)
        (.mkdir hidden)
        (spit (io/file root "alpha.md") "# alpha")
        (spit (io/file nested "beta.html") "<html><body>beta</body></html>")
        (spit (io/file hidden "secret.md") "# hidden")
        (spit (io/file root "skip.txt") "skip")
        (is (= ["alpha.md"]
               (mapv :relative-path (launcher/selectable-targets (.getPath root)))))))))

(deftest selectable-targets-recurses-when-explicitly-enabled
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")
            hidden (io/file root ".hidden")]
        (.mkdir nested)
        (.mkdir hidden)
        (spit (io/file root "alpha.md") "# alpha")
        (spit (io/file nested "beta.html") "<html><body>beta</body></html>")
        (spit (io/file hidden "secret.md") "# hidden")
        (is (= ["alpha.md" "nested/beta.html"]
               (mapv :relative-path (launcher/selectable-targets (.getPath root) true))))))))

(deftest selection->entry-supports-index-relative-name-and-absolute-path
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")
            alpha (io/file root "alpha.md")
            beta (io/file nested "beta.md")]
        (.mkdir nested)
        (spit alpha "# alpha")
        (spit beta "# beta")
        (let [entries (launcher/selectable-targets (.getPath root) true)]
          (testing "numeric selection"
            (is (= "alpha.md"
                   (:relative-path (launcher/selection->entry entries "1")))))
          (testing "listed relative path"
            (is (= (.getCanonicalPath beta)
                   (:path (launcher/selection->entry entries "nested/beta.md")))))
          (testing "basename selection"
            (is (= (.getCanonicalPath alpha)
                   (:path (launcher/selection->entry entries "alpha.md")))))
          (testing "absolute path selection"
            (is (= "nested/beta.md"
                   (:relative-path (launcher/selection->entry entries (.getCanonicalPath beta)))))))))))

(deftest selection->entry-rejects-ambiguous-or-missing-targets
  (let [entries [{:path "/tmp/root/a/dup.md" :relative-path "a/dup.md" :file-name "dup.md"}
                 {:path "/tmp/root/b/dup.md" :relative-path "b/dup.md" :file-name "dup.md"}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"ambiguous"
                          (launcher/selection->entry entries "dup.md")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not found"
                          (launcher/selection->entry entries "missing.md")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"out of range"
                          (launcher/selection->entry entries "3")))))

(deftest command-args-include-resolved-anchor-and-dir
  (is (= ["clojure"
          "-M"
          "-m"
          "topic-anchor.core"
          "--anchor"
          "/tmp/root/alpha.md"
          "--dir"
          "/tmp/root"
          "--model"
          "nomic-embed-text"
          "--top"
          "5"
          "--chunk-size"
          "1800"
          "--chunk-overlap"
          "200"]
         (launcher/command-args "/tmp/root" "/tmp/root/alpha.md" false))))

(deftest command-args-include-recursive-flag-when-enabled
  (is (= ["clojure"
          "-M"
          "-m"
          "topic-anchor.core"
          "--anchor"
          "/tmp/root/alpha.md"
          "--dir"
          "/tmp/root"
          "--recursive"
          "true"
          "--model"
          "nomic-embed-text"
          "--top"
          "5"
          "--chunk-size"
          "1800"
          "--chunk-overlap"
          "200"]
         (launcher/command-args "/tmp/root" "/tmp/root/alpha.md" true))))
