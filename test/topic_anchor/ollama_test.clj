(ns topic-anchor.ollama-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [topic-anchor.ollama :as ollama]
            [topic-anchor.test-support :as test-support]))

(deftest api-root-normalizes-base-url
  (is (= "http://127.0.0.1:11434/api" (ollama/api-root "http://127.0.0.1:11434")))
  (is (= "http://127.0.0.1:11434/api" (ollama/api-root "http://127.0.0.1:11434/api/"))))

(deftest embed-text-reads-embedding-from-json-response
  (test-support/with-http-server
    (fn [exchange]
      (let [payload (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)]
        (is (= "demo-model" (:model payload)))
        (is (= "sample text" (:input payload)))
        (test-support/write-json! exchange 200 {:embeddings [[0.1 0.2 0.3]]})))
    (fn [{:keys [base-url]}]
      (is (= {:ok? true :embedding [0.1 0.2 0.3]}
             (ollama/embed-text base-url "demo-model" "sample text"))))))

(deftest embed-text-reports-http-errors
  (test-support/with-http-server
    (fn [exchange]
      (test-support/write-json! exchange 500 {:error "boom"}))
    (fn [{:keys [base-url]}]
      (let [result (ollama/embed-text base-url "demo-model" "sample text")]
        (is (false? (:ok? result)))
        (is (= :http-error (:reason result)))
        (is (= 500 (:status result)))))))

(deftest embed-text-detects-model-not-found
  (test-support/with-http-server
    (fn [exchange]
      (test-support/write-json! exchange 404 {:error "model 'missing' not found"}))
    (fn [{:keys [base-url]}]
      (let [result (ollama/embed-text base-url "missing" "sample text")]
        (is (false? (:ok? result)))
        (is (= :model-not-found (:reason result)))
        (is (= "model 'missing' not found" (:message result)))))))

(deftest list-models-reads-tags-response
  (test-support/with-http-server
    {:embed (fn [exchange]
              (test-support/write-json! exchange 200 {:embeddings [[0.1]]}))
     :tags (fn [exchange]
             (test-support/write-json! exchange 200 {:models [{:name "nomic-embed-text"}
                                                              {:model "qwen2.5:7b"}]}))}
    (fn [{:keys [base-url]}]
      (is (= {:ok? true :models ["nomic-embed-text" "qwen2.5:7b"]}
             (ollama/list-models base-url))))))

(deftest parse-embedding-accepts-legacy-single-embedding-shape
  (testing "legacy Ollama responses keep the client tolerant"
    (is (= [0.1 0.2]
           (ollama/parse-embedding "{\"embedding\": [0.1, 0.2]}")))))
