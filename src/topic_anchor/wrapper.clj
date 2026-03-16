(ns topic-anchor.wrapper
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [topic-anchor.fs :as fs]))

(def default-bb-command "bb")

(defn trim-to-nil [value]
  (when (some? value)
    (let [trimmed (str/trim (str value))]
      (when-not (str/blank? trimmed)
        trimmed))))

(defn os-family []
  (let [name (-> (System/getProperty "os.name" "")
                 str/lower-case)]
    (cond
      (str/includes? name "win") :windows
      (str/includes? name "mac") :macos
      (or (str/includes? name "linux")
          (str/includes? name "nix")
          (str/includes? name "nux")) :linux
      :else :other)))

(defn canonical-directory [path]
  (let [trimmed (trim-to-nil path)]
    (when-not trimmed
      (throw (ex-info "Target directory is required" {:kind :input})))
    (let [candidate (io/file trimmed)
          display-path (str (fs/normalize-path trimmed))]
      (when-not (.exists candidate)
        (throw (ex-info (str "Directory does not exist: " display-path)
                        {:kind :input :path display-path})))
      (when-not (.isDirectory candidate)
        (throw (ex-info (str "Path is not a directory: " display-path)
                        {:kind :input :path display-path})))
      (str (fs/canonical-path candidate)))))

(defn topic-anchor-home? [path]
  (let [dir (some-> path io/file .getCanonicalFile)]
    (boolean
     (and dir
          (.isDirectory dir)
          (.isFile (io/file dir "bb.edn"))
          (.isDirectory (io/file dir "src"))))))

(defn repo-home-from-script [script-path]
  (some-> script-path
          io/file
          .getCanonicalFile
          .toPath
          .getParent
          .getParent
          str))

(defn resolve-app-home
  [{:keys [env-home script-path fallback-home]}]
  (let [candidates (->> [env-home
                         (repo-home-from-script script-path)
                         fallback-home]
                        (map trim-to-nil)
                        (remove nil?))]
    (or (some->> candidates
                 (filter topic-anchor-home?)
                 first
                 canonical-directory)
        (throw (ex-info
                (str "Unable to locate topic-anchor home for "
                     (name (os-family))
                     ". Set TOPIC_ANCHOR_HOME or run the wrapper from the topic-anchor repo.")
                {:kind :input
                 :candidates candidates
                 :os (os-family)})))))

(defn parse-launch-args [args]
  (loop [remaining args
         parsed {:dir nil :recursive false}]
    (if-let [arg (first remaining)]
      (cond
        (= "--recursive" arg)
        (recur (next remaining) (assoc parsed :recursive true))

        (str/starts-with? arg "-")
        (throw (ex-info (str "Unknown wrapper option: " arg)
                        {:kind :input :option arg}))

        (:dir parsed)
        (throw (ex-info (str "Only one folder path is supported, got extra argument: " arg)
                        {:kind :input :argument arg}))

        :else
        (recur (next remaining) (assoc parsed :dir arg)))
      parsed)))

(defn resolve-target-directory [{:keys [dir]} cwd]
  (canonical-directory
   (or dir
       cwd)))

(defn bb-command []
  (or (trim-to-nil (System/getenv "TOPIC_ANCHOR_BB_CMD"))
      default-bb-command))

(defn build-launch-request
  [args {:keys [cwd env-home script-path fallback-home]
         :or {cwd (System/getProperty "user.dir")
              env-home (System/getenv "TOPIC_ANCHOR_HOME")
              script-path (System/getProperty "babashka.file")}}]
  (let [{:keys [recursive] :as launch-options} (parse-launch-args args)
        app-home (resolve-app-home {:env-home env-home
                                    :script-path script-path
                                    :fallback-home fallback-home})
        target-dir (resolve-target-directory launch-options cwd)]
    {:os (os-family)
     :app-home app-home
     :target-dir target-dir
     :command (vec (concat [(bb-command) "semantic-compare"]
                           (when recursive ["--recursive"])
                           [target-dir]))}))

(defn run-wrapper!
  ([args]
   (run-wrapper! args {}))
  ([args options]
   (let [{:keys [app-home command]} (build-launch-request args options)
         process-builder (doto (ProcessBuilder. ^java.util.List command)
                           (.directory (io/file app-home))
                           (.inheritIO))
         process (.start process-builder)
         exit-code (.waitFor process)]
     (when-not (zero? exit-code)
       (throw (ex-info (str "topic-anchor failed with exit code " exit-code)
                       {:kind :runtime :exit-code exit-code})))
     exit-code)))

(defn -main [& args]
  (try
    (run-wrapper! args)
    (System/exit 0)
    (catch clojure.lang.ExceptionInfo ex
      (binding [*out* *err*]
        (println (.getMessage ex)))
      (System/exit (if (= :runtime (:kind (ex-data ex))) 1 2)))))
