(ns rhsm.errors.classification-test
  (:use [slingshot.slingshot :only (try+ throw+)]
        [com.redhat.qe.verify :only (verify)])
  (:require [rhsm.errors.utils :as sut]
            [rhsm.errors.protocols :as prot]
            [rhsm.errors.implementations :as impl]
            [clojure.test :refer [deftest is]])
  (:import org.testng.SkipException))

(deftest skip-exception-failure-level-test
  (is (= :skip-exception (prot/failure-level (new SkipException "some exception")))))

(deftest exception-test
  (is (= :unknown-exception (prot/failure-level (new Exception "some exception in general manner")))))

(deftest unknown-string-test
  (is (= :unknown (prot/failure-level "unknown string"))))

(deftest unknown-integer-test
  (is (= :unknown (prot/failure-level (Integer. 10)))))

(deftest verification-exception-test
  (let [e (try+ (verify false)
                (catch Object e e))]
    (is (= :verification-failure (prot/failure-level e)))))

(deftest network-candlepin-connection-error-test
  (let [e (try+ (throw+  {:type :network-error
                          :msg "Unable to reach the server at jsefler-candlepin6.usersys.redhat.com:8443/candlepin"
                          :log-warning :some-object-reference
                          :cancel :some-object-reference})
                (catch Object e
                  e))]
    (is (= :network-error (prot/failure-level e)))))

(deftest exception-re-risen-test
  (is (thrown? SkipException
               (sut/normalize-exception-types
                (throw+  {:type :network-error
                          :msg "Unable to reach the server at jsefler-candlepin6.usersys.redhat.com:8443/candlepin"
                          :log-warning :some-object-reference
                          :cancel :some-object-reference})))))

(deftest verify-error-passes-throught-test
  (is (thrown? java.lang.AssertionError
               (sut/normalize-exception-types
                (verify false)))))
