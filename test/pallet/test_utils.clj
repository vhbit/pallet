(ns pallet.test-utils
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [clojure.tools.logging]
   [pallet.action :refer [declare-action implement-action]]
   [pallet.action-plan :as action-plan]
   [pallet.actions-impl :as actions-impl]
   [pallet.api :refer [group-spec server-spec]]
   [pallet.common.context :refer [throw-map]]
   [pallet.common.deprecate :as deprecate]
   [pallet.compute.node-list :as node-list]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.execute :as execute]
   [pallet.execute :refer [target-flag?]]
   [pallet.script :as script]
   [pallet.session.verify :refer [add-session-verification-key]]
   [pallet.stevedore :as stevedore]
   [pallet.utils :refer [apply-map]]))

(defmacro with-private-vars [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context.  From users mailing
list, Alan Dipert and MeikelBrandmeyer."
  `(let ~(reduce #(conj %1 %2 `@(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(def dev-null
  (proxy [java.io.OutputStream] []
    (write ([i-or-bytes])
           ([bytes offset len]))))

(defmacro suppress-output
  "Prevent stdout to reduce test log noise"
  [& forms]
  `(binding [*out* (io/writer dev-null)]
    ~@forms))

(defmacro suppress-err
  "Prevent stdout to reduce test log noise"
  [& forms]
  `(binding [*err* (io/writer dev-null)]
    ~@forms))

(def null-print-stream
  (java.io.PrintStream. dev-null))

(defn no-output-fixture
  "A fixture for no output from tests"
  [f]
  (let [out# System/out]
    (System/setOut null-print-stream)
    (try
      (f)
      (finally (System/setOut out#)))))


(defmacro ^{:requires [actions-impl/*script-location-info*]}
  with-location-info
  "A scope for enabling or disabling location info"
  [b & body]
  `(binding [actions-impl/*script-location-info* ~b]
     ~@body))

(def no-location-info
  (fn [f] (with-location-info false (f))))

(def no-source-line-comments
  (fn [f] (stevedore/with-source-line-comments false (f))))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(def ubuntu-session {:server {:image {:os-family :ubuntu}}})
(def centos-session {:server {:image {:os-family :centos}}})

(defn with-ubuntu-script-template
  [f]
  "A test fixture for selection ubuntu as the script context"
  (script/with-script-context [:ubuntu]
    (f)))

(defn with-bash-script-language
  [f]
  "A test fixture for selection bash as the output language"
  (stevedore/with-script-language :pallet.stevedore.bash/bash
    (f)))

(defn with-no-source-line-comments
  [f]
  "A test fixture to remove source line comments"
  (stevedore/with-source-line-comments nil
    (f)))

(defn with-null-defining-context
  "A test fixture for binding null context"
  [f]
  (binding [action-plan/*defining-context* nil]
    (f)))

(defn make-node
  "Simple node for testing"
  [node-name & {:as options}]
  (apply-map
   node-list/make-node
   node-name
   (:group-name options)
   (:ip options "1.2.3.4")
   (:os-family options :ubuntu)
   (dissoc options :group-name :ip :os-family)))

(defn make-localhost-node
  "Simple localhost node for testing"
  [& {:as options}]
  (apply-map node-list/make-localhost-node options))

(defn make-localhost-compute
  [& {:as options}]
  (node-list/node-list-service
   [(apply-map make-localhost-node options)]))

(defmacro build-resources
  "Forwarding definition"
  [& args]
  `(do
     (require 'pallet.build-actions)
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.test-utils/build-resources
       'pallet.build-actions/build-actions))
     ((resolve 'pallet.build-actions/build-actions) ~@args)))

(defn test-session
  "Build a test session"
  [& components]
  (add-session-verification-key
   (reduce
    merge
    {:user *admin-user* :server {:node (make-node :id)}}
    components)))

(defn server
  "Build a server for the session map"
  [& {:as options}]
  (apply server-spec (apply concat options)))

(defn target-server
  "Build the target server for the session map"
  [& {:as options}]
  {:server (apply server-spec (apply concat options))})

(defn group
  "Build a group for the session map"
  [name & {:as options}]
  (apply group-spec name (apply concat options)))

(defn target-group
  "Build the target group for the session map"
  [name & {:as options}]
  {:group (apply group-spec name (apply concat options))})

(defmacro redef
  [ [& bindings] & body ]
  (if (find-var 'clojure.core/with-redefs)
    `(with-redefs [~@bindings] ~@body)
    `(binding [~@bindings] ~@body)))

(defmacro clj-action
  "Creates a clojure action with a :direct implementation."
  {:indent 1}
  [args & impl]
  (let [action-sym (gensym "clj-action")]
    `(let [action# (declare-action '~action-sym {})]
       (implement-action action# :direct
         {:action-type :fn/clojure :location :origin}
         ~args
         [(fn ~action-sym [~(first args)] ~@impl) ~(first args)])
       action#)))

(defmacro script-action
  "Creates a script action with a :direct implementation."
  {:indent 1}
  [args & impl]
  (let [action-sym (gensym "clj-action")]
    `(let [action# (declare-action '~action-sym {})]
       (implement-action action# :direct
         {:action-type :script :location :target}
         ~args
         ~@impl)
       action#)))

(def
  ^{:doc "Verify that the specified flag is set for the current target."}
  verify-flag-set
  (clj-action
    [session flag]
    (when-not (target-flag? session flag)
      (throw-map
       (format "Verification that flag %s was set failed" flag)
       {:flag flag}))
    [flag session]))

(def
  ^{:doc "Verify that the specified flag is not set for the current target."}
  verify-flag-not-set
  (clj-action
   [session flag]
  (when (target-flag? session flag)
    (throw-map
     (format "Verification that flag %s was not set failed" flag)
     {:flag flag}))
  [flag session]))

;;; Actions
;; (def ^{:doc "An action to set parameters"}
;;   parameters
;;   (clj-action [session & {:as keyvector-value-pairs}]
;;     [keyvector-value-pairs
;;      (assoc session
;;        :parameters (reduce
;;                     #(apply assoc-in %1 %2)
;;                     (:parameters session)
;;                     keyvector-value-pairs))]))

(defn bash
  "Create a bash literal string as returned by an action function"
  [& bash-strings]
  [{:language :bash} (apply str bash-strings)])

(defn remove-source-line-comments [script]
  (-> script
      (string/replace #"(?sm)^( *# [\w\d_]+.clj:\d+)+\n" "")
      (string/replace #"\s+# [\w\d_]+.clj:\d+" "")
      (string/replace #" \([\w\d_]+.clj:\d+\)\.\.\." "...")
      (string/replace #" \([\w\d_]+.clj:\d+\) : " " : ")
      (string/trim)))

;;; A test method that strips location and source comments
(defmethod assert-expr 'script-no-comment= [msg form]
  (let [[_ expected expr] form]
    `(let [expected# ~expected
           actual# ~expr
           expected-norm# (remove-source-line-comments expected#)
           actual-norm# (remove-source-line-comments actual#)]
       (if (= expected-norm# actual-norm#)
         (do-report
          {:type :pass :message ~msg :expected expected# :actual actual#})
         (do-report
          {:type :fail :message ~msg
           :expected (list '= [expected# expected-norm#] [actual# actual-norm#])
           :actual (list 'not (list '= [expected# expected-norm#]
                                       [actual# actual-norm#]))})))))
