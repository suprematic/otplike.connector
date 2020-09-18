(ns otplike.connector.connector
  (:require
   [defun.core :refer [defun defun-]]
   [otplike.gen-server :as gs]
   [otplike.process :as process :refer [!]]
   [taoensso.timbre :as log]))


;; ====================================================================
;; Internal
;; ====================================================================


#_(defn- reg-props* [{:keys [node->props prop->pids] :as state} pid props]
    (let [node->props (update node->props pid merge props)
          prop->pids (reduce
                       (fn [acc p]
                         (update acc p #(conj (or % #{}) pid)))
                       prop->pids
                       props)
          state (assoc state
                  :node->props node->props
                  :prop->pids prop->pids)]
      (notify-props state ::prop-set pid props)))


(defn- unreg-props* [{:keys [pid->props prop->pids] :as state} node-pid props]
  (let [pid-props (apply dissoc (pid->props node-pid) props)
        pid->props (if (empty? pid-props)
                     (dissoc pid->props node-pid)
                     (assoc pid->props node-pid pid-props))
        prop->pids (reduce
                     (fn [acc p]
                       (let [prop-pids (disj (acc p) node-pid)]
                         (if (empty? prop-pids)
                           (dissoc acc p)
                           (assoc acc p prop-pids))))
                     prop->pids
                     props)]
    (assoc state
      :pid->props pid->props
      :prop->pids prop->pids)))


(defn- unreg-all-props* [{:keys [pid->props] :as state} node-pid]
  (unreg-props* state node-pid (keys (pid->props node-pid))))


(defn- unreg-all-names* [{:keys [pid->names name->pid] :as state} pid]
  (let [pid-names (pid->names pid)
        name->pid (apply dissoc name->pid pid-names)]
    (-> state
      (update :pid->names dissoc pid)
      (assoc :name->pid name->pid))))


(defn- send-message [node-pid msg]
  (! node-pid [:send [:message msg]]))


(defn- send-no-route [node-pid k msg]
  (! node-pid [:send [:no-route k msg]]))


(defn- send-unregister [node-pid k reason]
  (! node-pid [:send [:unregister k reason]]))


(defn- send-node-down [node-pid node]
  (! node-pid [:send [:node-down node]]))


(defun- route*
  ([state origin-node-pid ([:name reg-name] :as k) msg]
   (if-some [node-pid (get-in state [:name->pid reg-name])]
     (send-message node-pid msg)
     (send-no-route origin-node-pid k msg))
   state)
  ([state origin-node-pid ([:node node] :as k) msg]
   (if-some [node-pid (get-in state [:node->pid node])]
     (send-message node-pid msg)
     (send-no-route origin-node-pid k msg))
   state))


(defn- broadcast-node-down [state node]
  (doseq [node-pid (-> state :node->pid vals)]
    (send-node-down node-pid node))
  state)


(defn- down [state pid]
  (if-let [node (get-in state [:pid->node pid])]
    (-> state
      (update :node->pid dissoc node)
      (update :pid->node dissoc pid)
      (unreg-all-props* pid)
      (unreg-all-names* pid)
      (broadcast-node-down node))
    state))


(defn- connect* [state node-pid node]
  (process/link node-pid)
  (-> state
    (assoc-in [:node->pid node] node-pid)
    (assoc-in [:pid->node node-pid] node)))


(defn- register-name
  [{:keys [name->pid pid->node] :as state} node-pid reg-name]
  (let [owner-node-pid (name->pid reg-name)]
    (cond
      (nil? owner-node-pid)
      (-> state
        (update-in [:pid->names node-pid] #(conj (or % #{}) reg-name))
        (assoc-in [:name->pid reg-name] node-pid))

      (= node-pid owner-node-pid)
      state

      :else
      (do
        (send-unregister node-pid
          [:name reg-name]
          [:registered {:node (get pid->node owner-node-pid)}])
        state))))


(defun- register-key
  ([state node-pid [:name reg-name]]
   (register-name state node-pid reg-name))

  ([state node-pid k]
   (log/error "unexpected registration key" :node node-pid :key k)
   state))


(defn- register* [state node-pid items]
  (reduce #(register-key %1 node-pid %2) state items))


(defn- unregister-name [{:keys [name->pid] :as state} node-pid reg-name]
  (let [owner-node-pid (name->pid reg-name)]
    (cond
      (= node-pid owner-node-pid)
      (-> state
        (update-in [:pid->names node-pid] #(disj (or % #{}) reg-name))
        (update :name->pid dissoc reg-name))

      :else
      state)))


(defun- unregister-key
  ([state node-pid [:name reg-name]]
   (unregister-name state node-pid reg-name))

  ([state node-pid k]
   (log/error "unexpected registration key" :node node-pid :key k)
   state))


(defn- unregister* [state node-pid ks]
  (reduce #(unregister-key %1 node-pid %2) state ks))


;; ====================================================================
;; gen-server callbacks
;; ====================================================================


(defn- init []
  [:ok
   {:node->pid {}
    :pid->node {}
    :pid->names {}
    :name->pid {}
    :pid->props {}
    :prop->pids {}}])


(defun handle-cast
  ([[::connect node-pid node] state]
   (log/debug "connecting node" :pid node-pid :node node)
   [:noreply (connect* state node-pid node)])
  ([[::register node-pid ks] state]
   (log/debug "registering items" :pid node-pid :keys ks)
   [:noreply (register* state node-pid ks)])
  ([[::unregister node-pid ks] state]
   (log/debug "unregistering items" :pid node-pid :keys ks)
   [:noreply (unregister* state node-pid ks)])
  ([[::route node-pid dest msg] state]
   (log/debug "routing message" :dest dest :message msg)
   [:noreply (route* state node-pid dest msg)]))


(defun handle-info
  ([[:EXIT pid reason] state]
   (log/debug "node down" :pid pid :reason reason)
   [:noreply (down state pid)]))


;; ====================================================================
;; API
;; ====================================================================


(defn start-link< []
  (gs/start-link-ns ::server [] {:spawn-opt {:flags {:trap-exit true}}}))


(defn connect [node]
  (gs/cast ::server [::connect (process/self) node]))


(defn register [items]
  (gs/cast ::server [::register (process/self) items]))


(defn unregister [items]
  (gs/cast ::server [::unregister (process/self) items]))


(defn route [to msg]
  (gs/cast ::server [::route (process/self) to msg]))
