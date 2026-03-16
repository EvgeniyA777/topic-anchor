(ns topic-anchor.install-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.install :as install]))

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "topic-anchor-install-test"
             (make-array java.nio.file.attribute.FileAttribute 0))
        file (.toFile dir)]
    (try
      (f file)
      (finally
        (doseq [candidate (reverse (file-seq file))]
          (.delete candidate))))))

(deftest user-bin-dir-prefers-explicit-override
  (is (= "/custom/bin"
         (install/user-bin-dir {:os :linux
                                :env {"TOPIC_ANCHOR_BIN_DIR" "/custom/bin"
                                      "HOME" "/home/demo"}}))))

(deftest user-bin-dir-falls-back-by-platform
  (testing "unix uses XDG_BIN_HOME then ~/.local/bin"
    (is (= "/xdg/bin"
           (install/user-bin-dir {:os :linux
                                  :env {"XDG_BIN_HOME" "/xdg/bin"
                                        "HOME" "/home/demo"}})))
    (is (= "/home/demo/.local/bin"
           (install/user-bin-dir {:os :macos
                                  :env {"HOME" "/home/demo"}}))))
  (testing "windows uses USERPROFILE"
    (is (= "C:\\Users\\demo/.local/bin"
           (install/user-bin-dir {:os :windows
                                  :env {"USERPROFILE" "C:\\Users\\demo"}})))))

(deftest installed-command-path-uses-platform-specific-name
  (is (= "/tmp/bin/topic-anchor"
         (install/installed-command-path "/tmp/bin" :linux)))
  (is (= "C:\\bin/topic-anchor.bat"
         (install/installed-command-path "C:\\bin" :windows))))

(deftest path-contains-dir-handles-platform-appropriate-paths
  (is (true? (install/path-contains-dir? "/usr/bin:/tmp/bin" "/tmp/bin")))
  (is (false? (install/path-contains-dir? "/usr/bin:/tmp/bin" "/opt/bin"))))

(deftest find-command-on-path-locates-existing-command
  (with-temp-dir
    (fn [root]
      (let [bin (io/file root "bin")
            cmd (io/file bin "topic-anchor")]
        (.mkdir bin)
        (spit cmd "#!/bin/sh\n")
        (.setExecutable cmd true false)
        (is (= (.getCanonicalPath cmd)
               (install/find-command-on-path "topic-anchor"
                                             :linux
                                             {"PATH" (.getCanonicalPath bin)})))))))

(deftest install-result-lines-print-path-guidance-when-needed
  (let [lines (install/install-result-lines {:repo-home "/repo"
                                             :install-dir "/home/demo/.local/bin"
                                             :install-path "/home/demo/.local/bin/topic-anchor"
                                             :install-mode :symlink
                                             :path-configured? false
                                             :env {"SHELL" "/bin/zsh"}
                                             :os :linux})]
    (is (= "Installed command: /home/demo/.local/bin/topic-anchor" (first lines)))
    (is (some #(.contains % ".zshrc") lines))))

(deftest ensure-no-command-conflict-rejects-foreign-command
  (with-temp-dir
    (fn [root]
      (let [other-bin (io/file root "other-bin")
            cmd (io/file other-bin "topic-anchor")]
        (.mkdir other-bin)
        (spit cmd "#!/bin/sh\n")
        (.setExecutable cmd true false)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"already exists in PATH"
                              (install/ensure-no-command-conflict!
                               (str (io/file root "install-bin" "topic-anchor"))
                               :linux
                               {"PATH" (.getCanonicalPath other-bin)})))))))

(deftest install-user-installs-command-into-explicit-bin-dir
  (with-temp-dir
    (fn [root]
      (let [repo (io/file root "topic-anchor")
            src (io/file repo "src")
            bin (io/file repo "bin")
            repo-script (io/file bin "topic-anchor")
            install-dir (io/file root "user-bin")
            installed (io/file install-dir "topic-anchor")]
        (.mkdir repo)
        (.mkdir src)
        (.mkdir bin)
        (spit (io/file repo "bb.edn") "{}")
        (spit repo-script "#!/usr/bin/env bb\n")
        (.setExecutable repo-script true false)
        (let [result (install/install-user! {:cwd (.getCanonicalPath repo)
                                             :os :linux
                                             :env {"HOME" (.getCanonicalPath root)
                                                   "TOPIC_ANCHOR_BIN_DIR" (.getCanonicalPath install-dir)
                                                   "PATH" ""}})]
          (is (= (.getCanonicalPath repo) (:repo-home result)))
          (is (.exists installed))
          (is (= (str (io/file (.getCanonicalPath install-dir) "topic-anchor"))
                 (:install-path result))))))))
