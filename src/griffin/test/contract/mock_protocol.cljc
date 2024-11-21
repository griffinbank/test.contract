(ns griffin.test.contract.mock-protocol)

(defprotocol State
  (init-state [this i]
    "Update the state given a mock is starting with initial state i")
  (swap-state [this f]
    "Calls f, the method, with the current mock state, replacing with the return value. NOTE: f may be called multiple times so should be free of side effects."))
