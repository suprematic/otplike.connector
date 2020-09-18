(ns otplike.connector.core
  (:gen-class)
  (:require 
   [clojure.core.match :refer [match]]
   [otplike.connector.config :as server-config]
   [otplike.connector.log :as server-log]
   [otplike.connector.root-sup :as root-sup]
   [otplike.connector.util :as util]
   [otplike.connector.version :as version]
   [otplike.process :as process :refer [!]]
   [suprematic.config :as config]
   [suprematic.report :as report]
   [taoensso.timbre :as log]))


;; ====================================================================
;; Internal
;; ====================================================================


(defonce ^:private boot-pid (atom nil))


(def ^:private start-timeout 10000)


(defn terminate-root [root-pid shutdown-promise]
  (process/async
    (log/debug "terminating root supervisor")
    (process/exit root-pid :normal)
    (process/receive!
      [:EXIT root-pid :normal]
      (do
        (log/infof "root supervisor terminated normally")
        (deliver shutdown-promise :ok))

      [:EXIT root-pid reason]
      (do
        (log/errorf "root supervisor terminated with reason=%s" reason)
        (deliver shutdown-promise [:error reason]))

      (after start-timeout
        (log/errorf
          "root supervisor did not terminate within %sms, exiting boot process"
          start-timeout)
        (deliver shutdown-promise
          [:error [:terminate-timeout start-timeout]])))))


(defn await-termination [root-pid shutdown-promise]
  (process/async
    (loop []
      (process/receive!
        [:stop p]
        (try
          (process/await! (terminate-root root-pid shutdown-promise))
          (deliver p :ok)
          (catch Exception e
            (deliver p e)))

        [:EXIT root-pid reason]
        (do
          (log/errorf
            "root supervisor terminated with reason=%s, exiting boot process"
            reason)
          (deliver shutdown-promise [:error reason]))

        other
        (do
          (log/errorf "boot process received unexpected message: %s" other)
          (recur))))))


(process/proc-defn- app-proc [shutdown-promise]
  (match (process/await! (root-sup/start-link<))
    [:ok root-pid]
    (try
      (reset! boot-pid (process/self))
      (log/info "otplike.connector server is started")
      (process/await! (await-termination root-pid shutdown-promise))
      (reset! boot-pid nil)
      (catch Exception ex
        (reset! boot-pid nil)
        (deliver shutdown-promise [:error (process/ex->reason ex)])))

    [:error reason]
    (do
      (reset! boot-pid nil)
      (log/error "root supervisor terminated" :reason reason)
      (deliver shutdown-promise [:error reason]))))


(defn- log-config []
  (log/info "Server config"
    :port (config/get :otplike-connector-port)
    :host (config/get :otplike-connector-host)))


(defn- start* [shutdown-promise]
  (let [old (first (swap-vals! boot-pid #(if (nil? %) :starting %)))]
    (if (nil? old)
      (try
        (report/doreport
          [_ (config/read! server-config/opts)
           _ (server-log/configure-logger)]
          (do
            (log/info "Starting otplike.connector server"
              :version (version/project-full-version))
            (log-config)
            (util/trace-processes)
            (process/spawn-opt
              app-proc [shutdown-promise] {:flags {:trap-exit true}})
            :ok))
        (catch Exception e
          (reset! boot-pid nil)
          (throw e)))
      (report/error
        (if (process/pid? old) :already-started old)
        "already started"
        [:location/default]))))


(defn- exit-on-report [x]
  (if (report/report? x)
    (util/exit 1 (report/format x))
    x))


(defn- main-start []
  (let [shutdown-promise (promise)]
    (exit-on-report (start* shutdown-promise))
    (match @shutdown-promise
      :ok
      (util/exit 0)

      [:error reason]
      (util/exit 1 (str reason)))))


(defn- print-version [args]
  (if-some [arg (-> args second)]
    (util/exit 1 (str "unexpected argument: " arg))
    (let [arg (first args)]
      (case arg
        nil
        (println (version/project-version))

        ("-v" "--verbose")
        (println (version/project-full-version))

        (util/exit 1 (str "unexpected argument: " arg))))))


;; ====================================================================
;; API
;; ====================================================================


(defn start []
  (start* (promise)))


(defn stop []
  (let [?pid (first (swap-vals! boot-pid #(if (process/pid? %) nil %)))]
    (if (process/pid? ?pid)
      (let [p (promise)]
        (if (! ?pid [:stop p])
          (deref p 5000 [:error :timeout])
          :not-started))
      (or ?pid :not-started))))


(defn -main [& [command & args]]
  (case command
    nil
    (exit-on-report (main-start))

    "version"
    (print-version args)

    (util/exit 1 (str "unexpected command: " command))))
