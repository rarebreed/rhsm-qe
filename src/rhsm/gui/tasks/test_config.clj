(ns rhsm.gui.tasks.test-config
  (:import [com.redhat.qe.auto.testng TestScript]
           [com.redhat.qe.tools SSHCommandRunner]
           [rhsm.cli.tasks SubscriptionManagerTasks]
           [rhsm.cli.tasks CandlepinTasks]
           [rhsm.gui.tasks SSLUtilities]))

(defprotocol Defaultable
  (default [this] "returns the default value if the key is not found")
  (mapkey [this] "returns the key to get the value"))

(defrecord DefaultMapKey[key default]
  Defaultable
  (default [this] (:default this))
  (mapkey [this] (:key this)))

(extend-protocol Defaultable
  java.lang.String
  (default [this] nil)
  (mapkey [this] this))

(defn property-map [map]
  (zipmap (keys map)
          (for [v (vals map)]
            (let [val (System/getProperty (mapkey v) (default v))]
              (if (= "" val) nil val)))))

; to avoid a cyclic dependency, adding this in here and in tools.clj
(defn with-starting-slash
  "Tests for starting slash and adds if needed"
  [path]
  (if (= \/ (first path))
    path
    (str "/" path)))

(defn get-properties []
  (let [m (property-map {;proxy information
                         :basicauth-proxy-hostname "sm.basicauthproxy.hostname"
                         :basicauth-proxy-password "sm.basicauthproxy.password"
                         :basicauth-proxy-port "sm.basicauthproxy.port"
                         :basicauth-proxy-username "sm.basicauthproxy.username"
                         :basicauth-proxy-log (DefaultMapKey.
                                                "sm.basicauthproxy.log"
                                                "/var/log/squid/access.log")
                         :noauth-proxy-hostname "sm.noauthproxy.hostname"
                         :noauth-proxy-port "sm.noauthproxy.port"
                         :noauth-proxy-log (DefaultMapKey.
                                             "sm.noauthproxy.log"
                                             "/var/log/tinyproxy/tinyproxy.log")

                         ;binary paths
                         :binary-path (DefaultMapKey.
                                        "sm.gui.binary"
                                        "subscription-manager-gui")
                         :firstboot-binary-path (DefaultMapKey.
                                                  "sm.firstboot.binary"
                                                  "firstboot")

                         ;candlepin/client server information
                         :client-hostname "sm.client1.hostname"
                         :server-hostname "sm.server.hostname"
                         :server-port "sm.server.port"
                         :server-prefix "sm.server.prefix"

                         ;client user information
                         :owner-key "sm.client1.org"
                         :password "sm.client1.password"
                         :password1 "sm.client2.password"
                         :username "sm.client1.username"
                         :username1 "sm.client2.username"
                                        ; cockpit user informations
                         :cockpit-username "sm.client1.cockpit.username"
                         :cockpit-password "sm.client1.cockpit.password"
                         :cockpit-username1 "sm.client2.cockpit.username"
                         :cockpit-password1 "sm.client2.cockpit.password"

                         ;; candlepin server admin informations
                         :admin-username "sm.server.admin.username"
                         :admin-password "sm.server.admin.password"

                         ; rhsm.conf values
                         :rhsm-base-url (DefaultMapKey. "sm.rhsm.baseUrl" "https://cdn.redhat.com")
                         :rhsm-consumer-cert-dir (DefaultMapKey. "sm.rhsm.consumerCertDir" "/etc/pki/consumer")
                         :sm-rhsm-entitlement-cert-dir (DefaultMapKey.
                                                         "sm.rhsm.entitlementCertDir" "/etc/pki/entitlement")
                         :sm-rhsm-product-cert-dir (DefaultMapKey. "sm.rhsm.productCertDir" "/etc/pki/product")

                         ;ssh
                         :ssh-key-passphrase "sm.sshkey.passphrase"
                         :ssh-key-private (DefaultMapKey. "sm.sshkey.private" ".ssh/id_auto_dsa")
                         :ssh-timeout "sm.sshEmergencyTimeoutMS"
                         :ssh-user (DefaultMapKey. "sm.ssh.user" "testuser")
                         :ssh-super-user (DefaultMapKey. "sm.ssh.super-user" "root")

                         ;sources
                         :ldtpd-source-url (DefaultMapKey. "sm.ldtpd.sourceUrl" nil)

                         ;logging
                         :rhsm-log "/var/log/rhsm/rhsm.log"
                         :ldtp-log "/var/log/ldtpd/ldtpd.log"
                         })]
       (merge m (property-map
                 {:ldtp-url (DefaultMapKey.
                              "sm.ldtp.url"
                              (str "http://" (m :client-hostname) ":4118"))
                  :server-url (DefaultMapKey.
                                "sm.server.url"
                                (str "https://"
                                     (m :server-hostname)
                                     ":"
                                     (m :server-port)
                                     (with-starting-slash (m :server-prefix))))}))))

