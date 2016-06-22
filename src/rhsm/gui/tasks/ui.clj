(ns rhsm.gui.tasks.ui
  (:use  [clojure.string :only [join split capitalize]])
  (:require [gnome.ldtp :as ldtp]
            [mount.core :refer [defstate]]
            [rhsm.gui.tasks.tools :as tt])
  (:import java.util.NoSuchElementException
           [gnome.ldtp Element Tab Window TabGroup]))

(defn same-name "takes a collection of keywords like :registration-settings
and returns a mapping like :registration-settings -> 'Registration Settings'"
  ([coll]
     (same-name identity coll))
  ([word-fn coll]
     (zipmap coll
             (for [keyword coll] (->> (split (name keyword) #"-") (map word-fn) (join " "))))))

(defn text-field [coll]
  (zipmap coll
          (for [keyword coll]
            (->> (conj (split (name keyword) #"-") "text") (map capitalize) (join  " ")))))

(defn define-elements [window m]
  (zipmap (keys m) (for [v (vals m)] (Element. window v))))

(defn define-tabs [tabgroup m]
  (zipmap (keys m) (for [v (vals m)] (Tab. tabgroup v))))

(defn define-windows [m]
  (zipmap (keys m) (for [v (vals m)] (Window. v))))

(def default-windows  {:main-window "Subscription Manager"
                       :about-dialog "About Subscription Manager"
                       :credits-dialog "Credits"
                       :license-dialog "License"
                       :contract-selection-dialog "Contract Selection"
                       :date-selection-dialog "Date Selection"
                       :error-dialog "Error"
                       :facts-dialog "Subscription Manager - Facts"
                       :file-chooser "Select A File"
                       :filter-dialog "Filter Options"
                       :firefox-help-window "frmRedHatSubscriptionManagement-MozillaFirefox"
                       :firstboot-proxy-dialog "Proxy Configuration"
                       :firstboot-window "frm0"
                       :help-dialog "Subscription Manager Manual"
                       :import-dialog "Import Certificates"
                       :information-dialog "Information"
                       ;; renamed in 818238
                                        ;: progress-dialog "Progress Dialog"
                       :proxy-config-dialog "Proxy Configuration"
                       :question-dialog "Question"
                       ;; does not exist anymore? part of the register-dialog
                                        ;: subscribe-system-dialog "Subscribe System"
                       :register-dialog "System Registration"      ;; in RHEL 7.2 is "register_dialog"
                       :search-dialog "Searching"
                       ;; also does not exist anymore > tests have been moved to oldtests folder
                                        ;: subscription-assistant-dialog "Subscription Assistant"
                       :subscription-redemption-dialog "Subscription Redemption"
                       :system-preferences-dialog "System Preferences"
                       :warning-dialog "Warning"
                       :repositories-dialog "manage_repositories_dialog"
                       :subscription-attachment-dialog "Subscription Attachment"})

(defn version-dispatcher [release]
  (let [version (:family release)
        valid #{"RHEL5" "RHEL6" "RHEL7"}]
    (some valid [version])))

(defmulti windows-map-by-rhel-version version-dispatcher)

(defmethod windows-map-by-rhel-version nil [release]
  (throw (Exception. (format "Version %s is not implemented." (:family release)))))

(defmethod windows-map-by-rhel-version "RHEL5" [release] default-windows)
(defmethod windows-map-by-rhel-version "RHEL6" [release] default-windows)
(defmethod windows-map-by-rhel-version "RHEL7" [release]
  (assoc default-windows :register-dialog "register_dialog"))

(defstate windows :start (define-windows (windows-map-by-rhel-version (tt/get-release true))))

(defstate elements
  :start (merge
    (define-elements (windows :main-window)
      (merge (same-name capitalize [:about
                                    :all-available-subscriptions
                                    :all-subscriptions-view ;; the table in All Available Subscriptions tab
                                    :attach
                                    :calendar
                                    :configure-proxy
                                    :filters
                                    :getting-started
                                    :glossary
                                    :help
                                    :installed-view
                                    :my-installed-products
                                    :my-subscriptions
                                    :my-subscriptions-view
                                    :online-documentation
                                    :overall-status
                                    :preferences
                                    :registration-settings
                                    :remove
                                    :search                 ;; the Update button in All Available Subscriptions
                                    :update-certificates
                                    :view-system-facts
                                    :repositories
                                    :status-details-text])
                    {:date-entry "date-entry"
                     :register-system "Register System"
                     :redeem "Redeem a Subscription"
                     :import-certificate "Import Cert"
                     :unregister-system "Unregister"
                     :update-certificates "Update"
                     :no-subscriptions-label "no_subs_label"
                     ;; now called auto-attach
                     ;:autosubscribe "Auto-subscribe"
                     :auto-attach "Auto-attach"
                     }
                    ;dynamic text fields for details sections:
                    (text-field [:arch
                                 :certificate-status
                                 :installed-subscription
                                 :product
                                 :support-type
                                 :support-level
                                 :provides-management
                                 :account
                                 :providing-subscriptions
                                 ;; changed BZ 826735
                                 :start-end-date
                                 :subscription
                                 :subscription-type
                                 :all-available-support-level-and-type
                                 :all-available-subscription-type
                                 :all-available-subscription])
                    {:all-available-SKU-text "All Available SKU Text"
                     :product-id "Product ID Text"
                     ;;stacking id removed in BZ 821544
                     :stacking-id "Stacking ID Text"
                     :contract-number "Contract Number Text"
                     :bundled-products "Bundeled Products Table"
                     :all-available-bundled-products "All Available Bundled Product Table"}))
                    {:main-tabgroup (TabGroup. (windows :main-window) "ptl0")}
    (define-elements (windows :register-dialog)
        {:redhat-login "account_login"
         :password "account_password"
         :password-tip "registration_tip_label"
         :system-name "consumer_name"
         :skip-autobind "auto_bind"
         :register "register_button"
         :register-cancel "cancel_button"  ;; PROBABLY doesn't exist anymore
         :register-close "close_button"
         :owner-view "owner_treeview"
         :registering "Registering"
         :register-proxy-conf "proxy_button"
         :register-server "server_entry"
         :default-server "default_button"
         ;;activation-key section
         :activation-key-checkbox "I will use an Activation Key"
         :organization "organization_entry"
         :activation-key "activation_key_entry"
         :activation-system-name "consumer_entry"
         ;; sla/auto-attach stuff below
         :sla-attach "Attach"
         :sla-forward "Forward"
         :sla-next "Next"
         :sla-cancel "Cancel"
         :sla-back "Back"
         :auto-attach-subscriptions-table "Selected Subscriptions Table"
         ;;:sla-selection-table ""  ** waiting for table name Bug 1005329 **
         })
    (define-elements (windows :question-dialog)
      (same-name capitalize [:yes
                             :no ]))
    (define-elements (windows :facts-dialog)
      {:facts-view "facts_view"
       :close-facts "close_button"
       :update-facts "Update Facts"
       :update-time "Update Time"
       :facts-org "Organization Value"
       :facts-sys-id "System Identity Value"
       ;:facts-org-id "Organization ID Value"
       })
    (define-elements (windows :error-dialog)
      {:ok-error "OK"
       :error-msg "lbl[A-Za-z]*"})
    (define-elements (windows :contract-selection-dialog)
      {:contract-selection-table "SLA Selection Table"
       :cancel-contract-selection "Cancel"
       :attach-contract-selection "Attach"})
    (define-elements (windows :proxy-config-dialog)
       (merge (same-name capitalize [:proxy-checkbox
                                     :authentication-checkbox
                                     :password-text
                                     :username-text])
              {:close-proxy "Save Button"
               :cancel-proxy "Cancel Button"
               :test-connection "Test Connection Button"
               :connection-status "connectionStatusLabel"
               :proxy-location "Proxy Location Text"}))
    (define-elements (windows :information-dialog)
      {:info-msg "lbl[A-Za-z]*"
       :info-ok "OK"})
    (define-elements (windows :warning-dialog)
      {:warn-ok "OK"
       :warn-cancel "Cancel"})
    (define-elements (windows :filter-dialog)
      (merge (same-name capitalize [:match-system
                                    :match-installed
                                    :do-not-overlap])
             {:contain-the-text "Text in Subscription"
              :clear-filters "Clear"
              :close-filters "Close"}))
    (define-elements (windows :firstboot-window)
      {:firstboot-back "Back"
       :firstboot-forward "Forward"
       :firstboot-finish "Finish"
       :license-yes "Yes, I agree to the License Agreement"
       :license-no "No, I do not agree"
       :register-now "Yes, I'd like to register now."
       :register-later "No, I prefer to register at a later time."
       :register-rhsm "Red Hat Subscription Management"
       :register-rhn "Red Hat Network (RHN) Classic"
       :register-satellite "An RHN Satellite or RHN Proxy"
       :satelite-location "Location:"
       :firewall "Firewall"
       :software-updates "Set Up Software Updates"
       :firstboot-proxy-config "Proxy Setup Button"
       :firstboot-server-entry "server_entry"
       :firstboot-server-default "default_button"
       :firstboot-activation-checkbox "I will use an Activation Key"
       :firstboot-user "account_login"
       :firstboot-pass "account_password"
       :firstboot-autosubscribe "auto_bind"
       :firstboot-system-name "consumer_name"
       :firstboot-owner-table "tbl0"})
    (define-elements (windows :firstboot-proxy-dialog)
      {:firstboot-proxy-checkbox "Proxy Checkbox"
       :firstboot-proxy-location "Proxy Location:"
       :firstboot-auth-checkbox "Authentication Checkbox"
       :firstboot-proxy-user "Proxy Username"
       :firstboot-proxy-pass "Proxy Password"
       :firstboot-proxy-close "Save Button"
       :firstboot-proxy-cancel "Cancel Button"
       :firstboot-proxy-test "Test Connection Button"})
    (define-elements (windows :import-dialog)
      {:text-entry-toggle "Type a file name"
       :import-cert "Import"
       :import-cancel "Cancel"
       :enter-location "Enter Location"})
    (define-elements (windows :system-preferences-dialog)
      {:close-system-prefs "Close"
       :autoheal-checkbox "autoheal_checkbox"
       :service-level-dropdown "sla_selection_combobox"
       :release-dropdown "release_selection_combobox"})
    (define-elements (windows :date-selection-dialog)
      {:today "Today"})
    (define-elements (windows :subscription-redemption-dialog)
      {:email-address "Email Address Text"
       :redeem-cancel "Cancel"
       :redeem "Redeem"})
    (define-elements (windows :about-dialog)
      {;;these info fields are meant to be used by running gettext on them
       :python-rhsm-version "python-rhsm version*"
       :rhsm-service-version "subscription management service version*"
       :rhsm-version "subscription manager version*"
       :next-system-check "Next System Check-in*"
       :license "License"
       :credits "Credits"
       :close-about-dialog "Close"})
    (define-elements (windows :credits-dialog)
      {;;these info fields are meant to be used by running gettext on them
       :close-credits-dialog "Close"})
    (define-elements (windows :license-dialog)
      {;;these info fields are meant to be used by running gettext on them
       :close-license-dialog "Close"})
    (define-elements (windows :repositories-dialog)
      {:repo-table "Repository View"
       :repo-message "No repositories are available*"
       :close-repo-dialog "close_button"
       :repo-remove-override "remove_all_overrides_button"
       :base-url "SKU Text"
       :repo-name "Subscription Text"
       :gpg-check-text "gpgcheck_readonly"
       :gpg-check-combobox "gpgcheck_combobox"
       :gpg-check-edit "gpgcheck_edit_button"
       :gpg-check-remove "gpgcheck_remove_button"
       :apply "Apply"})
    (define-elements (windows :subscription-attachment-dialog)
                     {:drop-down "Standard"                 ;; comboselect box
                      :attach-next "register_button"        ;; the Next button
                      :attach-back "back_button"            ;; the Back button
                      :attach-close "close_button"})))      ;; the Close button


(defstate tabs
  :start (define-tabs (elements :main-tabgroup)
            (same-name capitalize [:all-available-subscriptions
                                   :my-subscriptions
                                   :my-installed-products])))

(defstate all-elements :start (merge windows elements tabs))

;; let clojure keywords represent locators.  When you call locator on
;; a keyword, it looks up that keyword in the all-elements map.

(extend-protocol ldtp/LDTPLocatable
  clojure.lang.Keyword
  (locator [this] (let [locatable (get all-elements this)]
                    (if-not locatable
                      (throw (IllegalArgumentException. (str "Key not found in UI mapping: " this))))
                    (ldtp/locator locatable))))
