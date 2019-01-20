(ns shadow.cljs.devtools.server.worker.impl
  (:refer-clojure :exclude (compile))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.async :as async :refer (go >! <! >!! <!! alt!)]
            [shadow.jvm-log :as log]
            [cljs.compiler :as cljs-comp]
            [cljs.analyzer :as cljs-ana]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.model :as m]
            [shadow.cljs.util :refer (reduce->)]
            [shadow.build :as build]
            [shadow.build.api :as build-api]
            [shadow.build.api :as cljs]
            [shadow.build.compiler :as build-comp]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.build.warnings :as warnings]
            [shadow.build.data :as data]
            [shadow.build.resource :as rc]
            [shadow.build.log :as build-log])
  (:import [java.util UUID]))

(defn proc? [x]
  (and (map? x) (::proc x)))

(defn worker-state? [x]
  (and (map? x) (::worker-state x)))

(defn gen-msg-id []
  (str (UUID/randomUUID)))

(defn repl-sources-as-client-resources
  "transforms a seq of resource-ids to return more info about the resource
   a REPL client needs to know more since resource-ids are not available at runtime"
  [source-ids state]
  (->> source-ids
       (map (fn [src-id]
              (let [src (get-in state [:sources src-id])]
                (select-keys src [:resource-id
                                  :type
                                  :resource-name
                                  :output-name
                                  :from-jar
                                  :ns
                                  :provides]))))
       (into [])))

(defmulti transform-repl-action
  (fn [state action]
    (:type action))
  :default ::default)

(defmethod transform-repl-action ::default [state action]
  action)

(defmethod transform-repl-action :repl/require [state action]
  (update action :sources repl-sources-as-client-resources state))

(defn >!!output [{:keys [system-bus build-id] :as worker-state} msg]
  {:pre [(map? msg)
         (:type msg)]}

  (let [msg (assoc msg :build-id build-id)
        output (get-in worker-state [:channels :output])]

    (sys-bus/publish! system-bus ::m/worker-broadcast msg)
    (sys-bus/publish! system-bus [::m/worker-output build-id] msg)

    (>!! output msg)
    worker-state))

(defn build-msg
  [worker-state msg]
  (>!!output worker-state
    {:type :build-message
     :msg msg}))

(defn repl-error [e]
  (log/debug-ex e ::repl-error)
  {:type :repl/error
   :ex e})

