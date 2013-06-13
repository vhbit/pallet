(ns pallet.core.operations-test
  (:refer-clojure :exclude [delay])
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script]]
   [pallet.algo.fsmop :refer [failed? operate]]
   [pallet.api :refer [group-spec plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :refer [nodes]]
   [pallet.compute.node-list :refer [node-list-service make-localhost-node]]
   [pallet.core.operations :refer :all]
   [pallet.core.primitives :refer [phase-errors]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.node :refer [group-name]]
   [pallet.test-utils :refer [clj-action make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(clj-action [session]
       (clojure.tools.logging/info (format "Seenfn %s %s" name @seen))
       (is (not @seen))
       (reset! seen true)
       [true session])
     seen?]))

(defn count-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [count-atom (atom 0)
        get-count (fn [] @count-atom)]
    [(clj-action [session]
       (clojure.tools.logging/info (format "count-fn %s %s" name @count-atom))
       (swap! count-atom inc)
       [true session])
     get-count]))

(deftest lift-test
  (let [user (assoc *admin-user* :no-sudo true)]
    (testing "lift a phase for a node in a group"
      (let [compute (make-localhost-compute)
            group (group-spec
                   (group-name (first (nodes compute)))
                   :phases {:p (plan-fn (exec-script "ls /"))})
            node-set @(operate (group-nodes compute [group]))
            op (operate (lift node-set {} {:user user} [:p] {}))
            {:keys [plan-state results targets]} @op]
        (is (nil? (phase-errors op)))
        (is (not (failed? op)))
        (is (= 1 (count targets)))
        (is (= 1 (count (:node-values plan-state))))
        (is (some
             (partial re-find #"bin")
             (->> (mapcat :result results) (map :out))))))
    (testing "lift two phases for a node in a group"
      (let [compute (make-localhost-compute)
            [localf seen?] (seen-fn "lift-test")
            group (group-spec
                   (group-name (first (nodes compute)))
                   :phases {:p (plan-fn (exec-script "ls /"))
                            :p2 (plan-fn (localf))})
            node-set @(operate (group-nodes compute [group]))
            op (operate (lift node-set {} {:user user} [:p :p2] {}))
            {:keys [plan-state results targets]} @op]
        (is (not (failed? op)))
        (is (= 1 (count targets)))
        (is (= 2 (count (:node-values plan-state))))
        (testing "results"
          (is (= 2 (count results)))
          (is (= #{:p :p2} (set (map :phase results)))))
        (let [r (mapcat :result results)]
          (is (re-find #"bin" (-> r first :out)))
          (is (= true (second r))))))
    (testing "lift two phases for two nodes in a group"
      (let [compute (node-list-service
                     [(make-localhost-node) (make-localhost-node)])
            [localf get-count] (count-fn "lift-test")
            group (group-spec
                   (group-name (first (nodes compute)))
                   :phases {:p (plan-fn (exec-script "ls /"))
                            :p2 (plan-fn (localf))})
            node-set @(operate (group-nodes compute [group]))
            op (operate (lift node-set {} {:user user} [:p :p2] {}))
            {:keys [plan-state results targets]} @op]
        (is (not (failed? op)) "operation failed")
        (is (= 2 (count targets)))
        (is (= 2 (get-count)))
        (testing "results"
          (is (= 4 (count results)))
          (is (= #{:p :p2} (set (map :phase results)))))
        (let [r (mapcat :result results)]
          (is (re-find #"bin" (-> r first :out)))
          (is (= true (nth r 3))))))))
