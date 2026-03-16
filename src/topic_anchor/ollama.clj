(ns topic-anchor.ollama
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn api-root [base-url]
  (let [trimmed (str/replace (or base-url "") #"/+$" "")]
    (if (str/ends-with? trimmed "/api")
      trimmed
      (str trimmed "/api"))))

(defn embed-url [base-url]
  (str (api-root base-url) "/embed"))

(defn make-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 2))
      .build))

(defn parse-embedding [body]
  (let [payload (json/read-str body :key-fn keyword)]
    (cond
      (vector? (:embedding payload)) (:embedding payload)
      (vector? (first (:embeddings payload))) (first (:embeddings payload))
      :else (throw (ex-info "Embedding vector missing from Ollama response"
                            {:reason :missing-embedding
                             :body payload})))))

(defn build-request [base-url model input]
  (let [payload (json/write-str {:model model :input input})]
    (-> (HttpRequest/newBuilder)
        (.uri (URI/create (embed-url base-url)))
        (.timeout (Duration/ofSeconds 60))
        (.header "Content-Type" "application/json")
        (.POST (HttpRequest$BodyPublishers/ofString payload))
        .build)))

(defn embed-text
  ([base-url model input]
   (embed-text (make-client) base-url model input))
  ([client base-url model input]
   (let [request (build-request base-url model input)]
     (try
       (let [response (.send client request (HttpResponse$BodyHandlers/ofString))
             status (.statusCode response)
             body (.body response)]
         (if (= 200 status)
           {:ok? true
            :embedding (parse-embedding body)}
           {:ok? false
            :reason :http-error
            :status status
            :message body}))
       (catch clojure.lang.ExceptionInfo ex
         {:ok? false
          :reason (or (:reason (ex-data ex)) :malformed-response)
          :message (.getMessage ex)})
       (catch Exception ex
         {:ok? false
          :reason :request-failed
          :message (.getMessage ex)})))))
