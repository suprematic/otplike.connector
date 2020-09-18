(ns otplike.connector.routes
  (:require
   [compojure.core :refer [GET defroutes context]]
   [compojure.route :as route]
   [otplike.connector.connection :as connection]
   [ring.middleware.defaults :as ring-defaults]
   [ring.middleware.reload :refer [wrap-reload]]
   [taoensso.timbre :as log]))


;; ====================================================================
;; Internal
;; ====================================================================


(defroutes routes
  (GET "/connect" []
    (connection/http-kit-handler))

  (route/not-found "404 - Not Found"))


;; ====================================================================
;; API
;; ====================================================================


(def root
  (-> #'routes
    (ring-defaults/wrap-defaults
      (assoc-in
        ring-defaults/secure-api-defaults [:security :ssl-redirect] false))
    wrap-reload))
