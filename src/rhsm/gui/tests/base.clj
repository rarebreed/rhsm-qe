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

(defn ^{BeforeSuite {:groups ["setup"]}}
  startup [_]
  (try
    (println "----------------------------------- before suite ---------------------------------------")
    (let [cliscript (SubscriptionManagerCLITestScript.)]
      (.setupBeforeSuite cliscript))
    (println "----------------------------------- before config init ---------------------------------------")
    (config/init)
    (assert-valid-testing-arch)
    (println "----------------------------------- before update ldtpd ---------------------------------------")
    (update-ldtpd (:ldtpd-source-url @config/config))
    (println "----------------------------------- before restart vnc ---------------------------------------")
    (restart-vnc)
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
