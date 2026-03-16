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

(deftest parse-embedding-accepts-legacy-single-embedding-shape
  (testing "legacy Ollama responses keep the client tolerant"
    (is (= [0.1 0.2]
           (ollama/parse-embedding "{\"embedding\": [0.1, 0.2]}")))))
