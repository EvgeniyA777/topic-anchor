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

(defn tags-url [base-url]
  (str (api-root base-url) "/tags"))

(defn make-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 2))
      .build))

(defn parse-json-body [body]
  (try
    (json/read-str body :key-fn keyword)
    (catch Exception ex
      (throw (ex-info "Ollama returned malformed JSON"
                      {:reason :malformed-response
                       :body body}
                      ex)))))

(defn api-error-message [body]
  (let [payload (parse-json-body body)]
    (or (:error payload)
        (:message payload)
        body)))

(defn parse-embedding [body]
  (let [payload (parse-json-body body)]
    (cond
      (vector? (:embedding payload)) (:embedding payload)
      (vector? (first (:embeddings payload))) (first (:embeddings payload))
      :else (throw (ex-info "Embedding vector missing from Ollama response"
                            {:reason :missing-embedding
                             :body payload})))))

(defn parse-models [body]
  (let [payload (parse-json-body body)
        models (:models payload)]
    (cond
      (vector? models)
      (->> models
           (map #(or (:name %) (:model %)))
           (remove nil?)
           vec)

      :else
      (throw (ex-info "Model list missing from Ollama response"
                      {:reason :malformed-response
                       :body payload})))))

(defn build-request [base-url model input]
  (let [payload (json/write-str {:model model :input input})]
    (-> (HttpRequest/newBuilder)
        (.uri (URI/create (embed-url base-url)))
        (.timeout (Duration/ofSeconds 60))
        (.header "Content-Type" "application/json")
        (.POST (HttpRequest$BodyPublishers/ofString payload))
        .build)))

(defn build-tags-request [base-url]
  (-> (HttpRequest/newBuilder)
      (.uri (URI/create (tags-url base-url)))
      (.timeout (Duration/ofSeconds 15))
      .GET
      .build))

(defn classify-http-error [status body]
  (let [message (try
                  (api-error-message body)
                  (catch clojure.lang.ExceptionInfo _
                    body))
        lower-message (str/lower-case (str message))]
    {:reason (if (and (#{400 404} status)
                      (str/includes? lower-message "model")
                      (str/includes? lower-message "not found"))
               :model-not-found
               :http-error)
     :status status
     :message message}))

(defn classify-request-failure [^Exception ex]
  (let [message (.getMessage ex)
        lower-message (str/lower-case (or message ""))]
    {:reason (if (or (str/includes? lower-message "connect")
                     (str/includes? lower-message "refused")
                     (str/includes? lower-message "timed out"))
               :service-unreachable
               :request-failed)
     :message message}))

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
           (merge {:ok? false} (classify-http-error status body))))
       (catch clojure.lang.ExceptionInfo ex
         {:ok? false
          :reason (or (:reason (ex-data ex)) :malformed-response)
          :message (.getMessage ex)})
       (catch Exception ex
         (merge {:ok? false} (classify-request-failure ex)))))))

(defn list-models
  ([base-url]
   (list-models (make-client) base-url))
  ([client base-url]
   (let [request (build-tags-request base-url)]
     (try
       (let [response (.send client request (HttpResponse$BodyHandlers/ofString))
             status (.statusCode response)
             body (.body response)]
         (if (= 200 status)
           {:ok? true
            :models (parse-models body)}
           (merge {:ok? false} (classify-http-error status body))))
       (catch clojure.lang.ExceptionInfo ex
         {:ok? false
          :reason (or (:reason (ex-data ex)) :malformed-response)
          :message (.getMessage ex)})
       (catch Exception ex
         (merge {:ok? false} (classify-request-failure ex)))))))
