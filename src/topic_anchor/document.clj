(ns topic-anchor.document
  (:require [topic-anchor.fs :as fs]
            [topic-anchor.html :as html]
            [topic-anchor.markdown :as markdown]))

(defn extract-text [path]
  (case (fs/path-extension path)
    ".html" (html/extract-text path)
    ".htm" (html/extract-text path)
    ".md" (markdown/extract-text path)
    {:ok? false :reason :unsupported-type}))
