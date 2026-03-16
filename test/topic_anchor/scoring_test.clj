(ns topic-anchor.scoring-test
  (:require [clojure.test :refer [deftest is testing]]
            [topic-anchor.scoring :as scoring]))

(deftest cosine-similarity-matches-expected-geometry
  (is (= 1.0 (double (scoring/cosine-similarity [1 0] [1 0]))))
  (is (= 0.0 (double (scoring/cosine-similarity [1 0] [0 1])))))

(deftest threshold-policy-computes-median-and-mad
  (let [policy (scoring/threshold-policy [0.95 0.94 0.93 0.90 0.60 0.58])]
    (is (= 0.915 (:median-score policy)))
    (is (= 0.029999999999999916 (:mad policy)))
    (is (= 0.8250000000000003 (:outlier-threshold policy)))
    (is (= 0.8550000000000002 (:suspect-threshold policy)))))

(deftest classify-score-follows-policy
  (let [policy (scoring/threshold-policy [0.95 0.94 0.93 0.90 0.60 0.58])]
    (is (= "OK" (scoring/classify-score 0.90 policy)))
    (is (= "SUSPECT" (scoring/classify-score 0.85 policy)))
    (is (= "OUTLIER" (scoring/classify-score 0.70 policy)))))

(deftest review-index-selects-the-lowest-score
  (testing "ties break by earliest index for deterministic output"
    (is (= 1 (scoring/review-index [0.9 0.5 0.5 0.8])))))
