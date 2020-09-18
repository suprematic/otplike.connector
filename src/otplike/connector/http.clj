(ns otplike.connector.http
  (:require 
   [defun.core :refer [defun-]]
   [org.httpkit.server :as http-kit]
   [otplike.gen-server :as gs]
   [suprematic.config :as config]
   [taoensso.timbre :as log]))


;; ====================================================================
;; gen-server callbacks
;; ====================================================================


(defn init [app]
  (let [port (config/get :otplike-connector-port)
        host (config/get :otplike-connector-host)]
    (log/infof "starting HTTP server on %s:%s" host port)
    (let [stop (http-kit/run-server
                 app
                 {:ip host
                  :port port
                  :auto-reload? true
                  :join? false
                  :max-body (* 1024 1000 1000)})]
      (log/info "HTTP server started")
      [:ok {:stop stop}])))


(defun- handle-info
  ([[:EXIT _pid reason] state]
   [:stop reason state]))


(defn terminate [_ {:keys [stop]}]
  (log/info "terminating HTTP server")
  (stop :timeout 500))


;; ====================================================================
;; API
;; ====================================================================


(defn start-link< [app]
  (gs/start-link-ns ::server [app] {:spawn-opt {:flags {:trap-exit true}}}))
