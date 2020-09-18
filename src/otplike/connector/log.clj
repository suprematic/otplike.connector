(ns otplike.connector.log
  (:require
   [taoensso.timbre :as log]))


;; ====================================================================
;; Internal
;; ====================================================================


(defn- log-record
  [{:keys
    [level msg_ ?err vargs ?ns-str ?file hostname_ timestamp_ ?line ?msg-fmt]}]
  (let [[message & {:as data}] (if ?msg-fmt [(force msg_)] vargs)]
    (merge
      {:level level
       :hostname (force hostname_)
       :timestamp (force timestamp_)
       :message message}
      (when ?err
        {:error (pr-str ?err)})
      (when data
        {:data data})
      (when ?ns-str
        {:ns ?ns-str})
      (when ?file
        {:file ?file})
      (when ?line
        {:line ?line}))))


(defn- format-log-data [log-data]
  (-> log-data log-record (clojure.pprint/write :stream nil) #_pr-str))


;; ====================================================================
;; API
;; ====================================================================


(defn configure-logger []
  (log/merge-config!
    {:level :debug
     :output-fn format-log-data
     :timestamp-opts {:pattern "yyyy-MM-dd HH:mm:ss.SSS"}
     :appenders
     {:println (log/println-appender {:stream :auto})}}))

