(ns timi.server.components.core
  (:require
    [com.stuartsierra.component :as component]
    [timi.server.components.cli :as cli]
    [timi.server.components.httpd :as httpd]))

(defn init [app config]
  (component/system-map
    :cfg config
    :cli (component/using
             (cli/new-server)
             [:cfg])
    :httpd (component/using
             (httpd/new-server app)
             [:cfg])))

(defn stop [system component-key]
  (->> system
       (component-key)
       (component/stop)
       (assoc system component-key)))

(defn start [system component-key]
  (->> system
       (component-key)
       (component/start)
       (assoc system component-key)))

(defn restart [system component-key]
  (-> system
      (stop component-key)
      (start component-key)))