(defn build-failure
  [{:keys [build-config] :as worker-state} e]
  (let [{:keys [resource-id resource-ids]} (ex-data e)]
    (-> worker-state
        ;; if any resource was responsible for the build failing we remove it completely
        ;; to ensure that all state is in proper order in the next compile and does not
        ;; contain remnants of the failed compile
        ;; FIXME: should probably check ex-data :tag
        (cond->
          resource-id
          (update :build-state data/remove-source-by-id resource-id)
          resource-ids
          (update :build-state build-api/reset-resources resource-ids))
        (>!!output
          {:type :build-failure
           :report
           (binding [warnings/*color* false]
             (errors/error-format e))
           }))))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [build-config proc-id http] :as worker-state}]

  (>!!output worker-state {:type :build-configure
                           :build-config build-config})

  (try
    ;; FIXME: allow the target-fn read-only access to worker-state? not just worker-info?
    ;; it may want to put things on the websocket?
    (let [worker-info
          {:proc-id proc-id
           :addr (:addr http)
           :host (:host http)
           :port (:port http)
           :ssl (:ssl http)}

          async-logger
          (util/async-logger (-> worker-state :channels :output))

          {:keys [extra-config-files] :as build-state}
          (-> (util/new-build build-config :dev {})
              (build-api/with-logger async-logger)
              (merge {:worker-info worker-info
                      :mode :dev
                      ::compile-attempt 0
                      ;; temp hack for repl/setup since it needs access to repl-init-ns but configure wasn't called yet
                      :shadow.build/config build-config
                      })
              (build/configure :dev build-config)
              ;; FIXME: this should be done on session-start
              ;; only keeping it until everything uses new repl-system
              ;; must be done after config in case config has non-default resolve settings
              (repl/setup))

          extra-config-files
          (reduce
            (fn [m file]
              (assoc m file (.lastModified file)))
            {}
            extra-config-files)]

      ;; FIXME: should maybe cleanup old :build-state if there is one (re-configure)
      (assoc worker-state
        :extra-config-files extra-config-files
        :build-state build-state))
    (catch Exception e
      (-> worker-state
          (dissoc :build-state) ;; just in case there is an old one
          (build-failure e)))))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

(defn extract-reload-info
  "for every cljs build source collect all defs that are marked with special metadata"
  [{:keys [build-sources] :as build-state}]
  (-> {:never-load #{}
       :after-load []
       :before-load []}
      (reduce->
        (fn [info resource-id]
          (let [hooks (get-in build-state [:output resource-id ::hooks])]
            (if-not hooks
              info
              (merge-with into info hooks)
              )))
        build-sources
        )))

(defn add-entry [entries fn-sym & extra-attrs]
  (conj entries
    (-> {:fn-sym fn-sym
         :fn-str (cljs-comp/munge (str fn-sym))}
        (cond->
          (seq extra-attrs)
          (merge (apply array-map extra-attrs))))))

(defn build-find-resource-hooks [{:keys [compiler-env] :as build-state} {:keys [resource-id ns] :as src}]
  (let [{:keys [name meta defs] :as ns-info}
        (get-in compiler-env [::cljs-ana/namespaces ns])

        {:keys [after-load after-load-async before-load before-load-async] :as devtools-config}
        (get-in build-state [::build/config :devtools])

        hooks
        (-> {:never-load #{}
             :after-load []
             :before-load []}
            (cond->
              (or (:dev/never-load meta)
                  (:dev/once meta) ;; can't decide on which meta to use
                  (:figwheel-noload meta))
              (update :never-load conj name))

            (reduce-kv->
              (fn [info def-sym {:keys [name meta] :as def-info}]
                (cond-> info
                  (or (:dev/before-load meta)
                      (= name before-load))
                  (update :before-load add-entry name)

                  (or (:dev/before-load-async meta)
                      (= name before-load-async))
                  (update :before-load add-entry name :async true)

                  (or (:dev/after-load meta)
                      (= name after-load))
                  (update :after-load add-entry name)

                  (or (:dev/after-load-async meta)
                      (= name after-load-async))
                  (update :after-load add-entry name :async true)))
              defs))]

    (assoc-in build-state [:output resource-id ::hooks] hooks)))

(defn build-find-hooks [{:keys [build-sources] :as state}]
  (reduce
    (fn [state resource-id]
      (let [{:keys [type resource-name] :as src}
            (data/get-source-by-id state resource-id)]
        (if (or (not= :cljs type)
                (get-in state [:output resource-id ::hooks])
                (str/starts-with? resource-name "cljs/")
                (str/starts-with? resource-name "clojure/"))
          state
          (build-find-resource-hooks state src))))
    state
    build-sources))

(defn build-compile
  [{:keys [build-state namespaces-modified] :as worker-state}]
  ;; this may be nil if configure failed, just silently do nothing for now
  (if (nil? build-state)
    worker-state
    (try
      (>!!output worker-state {:type :build-start})

      (let [{:keys [build-sources build-macros] :as build-state}
            (-> build-state
                (cond->
                  (seq namespaces-modified)
                  (build-api/reset-namespaces namespaces-modified))
                (build-api/reset-always-compile-namespaces)
                (build/compile)
                (build/flush)
                (build-find-hooks)
                (update ::compile-attempt inc))]

        (>!!output worker-state
          {:type :build-complete
           :info (::build/build-info build-state)
           :reload-info (extract-reload-info build-state)})

        (assoc worker-state
          :build-state build-state
          ;; tracking added/modified namespaces since we finished compiling
          :namespaces-added #{}
          :namespaces-modified #{}
          :last-build-provides (-> build-state :sym->id keys set)
          :last-build-sources build-sources
          :last-build-macros build-macros))
      (catch Exception e
        (build-failure worker-state e)))))

(defn process-repl-result
  [worker-state {:keys [id] :as result}]

  ;; forward everything to out as well
  ;; FIXME: probably remove this?
  (>!!output worker-state {:type :repl/result :result result})

  (let [pending-action (get-in worker-state [:pending-results id])
        worker-state (update worker-state :pending-results dissoc id)]

    (cond
      (nil? pending-action)
      worker-state

      (fn? pending-action)
      (pending-action worker-state result)

      (util/chan? pending-action)
      (do (>!! pending-action result)
          worker-state)

      :else
      (do (log/warn ::invalid-pending-result {:id id :pending pending-action})
          worker-state
          ))))

(defmulti do-proc-control
  (fn [worker-state {:keys [type] :as msg}]
    type))

(defmethod do-proc-control :sync!
  [worker-state {:keys [chan] :as msg}]
  (async/close! chan)
  worker-state)

(defmethod do-proc-control :runtime-connect
  [{:keys [build-state] :as worker-state} {:keys [runtime-id runtime-out runtime-info]}]
  (log/debug ::runtime-connect {:runtime-id runtime-id})
  (>!! runtime-out {:type :repl/init
                    :repl-state
                    (-> (:repl-state build-state)
                        (update :repl-sources repl-sources-as-client-resources build-state))})

  ;; (>!!output worker-state {:type :repl/runtime-connect :runtime-id runtime-id :runtime-info runtime-info})
  (-> worker-state
      (cond->
        (zero? (count (:runtimes worker-state)))
        (assoc :default-runtime-id runtime-id))
      (update :runtimes assoc runtime-id
        {:runtime-id runtime-id
         :runtime-out runtime-out
         :runtime-info runtime-info
         :connected-since (System/currentTimeMillis)
         :init-sent true})))

(defn maybe-pick-different-default-runtime [{:keys [runtimes default-runtime-id] :as worker-state} runtime-id]
  (cond
    (not= default-runtime-id runtime-id)
    worker-state

    (empty? runtimes)
    (dissoc worker-state :default-runtime-id)

    :else
    (assoc worker-state :default-runtime-id
                        (->> (vals runtimes)
                             (sort-by :connected-since)
                             (first)
                             :runtime-id))))

(comment
  ;; when a runtime disconnects and it was the default
  ;; pick a new one if there are any
  (maybe-pick-different-default-runtime
    {:default-runtime-id 1
     :runtimes {2 {:runtime-id 2 :connected-since 2}
                3 {:runtime-id 3 :connected-since 3}}}
    1)

  ;; if there aren't any remove the default
  (maybe-pick-different-default-runtime
    {:default-runtime-id 1
     :runtimes {}}
    1)

  ;; if it wasn't the default do nothing
  (maybe-pick-different-default-runtime
    {:default-runtime-id 2
     :runtimes {}}
    1))

(defmethod do-proc-control :runtime-disconnect
  [worker-state {:keys [runtime-id]}]
  (log/debug ::runtime-disconnect {:runtime-id runtime-id})
  ;; (>!!output worker-state {:type :repl/runtime-disconnect :runtime-id runtime-id})
  (-> worker-state
      (update :runtimes dissoc runtime-id)
      (maybe-pick-different-default-runtime runtime-id)
      ;; clean all sessions for that runtime
      (update :repl-sessions (fn [sessions]
                               (reduce-kv
                                 (fn [sessions session-id session-info]

                                   (if (not= runtime-id (:runtime-id session-info))
                                     sessions
                                     (do (log/debug ::session-removal-runtime-disconnect
                                           {:session-id session-id
                                            :runtime-id runtime-id})
                                         (dissoc sessions session-id))))
                                 sessions
                                 sessions
                                 )))))

;; messages received from the runtime
(defmethod do-proc-control :runtime-msg
  [worker-state {:keys [msg runtime-id runtime-out] :as envelope}]
  (log/debug ::runtime-msg {:runtime-id runtime-id
                            :type (:type msg)})

  (case (:type msg)
    (:repl/result
      :repl/invoke-error
      :repl/init-complete
      :repl/set-ns-complete
      :repl/require-complete)
    (process-repl-result worker-state msg)

    :repl/out
    (>!!output worker-state {:type :repl/out :text (:text msg)})

    :repl/err
    (>!!output worker-state {:type :repl/err :text (:text msg)})

    :ping
    (do (>!! runtime-out {:type :pong :v (:v msg)})
        worker-state)

    ;; unknown message
    (do (log/warn ::unknown-runtime-msg {:runtime-id runtime-id :msg msg})
        worker-state)))

(defn handle-session-start-result [worker-state {:keys [type] :as result} {:keys [tool-out msg] :as envelope}]
  (case type
    :repl/init-complete
    (let [{::m/keys [session-id tool-id]} msg

          session-ns
          (get-in worker-state [:repl-sessions session-id :repl-state :current :ns])]

      (>!! tool-out {::m/op ::m/session-started
                     ::m/session-id session-id
                     ::m/tool-id tool-id
                     ::m/session-ns session-ns})
      worker-state)

    (do (log/warn ::unexpected-session-start-result {:result result :envelope envelope})
        worker-state)))

(defmethod do-proc-control ::m/session-start
  [{:keys [build-state] :as worker-state} {:keys [msg runtime-id runtime-out tool-out] :as envelope}]
  (log/debug ::tool-msg envelope)

  (let [{::m/keys [session-id tool-id]} msg]
    ;; if session already exists do nothing?
    (if (get-in worker-state [:repl-sessions session-id])
      (do (log/warn ::session-already-exists msg)
          worker-state)

      ;; otherwise create session and initialize client
      (let [default-repl-state ;; FIXME: this shouldn't exist at all
            (:repl-state worker-state)

            msg-id
            (gen-msg-id)

            {:keys [repl-state] :as build-state}
            (-> build-state
                (dissoc :repl-state)
                (repl/prepare)
                (update :repl-state assoc :session-id session-id))]

        ;; FIXME: the reply for this could arrive before the state is updated!
        (>!! runtime-out {:type :repl/session-start
                          :id msg-id
                          :repl-state
                          (update repl-state :repl-sources repl-sources-as-client-resources build-state)})

        (-> worker-state
            (assoc-in [:repl-sessions session-id]
              {:tool-out tool-out
               :tool-id tool-id
               :session-id session-id
               :runtime-id runtime-id
               :repl-state repl-state})

            (assoc :build-state (assoc build-state :repl-state default-repl-state))
            (update :pending-results assoc msg-id #(handle-session-start-result %1 %2 envelope))
            )))))

(defmethod do-proc-control ::m/tool-disconnect
  [worker-state {::m/keys [tool-id] :as msg}]
  worker-state
  ;; FIXME: should this actually clean out the session or just wait for tool reconnect maybe?
  #_(update worker-state :repl-sessions (fn [x]
                                          (reduce-kv
                                            (fn [x session-id session-info]
                                              (if (not= tool-id (:tool-id session-info))
                                                x
                                                ;; FIXME: something to cleanup on session-end?
                                                (do (log/debug ::tool-disconnect {:tool-id tool-id :session-id session-id})
                                                    (dissoc x session-id))))
                                            x
                                            x))))

(defn select-repl-state [worker-state session]
  (-> worker-state
      (assoc ::default-repl-state (get-in worker-state [:build-state :repl-state]))
      (assoc-in [:build-state :repl-state] (:repl-state session))))

(defn pop-repl-state [worker-state session-id]
  (let [repl-state (get-in worker-state [:build-state :repl-state])
        default-state (get-in worker-state [:build-state ::default-repl-state])]

    (-> worker-state
        (update :build-state assoc :repl-state default-state)
        (update :build-state dissoc ::m/default-repl-state)
        (assoc-in [:repl-sessions session-id :repl-state] repl-state)
        )))

(defn handle-repl-action-result
  [worker-state {:keys [type value result-id] :as result} {:keys [tool-out msg] :as envelope} action]
  (let [{::m/keys [session-id tool-id]} msg]
    (case type
      :repl/result
      (do (>!! tool-out {::m/op ::m/session-result
                         ::m/session-id session-id
                         ::m/tool-id tool-id
                         ::m/result-id result-id
                         ::m/printed-result value})
          worker-state)

      :repl/set-ns-complete
      (let [session-ns (get-in worker-state [:repl-sessions session-id :repl-state :current :ns])]
        (>!! tool-out {::m/op ::m/session-update
                       ::m/session-id session-id
                       ::m/session-ns session-ns})
        worker-state)

      (do (log/warn ::unexpected-repl-action-result {:result result :envelope envelope})
          worker-state))))

(defmethod do-proc-control ::m/session-eval
  [{:keys [build-state] :as worker-state}
   {:keys [msg tool-out] :as envelope}]

  (log/debug ::session-eval msg)
  (let [{::m/keys [session-id input-text]} msg

        {:keys [runtime-id] :as session}
        (get-in worker-state [:repl-sessions session-id])

        {:keys [runtime-out] :as runtime}
        (get-in worker-state [:runtimes runtime-id])]

    (cond
      (nil? build-state)
      (do (>!! tool-out {:type :repl/illegal-state})
          worker-state)

      (not session)
      (do (log/debug ::session-missing msg)
          worker-state)

      (not runtime)
      (do (log/debug ::runtime-missing msg)
          worker-state)

      :else
      (try
        (let [{:keys [build-state] :as worker-state}
              (select-repl-state worker-state session)

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (repl/process-input build-state input-text)

              new-actions
              (->> (subvec (:repl-actions repl-state) start-idx)
                   (map (fn [x] (assoc x :id (gen-msg-id)))))

              worker-state
              (reduce
                (fn [worker-state {:keys [id] :as action}]
                  (update worker-state :pending-results assoc id #(handle-repl-action-result %1 %2 envelope action)))
                worker-state
                new-actions)]

          (doseq [action new-actions]
            (>!! runtime-out (-> (transform-repl-action build-state action)
                                 (assoc :session-id session-id))))

          (-> worker-state
              (assoc :build-state build-state)
              (pop-repl-state session-id)))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! tool-out msg)
            (>!!output worker-state msg))
          worker-state)))))

(defmethod do-proc-control :start-autobuild
  [{:keys [build-config autobuild] :as worker-state} msg]
  (if autobuild
    ;; do nothing if already in auto mode
    worker-state
    ;; compile immediately, autobuild is then checked later
    (-> worker-state
        (assoc :autobuild true)
        (build-configure)
        (build-compile)
        )))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :compile
  [{:keys [build-state] :as worker-state} {:keys [reply-to] :as msg}]
  (let [result
        (-> worker-state
            (cond->
              (not build-state)
              (build-configure))
            (build-compile))]

    (when reply-to
      (>!! reply-to :done))

    result
    ))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :load-file
  [{:keys [build-state runtimes] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [runtime-count (count runtimes)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (> runtime-count 1)
      (do (>!! result-chan {:type :repl/too-many-runtimes :count runtime-count})
          worker-state)

      (zero? runtime-count)
      (do (>!! result-chan {:type :repl/no-runtime-connected})
          worker-state)

      :else
      (try
        (let [{:keys [runtime-out] :as runtime}
              (first (vals runtimes))

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (repl/repl-load-file* build-state msg)

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              last-idx
              (-> (get-in build-state [:repl-state :repl-actions])
                  (count)
                  (dec))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            ;; FIXME: this should be removed, only the runtime and tool should be notified
            (>!!output worker-state {:type :repl/action :action action})

            (>!! runtime-out (transform-repl-action build-state action)))

          (-> worker-state
              (assoc :build-state build-state)
              (update :pending-results assoc last-idx result-chan)))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defmethod do-proc-control :repl-compile
  [{:keys [build-state] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (try
    (let [start-idx
          (count (get-in build-state [:repl-state :repl-actions]))

          {:keys [repl-state] :as build-state}
          (if (string? input)
            (repl/process-input build-state input)
            (repl/process-read-result build-state input))

          new-actions
          (subvec (:repl-actions repl-state) start-idx)]

      (>!! result-chan {:type :repl/actions
                        :actions new-actions})

      (assoc worker-state :build-state build-state))

    (catch Exception e
      (>!! result-chan {:type :repl/error :e e})
      worker-state)))

(defmethod do-proc-control :repl-eval
  [{:keys [build-state runtimes default-runtime-id] :as worker-state}
   {:keys [result-chan input session-id runtime-id] :as msg}]
  (log/debug ::repl-eval {:session-id session-id :runtime-id runtime-id})
  (let [runtime-count (count runtimes)

        runtime-id
        (or runtime-id default-runtime-id)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (and (not runtime-id) (> runtime-count 1))
      (do (>!! result-chan {:type :repl/too-many-runtimes :count runtime-count})
          worker-state)

      (and runtime-id (not (get runtimes runtime-id)))
      (do (>!! result-chan {:type :repl/runtime-not-found :runtime-id runtime-id})
          worker-state)

      (zero? runtime-count)
      (do (>!! result-chan {:type :repl/no-runtime-connected})
          worker-state)

      :else
      (try
        (let [{:keys [runtime-out] :as runtime}
              (if runtime-id
                (get runtimes runtime-id)
                (first (vals runtimes)))

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (if (string? input)
                (repl/process-input build-state input)
                (repl/process-read-result build-state input))

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              last-idx
              (-> (get-in build-state [:repl-state :repl-actions])
                  (count)
                  (dec))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            (>!!output worker-state {:type :repl/action
                                     :action action})
            (>!! runtime-out (transform-repl-action build-state action)))

          (-> worker-state
              (assoc :build-state build-state)
              ;; FIXME: now dropping intermediate results since the REPL only expects one result
              (update :pending-results assoc last-idx result-chan)))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defn do-macro-update
  [{:keys [build-state last-build-macros autobuild] :as worker-state} {:keys [macro-namespaces] :as msg}]

  (cond
    (not build-state)
    worker-state

    ;; the updated macro may not be used by this build
    ;; so we can skip the rebuild
    (and (seq last-build-macros) (some last-build-macros macro-namespaces))
    (-> worker-state
        (update :build-state build-api/reset-resources-using-macros macro-namespaces)
        (cond->
          autobuild
          (build-compile)))

    :do-nothing
    (do (log/debug ::not-affected {:build-id (get-in build-state [:build-config :build-id])
                                   :macro-namespaces macro-namespaces})
        worker-state)))

(defn do-resource-update
  [{:keys [autobuild last-build-provides build-state] :as worker-state}
   {:keys [namespaces added] :as msg}]

  (if-not build-state
    worker-state
    (let [namespaces-used-by-build
          (->> namespaces
               (filter #(contains? last-build-provides %))
               (into #{}))]

      (cond
        ;; always recompile if the first compile attempt failed
        ;; since we don't know which files it wanted to use
        ;; after that only recompile when files in use by the build changed
        ;; or when new files were added and the build is greedy
        ;; (eg. browser-test since it dynamically adds file to the build)
        (and (pos? (::compile-attempt build-state))
             (not (seq namespaces-used-by-build))
             (if-not (get-in build-state [:build-options :greedy])
               ;; build is not greedy, not interested in new files
               true
               ;; build is greedy, must recompile if new files were added
               (not (seq added))))
        worker-state

        :else
        (-> worker-state
            ;; only record which namespaces where added/changed
            ;; delay doing actual work until next compilation
            ;; which may not happen immediately if autobuild is off
            ;; the REPL may still trigger compilations independently
            ;; which break if the state is already half cleaned
            (update :namespaces-added set/union added)
            (update :namespaces-modified set/union added namespaces)
            (cond->
              autobuild
              (build-compile)))))))

(defn do-asset-update
  [{:keys [runtimes] :as worker-state} updates]
  (let [watch-path
        (get-in worker-state [:build-config :devtools :watch-path])

        updates
        (->> updates
             (filter #(contains? #{:mod :new} (:event %)))
             (map #(update % :name rc/normalize-name))
             (map #(str watch-path "/" (:name %)))
             (into []))]
    ;; only interested in file modifications
    ;; don't need file instances, just the names
    (when (seq updates)
      (doseq [{:keys [runtime-out]} (vals runtimes)]
        (>!! runtime-out {:type :asset-watch
                          :updates updates}))))

  worker-state)

(defn do-config-watch
  [{:keys [autobuild] :as worker-state} {:keys [config] :as msg}]
  (-> worker-state
      (assoc :build-config config)
      (cond->
        autobuild
        (-> (build-configure)
            (build-compile)))))

(defn check-extra-files [files]
  (reduce-kv
    (fn [m file last-mod]
      (let [new-mod (.lastModified file)]
        (if (= new-mod last-mod)
          m
          (assoc m file new-mod))))
    files
    files))

(defn maybe-reload-config-files [{:keys [autobuild extra-config-files] :as worker-state}]
  (let [checked (check-extra-files extra-config-files)]
    (if (identical? extra-config-files checked)
      worker-state
      (do (log/debug ::extra-files-modified)
          (-> worker-state
              (assoc :addition-config-files checked)
              (cond->
                autobuild
                (-> (build-configure)
                    (build-compile)))
              )))))

(defn do-idle [{:keys [extra-config-files] :as worker-state}]
  (-> worker-state
      (cond->
        (seq extra-config-files)
        (maybe-reload-config-files))))
