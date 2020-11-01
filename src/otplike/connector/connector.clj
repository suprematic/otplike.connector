(ns otplike.connector.connector
  (:require
   [clojure.core.match :refer [match]]
   [defun.core :refer [defun defun-]]
   [otplike.gen-server :as gs]
   [otplike.process :as process :refer [!]]
   [taoensso.timbre :as log]))


;; ====================================================================
;; Internal
;; ====================================================================


(defn- send-message [node-pid msg]
  (! node-pid [:send [:message msg]]))


(defn- send-no-route [node-pid k msg]
  (! node-pid [:send [:no-route k msg]]))


(defn- send-routed [node-pid k msg dest-node]
  (! node-pid [:send [:routed k msg dest-node]]))


(defn- send-registered [node-pid k]
  (! node-pid [:send [:registered k]]))


(defn- send-unregister [node-pid k reason]
  (! node-pid [:send [:unregister k reason]]))


(defn- send-node-down [node-pid node]
  (! node-pid [:send [:node-down node]]))


(defn- unreg-all-names* [{:keys [pid->names name->pid] :as state} pid]
  (let [pid-names (pid->names pid)
        name->pid (apply dissoc name->pid pid-names)]
    (-> state
      (update :pid->names dissoc pid)
      (assoc :name->pid name->pid))))


(defun- route**
  ([state origin-node-pid ([:name reg-name] :as k) msg opts]
   (if-some [node-pid (get-in state [:name->pid reg-name])]
     (do
       (send-message node-pid msg)
       (when (:confirm? opts)
         (let [dest-node (get-in state [:pid->node node-pid])]
           (send-routed origin-node-pid k msg dest-node))))
     (when (:confirm? opts)
       (send-no-route origin-node-pid k msg)))
   state)

  ([state origin-node-pid ([:node node] :as k) msg opts]
   (if-some [node-pid (get-in state [:node->pid node])]
     (do
       (send-message node-pid msg)
       (when (:confirm? opts)
         (send-routed origin-node-pid k msg node)))
     (when (:confirm? opts)
       (send-no-route origin-node-pid k msg)))
   state))


(defn- route*
  ([state origin-node-pid k msg]
   (route* state origin-node-pid k msg {}))
  ([state origin-node-pid k msg opts]
   (route** state origin-node-pid k msg opts)))


(defn- broadcast-node-down [state node]
  (doseq [node-pid (-> state :node->pid vals)]
    (send-node-down node-pid node))
  state)


(defn- disconnect [state pid]
  (if-let [node (get-in state [:pid->node pid])]
    (-> state
      (update :node->pid dissoc node)
      (update :pid->node dissoc pid)
      (unreg-all-names* pid)
      (broadcast-node-down node))
    state))


(defn- connect* [state node-pid node]
  (process/link node-pid)
  (-> state
    (assoc-in [:node->pid node] node-pid)
    (assoc-in [:pid->node node-pid] node)))


(defn- add-name
  [{:keys [name->pid pid->node] :as state} node-pid reg-name]
  (let [owner-node-pid (name->pid reg-name)]
    (cond
      (nil? owner-node-pid)
      [:ok
       (-> state
         (update-in [:pid->names node-pid] #(conj (or % #{}) reg-name))
         (assoc-in [:name->pid reg-name] node-pid))]

      (= node-pid owner-node-pid)
      [:ok state]

      :else
      [:error [:registered {:node (get pid->node owner-node-pid)}]])))


(defun- add-key
  ([state node-pid [:name reg-name]]
   (add-name state node-pid reg-name))

  ([_state node-pid k]
   (log/error "unexpected registration key format" :node node-pid :key k)
   [:error [:invalid-key-format k]]))


(defn- register* [state node-pid ks]
  (loop [new-state state
         rest-ks ks]
    (if (empty? rest-ks)
      (do
        (send-registered node-pid ks)
        new-state)
      (let [k (first rest-ks)]
        (match (add-key new-state node-pid k)
          [:ok next-state]
          (recur next-state (rest ks))

          [:error reason]
          (do
            (send-unregister node-pid ks reason)
            state))))))


(defn- remove-name [{:keys [name->pid] :as state} node-pid reg-name]
  (let [owner-node-pid (name->pid reg-name)]
    (cond
      (= node-pid owner-node-pid)
      (-> state
        (update-in [:pid->names node-pid] #(disj (or % #{}) reg-name))
        (update :name->pid dissoc reg-name))

      :else
      state)))


(defun- remove-key
  ([state node-pid [:name reg-name]]
   (remove-name state node-pid reg-name))

  ([state node-pid k]
   (log/error "unexpected registration key" :node node-pid :key k)
   state))


(defn- unregister* [state node-pid ks]
  (reduce #(remove-key %1 node-pid %2) state ks))


(defun- handle-command
  ([node-pid [:register ks] state]
   (log/debug "registering keys" :pid node-pid :keys ks)
   (register* state node-pid ks))

  ([node-pid [:unregister ks] state]
   (log/debug "unregistering keys" :pid node-pid :keys ks)
   (unregister* state node-pid ks))

  ([node-pid [:route dest msg] state]
   (log/debug "routing message" :dest dest :message msg)
   (route* state node-pid dest msg))

  ([node-pid [:route dest msg opts] state]
   (log/debug "routing message" :dest dest :message msg)
   (route* state node-pid dest msg opts))

  ([node-pid command state]
   (log/error "unrecognized command" :pid node-pid :command command)
   state))


;; ====================================================================
;; gen-server callbacks
;; ====================================================================


(defn- init []
  [:ok
   {:node->pid {}
    :pid->node {}
    :pid->names {}
    :name->pid {}}])


(defun handle-cast
  ([[::connect node-pid node] state]
   (log/debug "connecting node" :pid node-pid :node node)
   [:noreply (connect* state node-pid node)])

  ([[::command node-pid command] state]
   (log/debug "processing command" :pid node-pid :command command)
   [:noreply (handle-command node-pid command state)]))


(defun handle-info
  ([[:EXIT pid reason] state]
   (log/debug "node down" :pid pid :reason reason)
   [:noreply (disconnect state pid)]))


;; ====================================================================
;; API
;; ====================================================================


(defn start-link< []
  (gs/start-link-ns ::server [] {:spawn-opt {:flags {:trap-exit true}}}))


(defn connect [node]
  (gs/cast ::server [::connect (process/self) node]))


(defn command [command]
  (gs/cast ::server [::command (process/self) command]))
