(ns griffin.test.contract.protocol
  (:refer-clojure :exclude [methods get-method]))

(defprotocol Return
  :extend-via-metadata true
  (spec [this]
    "A spec to validate the implementation's return value against")
  (next-state [this]
    "The next state value after this method returns")
  (gen [this]
    "A generator for mock return values. If not supplied, `spec` must gen"))

(defprotocol Method
  :extend-via-metadata true
  (var [this]
    "the protocol method var this describes")
  (return [this state args]
    "return an instance of Return")
  (requires [this state]
    "Return truthy if it is valid to generate a call to this method in the current state.")
  (args [this state]
    "Return a generator for arguments to call the method in the current state. Do not include `this`")
  (precondition [this state args]
    "Return truthy if it is valid to call this method with these args in the current state."))

(defprotocol Model
  :extend-via-metadata true
  (protocols [this]
    "The set of protocols this models")
  (methods [this]
    "Return all Methods")
  (get-method [this v]
    "Given a protocol method var, return an instance of Method")
  (initial-state [this]
    "Return a state value")
  (gen-method [this state]
    "Return a generator for selecting the next method to call")
  (cleanup [this implementation calls]
    "Called at the end of a test, with the implementation under test, and the set of calls that were run"))
