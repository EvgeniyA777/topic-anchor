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

(defn- with-http-server-config [{:keys [embed tags]} f]
  (let [server (HttpServer/create (InetSocketAddress. 0) 0)
        tags-handler (or tags
                         (fn [exchange]
                           (write-json! exchange 200 {:models []})))]
    (.createContext
     server
     "/api/embed"
     (reify HttpHandler
       (handle [_ exchange]
         (embed exchange))))
    (.createContext
     server
     "/api/tags"
     (reify HttpHandler
       (handle [_ exchange]
         (tags-handler exchange))))
    (.start server)
    (try
      (f {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})
      (finally
        (.stop server 0)))))

(defn with-http-server [handler-or-config f]
  (if (map? handler-or-config)
    (with-http-server-config handler-or-config f)
    (with-http-server-config {:embed handler-or-config} f)))
