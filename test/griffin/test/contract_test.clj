(ns griffin.test.contract-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            [griffin.test.contract :as c]
            [griffin.test.contract.protocol :as p]))

(defprotocol RemoteAPI
  :extend-via-metadata true
  (create-file [this file])
  (delete-file [this file])
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
              (c/method #'delete-file
                        (fn [state [file]]
                          (c/return #{:ok}
                                    :next-state (update state :files disj file)))
                        :requires (fn [state] (seq (:files state)))
                        :precondition (fn [state [file]] (contains? (:files state) file))
                        :args (fn [state] (gen/tuple (gen/elements (:files state)))))
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
      (delete-file [_this f]
        (dosync
          (commute state update :files disj f)
          :ok))
      (file-exists? [_this f]
        (boolean (get (:files @state) f))))))

(defn bad-impl []
  (reify
    RemoteAPI
    (create-file [_this _f]
      :ok)
    (delete-file [_this _f]
      (throw (UnsupportedOperationException. "method not implemented")))
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

(deftest verify-num-calls-opt-works
  (let [num-calls 123
        orig-gen-calls c/gen-calls]
    (with-redefs [;; remove non-determinism in gen/large-integer* so that gen-calls always returns the max
                  gen/large-integer* (fn [{:keys [min max]}]
                                       (gen/return max))
                  c/gen-calls (fn [& args]
                                (gen/fmap (fn [calls]
                                            (is (= num-calls (count calls)))
                                            calls)
                                          (apply orig-gen-calls args)))]
      (is (:pass? (tc/quick-check 1 (c/verify model good-impl :num-calls num-calls)))))))

(deftest test-proxy
  (let [good-mock (c/test-proxy model (good-impl))]
    (is (= :ok (create-file good-mock "/foo")))
    (is (= :error/file-exists (create-file good-mock "/foo"))))

  (let [bad-mock (c/test-proxy model (bad-impl))]
    (is (= :ok (create-file bad-mock "/foo")))
    (is (thrown? Exception (create-file bad-mock "/foo")))))

(deftest shrinking-produces-valid-calls
  (->> (rose/seq (gen/call-gen (c/gen-calls model (p/initial-state model)) (random/make-random) 100))
       (take 1000)
       (run! (fn [calls]
               (reduce (fn [state {:keys [method args return]}]
                         (is (p/requires method state) "shrunk state obeys requires")
                         (is (p/precondition method state args) "shrunk state obeys preconditions")
                         (let [state' (p/next-state (p/return method state args))]
                           (is (= state' (p/next-state return)) "shrunk state is correct")
                           state'))
                       (p/initial-state model)
                       calls)))))

(deftest shrinking-finds-smallest-case
  (let [ret (tc/quick-check 100 (c/verify model bad-impl))
        smallest (first (:smallest (:shrunk ret)))]
    (is (:fail ret) ret)
    (is (:shrunk ret) ret)
    (is smallest (:shrunk ret))
    ;; the smallest possible failing cases for bad-impl all involve 2 calls:
    ;; - create the same file twice
    ;; - create a file then delete it (note the model doesn't let us delete until we've created a file)
    ;; - create a file then ask if it exists
    (is (= 2 (count smallest)) smallest)
    (is (not= smallest (:fail ret)) ret)))
