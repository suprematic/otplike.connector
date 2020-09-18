(ns otplike.connector.root-sup
  (:require 
   [otplike.connector.connector :as connector]
   [otplike.connector.http :as http]
   [otplike.connector.routes :as routes]
   [otplike.supervisor :as supervisor]))


;; ====================================================================
;; Internal
;; ====================================================================


(defn- apps-sup-fn []
  [:ok
   [{:strategy :one-for-all
     :intensity 0}
    [{:id :reg
      :start [connector/start-link< []]}
     {:id :http
      :start [http/start-link< [routes/root]]}]]])


;; ====================================================================
;; API
;; ====================================================================


(defn start-link< []
  (supervisor/start-link apps-sup-fn []))