(def config (atom {}))
(def clientcmd (atom nil))
(def cli-tasks (atom nil))
(def candlepin-tasks (atom nil))
(def auth-proxyrunner (atom nil))
(def noauth-proxyrunner (atom nil))
(def candlepin-runner (atom nil))
(def clientcmd-testuser (atom nil))

(defn init []
  (TestScript.) ;;sets up logging, reads properties
  (swap! config merge (get-properties))
  ;; client command runner to run ssh commands on the rhsm client box
  (reset! clientcmd (SSHCommandRunner. (@config :client-hostname)
                                       (@config :ssh-super-user)
                                       (@config :ssh-key-private)
                                       (@config :ssh-key-passphrase)
                                       nil))
  (when (@config :ssh-timeout)
    (.setEmergencyTimeout @clientcmd (Long/valueOf (@config :ssh-timeout))))
  ;; client command runner to run file and other tasks on rhsm client box
  (reset! cli-tasks (SubscriptionManagerTasks. @clientcmd))
  (.initializeFieldsFromConfigFile @cli-tasks)
  ;; command runner to run ssh commands on the squid proxy server (with auth)
  (reset! auth-proxyrunner (SSHCommandRunner. (@config :basicauth-proxy-hostname)
                                              (@config :ssh-super-user)
                                              (@config :ssh-key-private)
                                              (@config :ssh-key-passphrase)
                                              nil))
  (when (@config :ssh-timeout)
    (.setEmergencyTimeout @auth-proxyrunner (Long/valueOf (@config :ssh-timeout))))
  ;; command runner to run ssh commands on the proxy server (no authentication)
  (reset! noauth-proxyrunner (SSHCommandRunner. (@config :noauth-proxy-hostname)
                                                (@config :ssh-super-user)
                                                (@config :ssh-key-private)
                                                (@config :ssh-key-passphrase)
                                                nil))
  (when (@config :ssh-timeout)
    (.setEmergencyTimeout @noauth-proxyrunner (Long/valueOf (@config :ssh-timeout))))
  ;; command runner to run ssh commands on the candlepin server
  ;; this is inside a try/catch block as a command runner cannot
  ;; be created for a stage-candlepin server
  (try
    (reset! candlepin-runner (SSHCommandRunner. (@config :server-hostname)
                                                (@config :ssh-super-user)
                                                (@config :ssh-key-private)
                                                (@config :ssh-key-passphrase)
                                                nil))
    (when (@config :ssh-timeout)
      (.setEmergencyTimeout @candlepin-runner (Long/valueOf (@config :ssh-timeout))))
    (catch Exception e
      (reset! candlepin-runner nil)))
  ;; command runner to run ssh commands on rhsm client box as non-root user
  (comment
    (reset! clientcmd-testuser (SSHCommandRunner. (@config :client-hostname)
                                                  (@config :ssh-user)
                                                  (@config :ssh-key-private)
                                                  (@config :ssh-key-passphrase)
                                                  nil))
    (when (@config :ssh-timeout)
      (.setEmergencyTimeout @clientcmd-testuser (Long/valueOf (@config :ssh-timeout)))))
  ;; instantiate CandlepinTasks
  (reset! candlepin-tasks (CandlepinTasks.))
  ;; turn off SSL Checking so rest API works
  ;(SSLUtilities/trustAllHostnames)
  ;(SSLUtilities/trustAllHttpsCertificates)
  )
