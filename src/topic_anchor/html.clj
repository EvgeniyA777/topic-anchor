(ns topic-anchor.html
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (org.jsoup Jsoup)))

(defn normalize-text [text]
  (-> text
      (str/replace #"\s+" " ")
      str/trim))

(defn extract-text [path]
  (try
    (let [html (slurp (io/file path))
          text (-> html
                   Jsoup/parse
                   .text
                   normalize-text)]
      (if (str/blank? text)
        {:ok? false :reason :empty-text}
        {:ok? true :text text}))
    (catch Exception ex
      {:ok? false
       :reason :read-error
       :message (.getMessage ex)})))
