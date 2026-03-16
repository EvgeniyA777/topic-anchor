(ns topic-anchor.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [topic-anchor.core :as core]))

(deftest validate-cli-reports-missing-required-inputs-later
  (testing "bootstrap stage accepts the CLI surface without running the pipeline"
    (let [{:keys [ok? options]} (core/validate-cli {:options {:anchor "a" :dir "b" :model "c"}
                                                    :errors nil})]
      (is ok?)
      (is (= "a" (:anchor options)))
      (is (= "c" (:model options))))))

(deftest usage-text-documents-command-shape
  (let [text (core/usage "summary")]
    (is (.contains text "topic-anchor"))
    (is (.contains text "--anchor ./good.html"))
    (is (.contains text "summary"))))
