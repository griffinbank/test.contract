(ns griffin.test.contract-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [griffin.test.contract :as c]))

(defprotocol RemoteAPI
  :extend-via-metadata true
  (create-file [this file])
  (file-exists? [this file]))

(def model
  (c/model
   {:protocols #{RemoteAPI}
    :methods [(c/method #'create-file
                        (fn [state [file]]
                          (if (not (get-in state [:files file]))
                            (c/return #{:ok}
                                      :next-state (update state :files conj file))
                            (c/return #{:error/file-exists}
                                      :next-state state)))
                        :args (fn [_state] (gen/tuple gen/string)))
              (c/method #'file-exists?
                        (fn [state [file]]
                          (let [exists? (boolean (get-in state [:files file]))]
                            (c/return (s/with-gen (fn [x] (= exists? x))
                                        (fn [] (gen/return exists?)))
                                      :next-state state)))
                        :args (fn [_state] (gen/tuple gen/string)))]

    :initial-state (fn []
                     {:files #{}})}))

(defn good-impl []
  (let [state (ref {:files #{}})]
    (reify
      RemoteAPI
      (create-file [_this f]
        (dosync
         (if (not (get (:files @state) f))
           (do
             (commute state update :files conj f)
             :ok)
           :error/file-exists)))
      (file-exists? [_this f]
        (boolean (get (:files @state) f))))))

(defn bad-impl []
  (reify
    RemoteAPI
    (create-file [_this _f]
      :ok)
    (file-exists? [_this _f]
      false)))

(deftest model-works
  (is (:pass? (tc/quick-check 100 (c/test-model model)))))

(deftest mocks-work
  (let [mock (c/mock model)]
    (is (= :ok (create-file mock "hello")))
    (is (= :error/file-exists (create-file mock "hello")))))

(defn state-impl-is-thread-safe [state]
  (let [mock (c/mock model :mock-state state)]
    (is (= {true 100}
           (frequencies
            (map deref
                 (doall (map (fn [fname]
                               (future
                                 (create-file mock fname)
                                 (file-exists? mock fname)))
                             (range 100)))))))))

(deftest mock-state-is-thread-safe
  (doseq [state [(c/ephemeral-state) (c/ref-state (ref {}))]]
    (testing state
      (state-impl-is-thread-safe state))))

(deftest verify-works
  (let [ret (tc/quick-check 100 (c/verify model good-impl))]
    (is (:pass? ret) ret)))

(deftest verify-catches-errors
  (let [ret (tc/quick-check 100 (c/verify model bad-impl))]
    ;; (println "ret:" ret)
    (is (find ret :pass?) ret)
    (is (false? (:pass? ret)) ret)))

(deftest test-proxy
  (let [good-mock (c/test-proxy model (good-impl))]
    (is (= :ok (create-file good-mock "/foo")))
    (is (= :error/file-exists (create-file good-mock "/foo"))))

  (let [bad-mock (c/test-proxy model (bad-impl))]
    (is (= :ok (create-file bad-mock "/foo")))
    (is (thrown? Exception (create-file bad-mock "/foo")))))
