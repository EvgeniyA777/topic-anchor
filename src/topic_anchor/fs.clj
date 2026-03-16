(ns topic-anchor.fs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def supported-extensions #{".html" ".htm" ".md"})

(defn normalize-path [path]
  (.normalize (.toAbsolutePath (.toPath (io/file path)))))

(defn canonical-path [path]
  (.toPath (.getCanonicalFile (io/file path))))

(defn relative-display-path [root path]
  (str (.normalize (.relativize (normalize-path root) (normalize-path path)))))

(defn path-extension [path]
  (let [name (str/lower-case (str (.getFileName (normalize-path path))))
        idx (.lastIndexOf name ".")]
    (when (pos? idx)
      (subs name idx))))

(defn supported-path? [path]
  (contains? supported-extensions (path-extension path)))

(defn hidden-relative-path? [root path]
  (let [relative (.relativize (normalize-path root) (normalize-path path))]
    (boolean
     (some #(str/starts-with? (str %) ".")
           relative))))

(defn regular-file? [path]
  (.isFile (io/file path)))

(defn candidate-paths [root {:keys [recursive include-hidden anchor]}]
  (let [root-file (io/file root)
        anchor-path (some-> anchor normalize-path)
        walk (if recursive file-seq #(cons % (.listFiles %)))]
    (->> (walk root-file)
         (filter regular-file?)
         (remove #(and anchor-path (= anchor-path (normalize-path %))))
         (filter supported-path?)
         (remove #(and (not include-hidden)
                       (hidden-relative-path? root-file %)))
         (sort-by #(relative-display-path root-file %))
         vec)))
