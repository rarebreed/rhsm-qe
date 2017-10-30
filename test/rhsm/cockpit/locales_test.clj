(ns rhsm.cockpit.locales-test
  (:require  [clojure.test :refer :all]
             [rhsm.cockpit.locales :as l]
             [clojure.xml :as xml]
             [clojure.zip :as zip]
             [clojure.java.io :as io]
             [clojure.data.zip.xml :as zip-xml]
             ))

(use-fixtures :once (fn [f]
                      (l/load-catalog)
                      (f)))

(deftest get-phrase-test
  (is (= "Register" (l/get-phrase "Register" :locale "en_US.UTF-8")))
  (is (= "Status: System isn't registered"
         (l/get-phrase "Status: System isn't registered" :locale "en_US.UTF-8" )))
  (is (= "Details" (l/get-phrase "Details" :locale "en_US.UTF-8")))
  (is (= "Installed Products" (l/get-phrase "Installed Products" :locale "en_US.UTF-8")))
  (is (= "Retrieving subscription status..." (l/get-phrase "Retrieving subscription status..." :locale "en_US.UTF-8")))
  (is (= "Updating" (l/get-phrase "Updating" :locale "en_US.UTF-8"))))
