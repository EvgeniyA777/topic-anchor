(ns topic-anchor.test-support
  (:require [clojure.data.json :as json])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(defn write-json! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/write-str payload) "UTF-8")]
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [body (.getResponseBody exchange)]
      (.write body bytes))))

(defn with-http-server [handler f]
  (let [server (HttpServer/create (InetSocketAddress. 0) 0)]
    (.createContext
     server
     "/api/embed"
     (reify HttpHandler
       (handle [_ exchange]
         (handler exchange))))
    (.start server)
    (try
      (f {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})
      (finally
        (.stop server 0)))))
