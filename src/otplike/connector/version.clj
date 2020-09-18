(ns otplike.connector.version)


;; ====================================================================
;; Internal
;; ====================================================================


(defmacro ^:private project-version*
  "Get the project version at the compilation phase.
  Only applicable to Leiningen projects."
  []
  (System/getProperty "otplike.connector.version"))


(defmacro ^:private project-full-version* []
  `~(str (project-version*) (System/getenv "VERSION_SUFFIX")))


;; ====================================================================
;; API
;; ====================================================================


(defn project-version []
  (project-version*))


(defn project-full-version []
  (project-full-version*))
