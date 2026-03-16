(ns topic-anchor.markdown
  (:require [clojure.java.io :as io]
            [topic-anchor.html :as html])
  (:import (org.commonmark.parser Parser)
           (org.commonmark.renderer.text TextContentRenderer)))

(def ^:private parser
  (-> (Parser/builder)
      .build))

(def ^:private renderer
  (-> (TextContentRenderer/builder)
      .build))

(defn extract-text [path]
  (try
    (let [markdown (slurp (io/file path))
          text (->> markdown
                    (.parse parser)
                    (.render renderer)
                    html/normalize-text)]
      (if (clojure.string/blank? text)
        {:ok? false :reason :empty-text}
        {:ok? true :text text}))
    (catch Exception ex
      {:ok? false
       :reason :read-error
       :message (.getMessage ex)})))
