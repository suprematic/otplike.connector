(defproject otplike.connector "0.0.1-SNAPSHOT"
  :min-lein-version "2.9.1"

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojure/core.async "1.3.610"]
   [org.clojure/core.match "1.0.0"]
   [cheshire "5.10.0"]
   [otplike "0.6.1-alpha-SNAPSHOT"]
   [compojure "1.6.2"]
   [http-kit "2.5.0"]
   [clj-http "3.10.2"]
   [ring "1.8.1"]
   [defun "0.3.1"]
   [ring/ring-defaults "0.3.2"]
   [http-kit "2.3.0"]
   [com.taoensso/timbre "4.10.0"]
   [jarohen/chord "0.8.1" :exclusions [cheshire]]
   [suprematic/config "0.2.0"]]

  :main otplike.connector.core
  :jvm-opts ["-Xmx256m"]

  :uberjar-name "otplike-connector.jar"

  :profiles
  {:uberjar {:aot :all}})
