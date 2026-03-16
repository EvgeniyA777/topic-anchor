(ns topic-anchor.document
  (:require [clojure.string :as str]
            [topic-anchor.fs :as fs]
            [topic-anchor.html :as html]
            [topic-anchor.markdown :as markdown]))

(defn chunk-text [text chunk-size chunk-overlap]
  (let [step (max 1 (- chunk-size chunk-overlap))
        limit (count text)]
    (->> (loop [start 0
                acc []]
           (if (>= start limit)
             acc
             (let [end (min limit (+ start chunk-size))]
               (recur (+ start step)
                      (conj acc (subs text start end))))))
         (remove str/blank?)
         vec)))

(defn extract-text [path]
  (case (fs/path-extension path)
    ".html" (html/extract-text path)
    ".htm" (html/extract-text path)
    ".md" (markdown/extract-text path)
    {:ok? false :reason :unsupported-type}))
