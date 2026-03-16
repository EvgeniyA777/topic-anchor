(ns topic-anchor.test-runner
  (:require [clojure.test :as t]
            topic-anchor.core-test
            topic-anchor.fs-test
            topic-anchor.html-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'topic-anchor.core-test
                                          'topic-anchor.fs-test
                                          'topic-anchor.html-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
