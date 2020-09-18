(ns otplike.connector.config
  (:require
   [suprematic.config.parse :as parse]))


;; ====================================================================
;; API
;; ====================================================================


(def opts
  {"OTPLIKE_CONNECTOR_PORT"
   {:parse-fn parse/port
    :default 9100}
   "OTPLIKE_CONNECTOR_HOST"
   {:parse-fn parse/not-empty-str
    :default "127.0.0.1"}})
