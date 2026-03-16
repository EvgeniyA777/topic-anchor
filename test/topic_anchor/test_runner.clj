(ns topic-anchor.test-runner
  (:require [clojure.test :as t]
            topic-anchor.core-test
            topic-anchor.core-integration-test
            topic-anchor.fs-test
            topic-anchor.html-test
            topic-anchor.install-test
            topic-anchor.launcher-test
            topic-anchor.markdown-test
            topic-anchor.ollama-test
            topic-anchor.scoring-test
            topic-anchor.wrapper-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'topic-anchor.core-test
                                          'topic-anchor.core-integration-test
                                          'topic-anchor.fs-test
                                          'topic-anchor.html-test
                                          'topic-anchor.install-test
                                          'topic-anchor.launcher-test
                                          'topic-anchor.markdown-test
                                          'topic-anchor.ollama-test
                                          'topic-anchor.scoring-test
                                          'topic-anchor.wrapper-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
