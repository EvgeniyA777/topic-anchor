(ns topic-anchor.install
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [topic-anchor.fs :as fs]
            [topic-anchor.wrapper :as wrapper]))

(def command-name "topic-anchor")

(defn trim-to-nil [value]
  (wrapper/trim-to-nil value))

(defn home-dir [env os]
  (or (trim-to-nil (get env "HOME"))
      (when (= :windows os)
        (trim-to-nil (get env "USERPROFILE")))))

(defn user-bin-dir
  ([]
   (user-bin-dir {:os (wrapper/os-family)
                  :env {"HOME" (System/getenv "HOME")
                        "USERPROFILE" (System/getenv "USERPROFILE")
                        "XDG_BIN_HOME" (System/getenv "XDG_BIN_HOME")
                        "TOPIC_ANCHOR_BIN_DIR" (System/getenv "TOPIC_ANCHOR_BIN_DIR")}}))
  ([{:keys [os env]}]
   (or (trim-to-nil (get env "TOPIC_ANCHOR_BIN_DIR"))
       (case os
         :windows
         (let [home (home-dir env os)]
           (when-not home
             (throw (ex-info "Unable to determine user home directory for Windows install"
                             {:kind :input :os os})))
           (str (io/file home ".local" "bin")))

         (:macos :linux :other)
         (or (trim-to-nil (get env "XDG_BIN_HOME"))
             (let [home (home-dir env os)]
               (when-not home
                 (throw (ex-info "Unable to determine HOME for install"
                                 {:kind :input :os os})))
               (str (io/file home ".local" "bin"))))))))

(defn installed-command-name [os]
  (if (= :windows os)
    (str command-name ".bat")
    command-name))

(defn installed-command-path [install-dir os]
  (str (io/file install-dir (installed-command-name os))))

(defn path-entries [path-value]
  (->> (str/split (or path-value "")
                  (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))
       (map trim-to-nil)
       (remove nil?)))

(defn normalized-path [path]
  (str (fs/normalize-path path)))

(defn absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn path-contains-dir? [path-value dir]
  (let [target (normalized-path dir)]
    (boolean
     (some #(= target (normalized-path %))
           (path-entries path-value)))))

