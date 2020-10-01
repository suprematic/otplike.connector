(ns otplike.connector.connection
  (:require
   [clojure.core.match :refer [match]]
   [cognitect.transit :as transit]
   [defun.core :refer [defun defun-]]
   [org.httpkit.server :as http-kit]
   [otplike.connector.connector :as connector]
   [otplike.gen-server :as gs]
   [otplike.process :as process]
   [taoensso.timbre :as log])
  (:import
   [otplike.process Pid TRef]))


;; ====================================================================
;; Internal
;; ====================================================================


(def ^:private default-ping-timeout-ms 15000)


(def ^:private default-pongs-missing-allowed 2)


(def ^:private default-transit-write-handlers
  {Pid
   (transit/write-handler
     "pid" (fn [^Pid pid] {:id (.id pid) :node (.node pid)}))
   TRef
   (transit/write-handler
     "otp-ref" (fn [^TRef tref] {:id (.id tref)}))})


(def ^:private default-transit-read-handlers
  {"pid"
   (transit/read-handler
     (fn [{:keys [id node]}]
       (Pid. id node)))
   "otp-ref"
   (transit/read-handler
     (fn [{:keys [id]}]
       (TRef. id)))})


(defn- transit-writer [stream {:keys [transit-write-handlers]}]
  (transit/writer
    stream
    :json
    {:handlers (merge default-transit-write-handlers transit-write-handlers)}))


(defn- transit-reader [stream {:keys [transit-read-handlers]}]
  (transit/reader
    stream
    :json
    {:handlers (merge default-transit-read-handlers transit-read-handlers)}))


(defn- transit-send [ws form opts]
  (let [os (java.io.ByteArrayOutputStream. 4096)]
    (-> os (transit-writer opts) (transit/write form))
    (http-kit/send! ws (.toString os))))


(defn- transit-read [string opts]
  (transit/read
    (transit-reader
      (java.io.ByteArrayInputStream.
        (.getBytes string java.nio.charset.StandardCharsets/UTF_8))
      opts)))


(defn- handle-close-fn [pid]
  (fn handle-close [_event]
    (log/debug "connection closed" :node-pid pid)
    (gs/cast pid :connection-closed)))


(defn- handle-receive-fn [opts pid]
  (fn handle-receive [data]
    (let [message
          (try
            (transit-read data opts)
            (catch Exception ex
              (log/error ex "cannot parse message from node"
                :node-pid pid :message data)
              (gs/cast pid [:exit (process/ex->reason ex)])
              (throw ex)))]
      (log/debug "message received from node" :node-pid pid :message message)
      (gs/cast pid [:message message]))))


(defn- start-link< [ws opts]
  (gs/start-link-ns
    [ws opts]
    {:timeout 10000 :spawn-opt {:flags {:trap-exit true}}}))


;; ====================================================================
;; gen-server callbacks
;; ====================================================================


(defn init [ws {:keys [ping-timeout-ms pongs-missing-allowed] :as opts}]
  (let [ping-timeout-ms (or ping-timeout-ms default-ping-timeout-ms)
        self-pid (process/self)
        node-id (.id ^Pid self-pid)]
    (connector/connect node-id)
    (http-kit/on-close ws (handle-close-fn self-pid))
    (http-kit/on-receive ws (handle-receive-fn opts self-pid))
    (transit-send ws [:connected node-id] opts)
    (log/debug "node connected" :pid (process/self) :node node-id)
    [:ok
     {:ws ws
      :ping-counter 0
      :pongs-waiting 0
      :ping-timeout-ms ping-timeout-ms
      :pongs-missing-allowed
      (or pongs-missing-allowed default-pongs-missing-allowed)
      :opts opts}
     ping-timeout-ms]))


(defun handle-cast
  ([:connection-closed state]
   [:stop :normal state])

  ([[:exit reason] state]
   [:stop reason state])

  ([[:message [:ping payload]] ({:ws ws :opts opts} :as state)]
   (log/debug "got ping, sending pong" :payload payload)
   (transit-send ws [:pong payload] opts)
   [:noreply state (:ping-timeout-ms state)])

  ([[:message [:pong payload]] state]
   (log/debug "got pong" :payload payload)
   [:noreply (assoc state :pongs-waiting 0) (:ping-timeout-ms state)])

  ([[:message [:command command]] state]
   (log/debug "got command" :command command)
   (connector/command command)
   [:noreply state (:ping-timeout-ms state)])
  
  ([[:message msg] state]
   (log/error "unrecognized message format" :message msg)
   [:stop [:invalid-protocol msg] state]))


(defun handle-info
  ([:timeout state]
   (let [{:keys
          [ws
           ping-timeout-ms
           ping-counter
           pongs-missing-allowed
           pongs-waiting
           opts]} state]
     (if (> pongs-waiting pongs-missing-allowed)
       (do
         (log/warn "exiting, pongs missing"
           :missing-allowed pongs-missing-allowed)
         [:stop [:pongs-missing pongs-waiting] state])
       (do
         (log/debug "sending ping"
           :payload ping-counter :pongs-waiting pongs-waiting)
         (transit-send ws [:ping ping-counter] opts)
         [:noreply
          (-> state (update :ping-counter inc) (update :pongs-waiting inc))
          ping-timeout-ms]))))

  ([[:send msg] ({:ws ws :opts opts} :as state)]
   (log/debug "sending message to node" :message msg)
   (transit-send ws [:command msg] opts)
   [:noreply state (:ping-timeout-ms state)]))


(defun terminate
  ([:normal {:ws ws}]
   (when (http-kit/open? ws)
     (http-kit/close ws)))
  ([reason {:ws ws :opts opts}]
   (log/error "terminating abnormally" :reason reason :self (process/self))
   (when (http-kit/open? ws)
     (try
       (transit-send ws [:exit reason] opts)
       (finally
         (http-kit/close ws))))))


;; ====================================================================
;; API
;; ====================================================================


(defn http-kit-handler
  "The options are:
    :transit-write-handlers - a map of transit write handlers
    :transit-read-handlers - a map of transit read handlers"
  ([]
   (http-kit-handler {}))
  ([opts]
   (fn [request]
     (http-kit/with-channel request ws
       (if (http-kit/websocket? ws)
         (process/spawn-opt
           (process/proc-fn []
             (match (process/await! (start-link< ws opts))
               [:ok _pid]
               :ok

               [:error reason]
               (do
                 (log/warn "handler :: cannot initialize connection" :reason reason)
                 (transit-send ws [:exit reason] opts)))
             (process/receive!
               _ :ok))
           {:flags {:trap-exit true}})
         
         (do
           (log/warn "[on-connect] :: not a websocket connection")
           (http-kit/send!
             ws {:status 426 :headers {"upgrade" "websocket"}})))))))
