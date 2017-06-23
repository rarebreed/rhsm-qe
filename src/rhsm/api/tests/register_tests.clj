(ns rhsm.api.tests.register_tests
  (:use [test-clj.testng :only (gen-class-testng)]
        [rhsm.gui.tasks.test-config :only (config
                                           clientcmd
                                           auth-proxyrunner
                                           noauth-proxyrunner)]
        [slingshot.slingshot :only (try+
                                    throw+)]
        [com.redhat.qe.verify :only (verify)]
        rhsm.gui.tasks.tools
        gnome.ldtp)
  (:require [clojure.tools.logging :as log]
            [rhsm.gui.tasks.tasks :as tasks]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.match :refer [match]]
            [rhsm.dbus.parser :as dbus])
  (:import [org.testng.annotations
            Test
            BeforeClass
            AfterClass
            BeforeGroups
            AfterGroups
            DataProvider]
           org.testng.SkipException
           [com.github.redhatqe.polarize.metadata TestDefinition]
           [com.github.redhatqe.polarize.metadata DefTypes$Project]))

(defn ^{Test {:groups ["registration"
                       "DBUS"
                       "API"
                       "tier1"]
              :description "Given a system is unregistered
   and there is a simple activation key for my account
When I
"
              :dataProvider "register-socket"}
        TestDefinition {:projectID [`DefTypes$Project/RedHatEnterpriseLinux7]}}
  register_using_dbus
  [_ socket]
  (let [[_ major minor] (re-find #"(\d)\.(\d)" (-> :true get-release :version))]
    (match major
           (a :guard #(< (Integer. %) 7 )) (throw (SkipException. "busctl is not available in RHEL6"))
           :else nil))
  (run-command "subscription-manager unregister")
  (let [response (-> (format "busctl --address=unix:abstract=%s call com.redhat.RHSM1 /com/redhat/RHSM1/Register com.redhat.RHSM1.Register Register 'sssa{sv}' %s %s %s 0"
                             socket (@config :owner-key) (@config :username) (@config :password))
                     run-command)]
    (let [[parsed-response rest-string] (-> response  :stdout (.trim) dbus/parse)]
      (verify (->> rest-string (= ""))) ;; no string left unparsed
      (verify (->> parsed-response keys (= ["content" "status" "headers"])))
      (verify (-> parsed-response (get "status") (= 200)))
      (let [response-headers (get parsed-response "headers")]
        (verify (-> response-headers keys (= ["date"
                                              "x-version"
                                              "transfer-encoding"
                                              "x-candlepin-request-uuid"
                                              "content-type"
                                              "server"]))))
      ;;(let [content (-> parsed-response (get "content"))])
      )))

(defn ^{DataProvider {:name "register-socket"}}
  register_socket
  "provides a socket that is used by DBus RHSM Register service"
  [_]
  "response:   s \"unix:abstract=/var/run/dbus-XP0szXbntD,guid=e234053b12b7e78b2c48cac758a60d17\""
  (-> (->> "busctl call com.redhat.RHSM1 /com/redhat/RHSM1/RegisterServer com.redhat.RHSM1.RegisterServer Start"
           run-command
           :stdout
           (re-seq #"abstract=([^,]+)"))
      (nth 0)
      (nth 1)
      vector
      vector
      to-array-2d))

(gen-class-testng)