(defn command-candidate-names [os env]
  (if (= :windows os)
    (let [pathext (or (trim-to-nil (get env "PATHEXT"))
                      ".COM;.EXE;.BAT;.CMD")]
      (->> (str/split pathext #";")
           (map str/lower-case)
           (cons "")
           distinct
           (map #(str command-name %))))
    [command-name]))

(defn find-command-on-path [command os env]
  (let [path-value (get env "PATH")]
    (some (fn [dir]
            (some (fn [candidate]
                    (let [path (io/file dir candidate)]
                      (when (.isFile path)
                        (absolute-path path))))
                  (if (= command-name command)
                    (command-candidate-names os env)
                    [command])))
          (path-entries path-value))))

(defn shell-config-file [env]
  (let [shell (or (trim-to-nil (get env "SHELL")) "")]
    (cond
      (str/includes? shell "zsh") "~/.zshrc"
      (str/includes? shell "bash") "~/.bashrc"
      :else "~/.profile")))

(defn path-instructions [install-dir os env]
  (case os
    :windows
    [(str "Add the install directory to your user PATH, then restart the shell:")
     (format "[Environment]::SetEnvironmentVariable(\"Path\", $env:Path + \";%s\", \"User\")"
             install-dir)]

    [(str "Add the install directory to PATH, then reload your shell:")
     (format "echo 'export PATH=\"%s:$PATH\"' >> %s"
             install-dir
             (shell-config-file env))]))

(defn repo-home
  ([]
   (repo-home (System/getProperty "user.dir")))
  ([cwd]
   (let [canonical (wrapper/canonical-directory cwd)]
     (when-not (wrapper/topic-anchor-home? canonical)
       (throw (ex-info
               (str "Run bb install-user from the topic-anchor repo root: " canonical)
               {:kind :input :cwd canonical})))
     canonical)))

(defn create-directory! [dir]
  (.mkdirs (io/file dir))
  dir)

(defn delete-existing-file! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (java.nio.file.Files/delete (.toPath file)))))

(defn unix-wrapper-script [repo-home]
  (str "#!/usr/bin/env bb\n\n"
       "(require '[babashka.classpath :as cp]\n"
       "         '[clojure.java.io :as io])\n\n"
       "(let [repo-home " (pr-str repo-home) "\n"
       "      _ (cp/add-classpath (str (io/file repo-home \"src\")))]\n"
       "  (require 'topic-anchor.wrapper)\n"
       "  (try\n"
       "    (topic-anchor.wrapper/run-wrapper! *command-line-args* {:fallback-home repo-home})\n"
       "    (System/exit 0)\n"
       "    (catch clojure.lang.ExceptionInfo ex\n"
       "      (binding [*out* *err*]\n"
       "        (println (.getMessage ex)))\n"
       "      (System/exit (if (= :runtime (:kind (ex-data ex))) 1 2)))))\n"))

(defn windows-wrapper-script [repo-home]
  (let [escaped-home (str/replace repo-home "/" "\\")]
    (str "@echo off\r\n"
         "setlocal\r\n"
         "if \"%TOPIC_ANCHOR_HOME%\"==\"\" set \"TOPIC_ANCHOR_HOME=" escaped-home "\"\r\n"
         "if \"%TOPIC_ANCHOR_BB_CMD%\"==\"\" (\r\n"
         "  set \"TOPIC_ANCHOR_BB_CMD=bb\"\r\n"
         ")\r\n"
         "\"%TOPIC_ANCHOR_BB_CMD%\" \"%TOPIC_ANCHOR_HOME%\\bin\\topic-anchor\" %*\r\n"
         "exit /b %ERRORLEVEL%\r\n")))

(defn write-executable-script! [path content]
  (spit path content)
  (.setExecutable (io/file path) true false)
  path)

(defn install-unix-command! [repo-home install-path]
  (let [source-path (.toPath (io/file repo-home "bin" command-name))
        target-path (.toPath (io/file install-path))]
    (delete-existing-file! install-path)
    (try
      (java.nio.file.Files/createSymbolicLink target-path source-path (make-array java.nio.file.attribute.FileAttribute 0))
      {:install-mode :symlink
       :install-path install-path}
      (catch Exception _
        (write-executable-script! install-path (unix-wrapper-script repo-home))
        {:install-mode :copy
         :install-path install-path}))))

(defn install-windows-command! [repo-home install-path]
  (delete-existing-file! install-path)
  (spit install-path (windows-wrapper-script repo-home))
  {:install-mode :shim
   :install-path install-path})

(defn ensure-no-command-conflict! [install-path os env]
  (when-let [existing (find-command-on-path command-name os env)]
    (let [target (normalized-path install-path)
          found (normalized-path existing)]
      (when-not (= target found)
        (throw (ex-info
                (str "A different '" command-name "' command already exists in PATH: " existing)
                {:kind :input
                 :existing-command existing
                 :install-path install-path}))))))

(defn install-command! [repo-home install-dir os]
  (let [install-path (installed-command-path install-dir os)]
    (create-directory! install-dir)
    (if (= :windows os)
      (install-windows-command! repo-home install-path)
      (install-unix-command! repo-home install-path))))

(defn install-result-lines [{:keys [repo-home install-dir install-path install-mode path-configured? env os]}]
  (vec
   (concat
    [(str "Installed command: " install-path)
     (str "Install mode: " (name install-mode))
     (str "App home: " repo-home)]
    (if path-configured?
      [(str "PATH already includes: " install-dir)]
      (path-instructions install-dir os env)))))

(defn install-user!
  ([]
   (install-user! {}))
  ([{:keys [cwd os env]
     :or {cwd (System/getProperty "user.dir")
          os (wrapper/os-family)
          env {"HOME" (System/getenv "HOME")
               "USERPROFILE" (System/getenv "USERPROFILE")
               "XDG_BIN_HOME" (System/getenv "XDG_BIN_HOME")
               "TOPIC_ANCHOR_BIN_DIR" (System/getenv "TOPIC_ANCHOR_BIN_DIR")
               "PATH" (System/getenv "PATH")
               "PATHEXT" (System/getenv "PATHEXT")
               "SHELL" (System/getenv "SHELL")}}}]
   (let [repo-home (repo-home cwd)
         install-dir (user-bin-dir {:os os :env env})
         install-path (installed-command-path install-dir os)
         _ (ensure-no-command-conflict! install-path os env)
         {:keys [install-mode]} (install-command! repo-home install-dir os)
         result {:repo-home repo-home
                 :install-dir install-dir
                 :install-path install-path
                 :install-mode install-mode
                 :path-configured? (path-contains-dir? (get env "PATH") install-dir)
                 :env env
                 :os os}]
     (doseq [line (install-result-lines result)]
       (println line))
     result)))
