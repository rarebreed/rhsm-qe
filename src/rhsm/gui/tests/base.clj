(ns rhsm.gui.tests.base
  (:use [test-clj.testng :only (gen-class-testng)]
        [rhsm.gui.tasks.tasks]
        rhsm.gui.tasks.tools)
  (:require [rhsm.gui.tasks.test-config :as config]
            [clojure.tools.logging :as log]
            [mount.core :as mount])
  (:import [org.testng.annotations BeforeSuite
            AfterSuite]
           [rhsm.base SubscriptionManagerCLITestScript]
           org.testng.SkipException))

;(def user "testuser")

(defn run-and-assert
  "Wrapper around run-command that throws a SkipException if the command fails"
  [command]
  (let [result (run-command command)]
    (if (not (bash-bool (:exitcode result)))
      (throw (SkipException. (str "Command '" command "' failed! Skipping suite."))))))

(defn update-ldtpd
  "This function updates ldtpd on the client"
  [url]
  (when url
    (if (= "RHEL5" (get-release))
      (let [;path (str "/home/" user "/bin/ldtpd")
            path (str "/root/bin/ldtpd")]
        (run-and-assert (str "wget " url " -O " path))
        (run-and-assert (str "chmod +x " path))))))

(defn restart-vnc
  "function that restarts the vnc server"
  []
  (if (= "RHEL7" (get-release))
    (do (run-command "systemctl stop vncserver@:2.service")
        (. Thread (sleep 5000))
        ;;yup systemd sucks
        (run-command "killall -9 Xvnc")
        (run-command "rm -f /tmp/.X2-lock; rm -f /tmp/.X11-unix/X2")
        (run-and-assert "systemctl start vncserver@:2.service"))
    (do
      (run-command "service vncserver stop")
      (. Thread (sleep 5000))
      (run-command "rm -f /tmp/.X2-lock; rm -f /tmp/.X11-unix/X2")
      (run-and-assert "service vncserver start")))
  (run-command "echo -n \"Waiting for startup.\" & until $(netstat -lnt | awk '$6 == \"LISTEN\" && $4 ~ \".4118\"' | grep -q .); do echo -n \".\"; sleep 2; done; echo")
  (. Thread (sleep 10000))
  (comment (if (= "RHEL7" (get-release))
             (do
               (run-command "gsettings set org.gnome.settings-daemon.plugins.a11y-settings active false")
               (run-command "gsettings set org.gnome.desktop.interface toolkit-accessibility true")))))

(defn open-connection-to-candlepin
  "The function runs 'iptables' to open connection to a server."
  ;; iptables -D OUTPUT -d subscription.rhsm.stage.redhat.com -j REJECT
  ;; iptables: No chain/target/match by that name.
  ([runner]
   ;; remove all this task related REJECT rules from OUTPUT chain
   (let [iptables-cmd (format "iptables -D OUTPUT -d %s -j REJECT" (conf-file-value "hostname"))]
     (loop [result-of-del-cmd (-> iptables-cmd (run-command :runner runner) :stderr)
            iteration 1]
       (if-not (or (.contains result-of-del-cmd "No chain/target/match by that name.") (>= iteration 10))
         (recur (-> iptables-cmd (run-command :runner runner) :stderr) (inc iteration))))))
  ([] (open-connection-to-candlepin @config/clientcmd)))

(defn close-connection-to-candlepin
  "The function runs 'iptables' to close the connection to a candlepin server."
  ([runner]
   ;; iptables -A OUTPUT -d subscription.rhsm.stage.redhat.com -j REJECT
   ;; openssl s_client -debug -connect subscription.rhsm.stage.redhat.com:443
   ;; Connection refused
   (let [iptables-cmd (format "iptables -A OUTPUT -d %s -j REJECT" (conf-file-value "hostname"))
         ;; connection-test (format "openssl s_client -debug -host %s -port %s"
         ;;                         (conf-file-value "hostname")
         ;;                         (conf-file-value "port"))
         ]
     (run-command iptables-cmd :runner runner)
     ;;(verify (-> connection-test run-command :stderr (.contains "Connection refused")))
     ))
  ([] (close-connection-to-candlepin @config/clientcmd)))

(defn ^{BeforeSuite {:groups ["setup"]}}
  startup [_]
  (try
    (println "----------------------------------- going to setup suite ---------------------------------------")
    (let [cliscript (SubscriptionManagerCLITestScript.)]
      (.setupBeforeSuite cliscript))
    (println "----------------------------------- going to config init ---------------------------------------")
    (config/init)
    (assert-valid-testing-arch)
    (println "----------------------------------- going to update ldtpd ---------------------------------------")
    (update-ldtpd (:ldtpd-source-url @config/config))
    (println "----------------------------------- going to restart vnc ---------------------------------------")
    (restart-vnc)
    (println "----------------------------------- going to open connection to candlepin on the client machine ---------------------------------------")
    (open-connection-to-candlepin)
    (println "----------------------------------- going to open connection to candlepin on the proxy server ---------------------------------------")
    (open-connection-to-candlepin @config/noauth-proxyrunner)

    (println "----------------------------------- before connect ---------------------------------------")
    (connect)
    (println "----------------------------------- before mount/start ---------------------------------------")
    (mount/start)
    (println "----------------------------------- before start-app ---------------------------------------")
    (start-app)
    (catch Exception e
      (reset! (skip-groups :suite) true)
      (throw e))))

(defn ^{AfterSuite {:groups ["setup"]}}
  killGUI [_]
  (kill-app)
  (log/info "Contents of ldtpd.log:")
  (log/info (:stdout
             (run-command
              ;(str "cat /home/" user "/ldtpd/ldtpd.log")
              (str "cat /root/ldtpd/ldtpd.log")))))

(gen-class-testng)
