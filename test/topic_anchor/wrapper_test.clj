(ns topic-anchor.wrapper-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.wrapper :as wrapper]))

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "topic-anchor-wrapper-test"
             (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile dir)]
    (try
      (f file)
      (finally
        (doseq [candidate (reverse (file-seq file))]
          (.delete candidate))))))

(deftest topic-anchor-home-detection-requires-bb-and-src
  (with-temp-dir
    (fn [root]
      (let [repo (io/file root "topic-anchor")
            src (io/file repo "src")]
        (.mkdir repo)
        (.mkdir src)
        (spit (io/file repo "bb.edn") "{}")
        (is (true? (wrapper/topic-anchor-home? (.getPath repo))))
        (is (false? (wrapper/topic-anchor-home? (.getPath root))))))))

(deftest repo-home-from-script-resolves-parent-of-bin
  (with-temp-dir
    (fn [root]
      (let [repo (io/file root "topic-anchor")
            bin (io/file repo "bin")
            script (io/file bin "topic-anchor-here")]
        (.mkdir repo)
        (.mkdir bin)
        (spit script "#!/usr/bin/env bb")
        (is (= (.getCanonicalPath repo)
               (wrapper/repo-home-from-script (.getPath script))))))))

(deftest resolve-app-home-prefers-env-then-script
  (with-temp-dir
    (fn [root]
      (let [env-home (io/file root "env-home")
            script-home (io/file root "script-home")
            fallback-home (io/file root "fallback-home")
            script-bin (io/file script-home "bin")
            script (io/file script-bin "topic-anchor-here")]
        (doseq [home [env-home script-home fallback-home]]
          (.mkdir home)
          (.mkdir (io/file home "src"))
          (spit (io/file home "bb.edn") "{}"))
        (.mkdir script-bin)
        (spit script "#!/usr/bin/env bb")
        (testing "env wins"
          (is (= (.getCanonicalPath env-home)
                 (wrapper/resolve-app-home {:env-home (.getPath env-home)
                                            :script-path (.getPath script)
                                            :fallback-home (.getPath fallback-home)}))))
        (testing "script-derived home is used when env is absent"
          (is (= (.getCanonicalPath script-home)
                 (wrapper/resolve-app-home {:env-home nil
                                            :script-path (.getPath script)
                                            :fallback-home (.getPath fallback-home)}))))))))

(deftest resolve-target-directory-defaults-to-cwd
  (with-temp-dir
    (fn [root]
      (let [nested (io/file root "nested")]
        (.mkdir nested)
        (is (= (.getCanonicalPath nested)
               (wrapper/resolve-target-directory [] (.getPath nested))))
        (is (= (.getCanonicalPath nested)
               (wrapper/resolve-target-directory [(str (io/file nested "."))]
                                                 (.getPath root))))))))

(deftest build-launch-request-uses-canonical-paths-and-bb-command
  (with-temp-dir
    (fn [root]
      (let [repo (io/file root "topic-anchor")
            src (io/file repo "src")
            cwd (io/file root "caller")
            bin (io/file repo "bin")
            script (io/file bin "topic-anchor-here")]
        (.mkdir repo)
        (.mkdir src)
        (.mkdir cwd)
        (.mkdir bin)
        (spit (io/file repo "bb.edn") "{}")
        (spit script "#!/usr/bin/env bb")
        (with-redefs [wrapper/bb-command (constantly "bb-test")]
          (is (= {:os (wrapper/os-family)
                  :app-home (.getCanonicalPath repo)
                  :target-dir (.getCanonicalPath cwd)
                  :command ["bb-test" "semantic-compare" (.getCanonicalPath cwd)]}
                 (wrapper/build-launch-request []
                                               {:cwd (.getPath cwd)
                                                :script-path (.getPath script)}))))))))

(deftest resolve-app-home-errors-when-no-valid-home-found
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unable to locate topic-anchor home"
                        (wrapper/resolve-app-home {:env-home nil
                                                   :script-path nil
                                                   :fallback-home nil}))))
