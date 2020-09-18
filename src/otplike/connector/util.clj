(ns otplike.connector.util
  (:require
   [clojure.core.match :refer [match]]
   [clojure.pprint :as pprint]
   [otplike.process :as process]
   [otplike.trace :as trace]
   [taoensso.timbre :as log]))


;; ====================================================================
;; Internal
;; ====================================================================


(defn- truncate-exception [reason-exception]
  (-> reason-exception
    (update :message #(when % (subs % 0 (min 512 (count %)))))
    (update :stack-trace #(take 32 %))))


;; ====================================================================
;; API
;; ====================================================================


(defn trace-processes []
  (process/trace
    trace/crashed?
    (fn [{pid :pid {:keys [reason]} :extra}]
      (log/error "process terminated abnormally" :pid pid :reason reason))))


(defn println-err [& args]
  (binding [*out* *err*]
    (apply println args)))


(defn exit
  ([status]
   (System/exit status))
  ([status message]
   (println-err message)
   (exit status)))
