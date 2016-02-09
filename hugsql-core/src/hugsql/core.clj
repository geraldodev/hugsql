(ns hugsql.core
  (:require [hugsql.parser :as parser]
            [hugsql.parameters :as parameters]
            [hugsql.adapter :as adapter]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(def ^:no-doc adapter nil)

(defn set-adapter!
  "Set a global adapter."
  [the-adapter]
  (alter-var-root #'adapter (constantly the-adapter)))

(defn ^:no-doc get-adapter
  "Get an adapter.  Sets default
   hugsql.adapter.clojure-java-jdbc 
   adapter if no adapter is set."
  []
  ;; DEV NOTE: I don't really like dynamically eval'ing
  ;; and require'ing here, but I do prefer to have:
  ;; 1) an easy-path/just-works default adapter
  ;; 2) *no* hard-coded default dependency
  ;;    if someone wants to use another adapter
  ;; 3) *no* requirement for an adapter at all unless
  ;;    def-db-fns is used and calls this function
  (when (nil? adapter)
    (eval
     '(do (clojure.core/require '[hugsql.adapter.clojure-java-jdbc :as adp])
          (hugsql.core/set-adapter! (adp/hugsql-adapter-clojure-java-jdbc)))))
  adapter)

(defn ^:no-doc parsed-defs-from-string
  "Given a hugsql SQL string,
   parse it, and return the defs."
  [sql]
  (parser/parse sql))

(defn ^:no-doc parsed-defs-from-file
  "Given a hugsql SQL file in the classpath,
   a resource, or a java.io.File, parse it, and return the defs."
  [file]
  (parser/parse
   (slurp
    (condp instance? file
      java.io.File file ; already file
      java.net.URL file ; already resource
      ;; assume resource path (on classpath)
      (if-let [f (io/resource file)]
        f
        (throw (ex-info (str "Can not read file: " file) {})))))))

(defn ^:no-doc validate-parameters!
  "Ensure SQL template parameters match provided param-data,
   and throw an exception if mismatch."
  [sql-template param-data]
  (doseq [k (map :name (filter map? sql-template))]
    (when-not (contains? param-data k)
      (throw (ex-info
              (str "Parameter Mismatch: "
                   k " parameter data not found.") {})))))

(defn ^:no-doc prepare-sql
  "Takes an sql template (from hugsql parser)
   and the runtime-provided param data
   and creates a vector of [\"sql\" val1 val2]
   suitable for jdbc query/execute.

   The :sql vector (sql-template) has interwoven
   sql strings and hashmap parameters.  We directly
   apply parameters to non-prepared parameter types such
   as identifiers and keywords.  For value parameter types,
   we replace use the jdbc prepared statement syntax of a
   '?' to placehold for the value."
  ([sql-template param-data options]
   (validate-parameters! sql-template param-data)
   (let [applied (mapv
                  #(if (string? %)
                     [%]
                     (parameters/apply-hugsql-param % param-data options))
                  sql-template)
         sql    (string/join "" (map first applied))
         params (flatten (remove empty? (map rest applied)))]
     (apply vector sql params))))

(def default-sqlvec-options
  {:quoting :off
   :fn-suffix "-sqlvec"})

(def default-db-options {:quoting :off})

(defn- str->key
  [str]
  (keyword (string/replace-first str #":" "")))

(defn ^:no-doc command-sym
  [hdr]
  (or
   ;;                ↓ short-hand command position
   ;; -- :name my-fn :? :1
   (when-let [c (second (:name hdr))] (str->key c))
   ;; -- :command :?
   (when-let [c (first (:command hdr))] (str->key c))
   ;; default
   :query))

(defn ^:no-doc result-sym
  [hdr]
  (keyword
   (or
    ;;                   ↓ short-hand result position
    ;; -- :name my-fn :? :1
    (when-let [r (second (next (:name hdr)))] (str->key r))
    ;; -- :result :1
    (when-let [r (first (:result hdr))] (str->key r))
    ;; default
    :raw)))

(defmulti hugsql-command-fn identity)
(defmethod hugsql-command-fn :! [sym] 'hugsql.adapter/execute)
(defmethod hugsql-command-fn :execute [sym] 'hugsql.adapter/execute)
(defmethod hugsql-command-fn :? [sym] 'hugsql.adapter/query)
(defmethod hugsql-command-fn :query [sym] 'hugsql.adapter/query)
(defmethod hugsql-command-fn :default [sym] 'hugsql.adapter/query)

(defmulti hugsql-result-fn identity)
(defmethod hugsql-result-fn :1 [sym] 'hugsql.adapter/result-one)
(defmethod hugsql-result-fn :one [sym] 'hugsql.adapter/result-one)
(defmethod hugsql-result-fn :* [sym] 'hugsql.adapter/result-many)
(defmethod hugsql-result-fn :many [sym] 'hugsql.adapter/result-many)
(defmethod hugsql-result-fn :n [sym] 'hugsql.adapter/result-affected)
(defmethod hugsql-result-fn :affected [sym] 'hugsql.adapter/result-affected)
(defmethod hugsql-result-fn :raw [sym] 'hugsql.adapter/result-raw)
(defmethod hugsql-result-fn :default [sym] 'hugsql.adapter/result-raw)

(defn sqlvec-fn*
  "Given parsed sql and optional options, return an
   anonymous function that returns hugsql sqlvec format"
  ([psql] (sqlvec-fn* psql {}))
  ([psql options]
   (fn y
     ([] (y {} {}))
     ([param-data] (y param-data {}))
     ([param-data opts]
      (prepare-sql psql param-data (merge default-sqlvec-options opts))))))

(defn sqlvec-fn
  "Given an sql string and optional options, return an
   anonymous function that returns hugsql sqlvec format"
  ([sql] (sqlvec-fn sql {}))
  ([sql options]
   (let [psql (:sql (first (parser/parse sql {:no-header true})))]
     (sqlvec-fn* psql options))))

(defn sqlvec
  "Given an sql string, optional options, and param data, return an sqlvec"
  ([sql param-data] (sqlvec sql {} param-data))
  ([sql options param-data]
   (let [f (sqlvec-fn sql options)]
     (f param-data))))

(defmacro def-sqlvec-fns
  "Given a HugSQL SQL file, define the <name>-sqlvec functions in the
  current namespace.  Returns sqlvec format: a vector of SQL and
  parameter values. (e.g., [\"select * from test where id = ?\" 42])

  Usage:

   (def-sqlvec-fns file options?)

   where:
    - file is a string file path in your classpath,
      a resource object (java.net.URL),
      or a file object (java.io.File)
    - options (optional) hashmap:
      {:quoting :off(default) | :ansi | :mysql | :mssql
       :fn-suffix \"-sqlvec\" (default)

   :quoting options for identifiers are:
     :ansi double-quotes: \"identifier\"
     :mysql backticks: `identifier`
     :mssql square brackets: [identifier]
     :off no quoting (default)

   Identifiers containing a period/dot . are split, quoted separately,
   and then rejoined. This supports myschema.mytable conventions.

   :quoting can be overridden as an option in the calls to functions
   created by def-db-fns.

   :fn-suffix is appended to the defined function names to
   differentiate them from the functions defined by def-db-fns."
  ([file] (def-sqlvec-fns &form &env file {}))
  ([file options]
   `(doseq [~'pdef (parsed-defs-from-file ~file)]
      (let [~'sql (:sql ~'pdef)
            ~'nam (symbol
                   (str (first (:name (:hdr ~'pdef)))
                        (:fn-suffix (merge default-sqlvec-options ~options))))
            ~'doc (str (or (first (:doc (:hdr ~'pdef))) "") " (sqlvec)")]
        (intern *ns*
                (with-meta ~'nam {:doc ~'doc})
                (sqlvec-fn* ~'sql ~options))))))

(defmacro def-sqlvec-fns-from-string
  "Given a HugSQL SQL string, define the <name>-sqlvec functions in the
  current namespace.  Returns sqlvec format: a vector of SQL and
  parameter values. (e.g., [\"select * from test where id = ?\" 42])

  Usage:

   (def-sqlvec-fns-from-string s options?)

   where:
    - s is a HugSQL-flavored sql string
    - options (optional) hashmap:
      {:quoting :off(default) | :ansi | :mysql | :mssql
       :fn-suffix \"-sqlvec\" (default)

   :quoting options for identifiers are:
     :ansi double-quotes: \"identifier\"
     :mysql backticks: `identifier`
     :mssql square brackets: [identifier]
     :off no quoting (default)

   Identifiers containing a period/dot . are split, quoted separately,
   and then rejoined. This supports myschema.mytable conventions.

   :quoting can be overridden as an option in the calls to functions
   created by def-db-fns.

   :fn-suffix is appended to the defined function names to
   differentiate them from the functions defined by def-db-fns."
  ([s] (def-sqlvec-fns-from-string &form &env s {}))
  ([s options]
   `(doseq [~'pdef (parsed-defs-from-string ~s)]
      (let [~'sql (:sql ~'pdef)
            ~'nam (symbol
                   (str (first (:name (:hdr ~'pdef)))
                        (:fn-suffix (merge default-sqlvec-options ~options))))
            ~'doc (str (or (first (:doc (:hdr ~'pdef))) "") " (sqlvec)")]
        (intern *ns*
                (with-meta ~'nam {:doc ~'doc})
                (sqlvec-fn* ~'sql ~options))))))

(defn db-fn*
  "Given parsed sql and optionally a command, result, and options,
  return an anonymous function that can run hugsql database
  execute/queries and supports hugsql parameter replacement"
  ([psql] (db-fn* psql :default :default {}))
  ([psql command] (db-fn* psql command :default {}))
  ([psql command result] (db-fn* psql command result {}))
  ([psql command result options]
   (fn y
     ([db] (y db {} {}))
     ([db param-data] (y db param-data {}))
     ([db param-data opts & command-opts]
      (let [o (merge default-db-options options opts)
            o (if (seq command-opts)
                (assoc o :command-options command-opts) o)
            a (or (:adapter o) (get-adapter))]
        (try
          (as-> psql x
            (prepare-sql x param-data o)
            ((resolve (hugsql-command-fn command)) a db x o)
            ((resolve (hugsql-result-fn result)) a x o))
          (catch Exception e
            (adapter/on-exception a e))))))))

(defn db-fn
  "Given an sql string and optionally a command, result, and options,
  return an anonymous function that can run hugsql database
  execute/queries and supports hugsql parameter replacement"
  ([sql] (db-fn sql :default :default {}))
  ([sql command] (db-fn sql command :default {}))
  ([sql command result] (db-fn sql command result {}))
  ([sql command result options]
   (let [psql (:sql (first (parser/parse sql {:no-header true})))]
     (db-fn* psql command result options))))

(defmacro def-db-fns
  "Given a HugSQL SQL file, define the database
   functions in the current namespace.

   Usage:

   (def-db-fns file options?)

   where:
    - file is a string file path in your classpath,
      a resource object (java.net.URL),
      or a file object (java.io.File)
    - options (optional) hashmap:
      {:quoting :off(default) | :ansi | :mysql | :mssql
       :adapter adapter }

   :quoting options for identifiers are:
     :ansi double-quotes: \"identifier\"
     :mysql backticks: `identifier`
     :mssql square brackets: [identifier]
     :off no quoting (default)

   Identifiers containing a period/dot . are split, quoted separately,
   and then rejoined. This supports myschema.mytable conventions.

   :quoting can be overridden as an option in the calls to functions
   created by def-db-fns.

   :adapter specifies the HugSQL adapter to use for all defined
   functions. The default adapter used is
   (hugsql.adapter.clojure-java-jdbc/hugsql-adapter-clojure-java-jdbc)
   when :adapter is not given.

   See also hugsql.core/set-adapter! to set adapter for all def-db-fns
   calls.  Also, :adapter can be specified for individual function
   calls (overriding set-adapter! and the :adapter option here)."
  ([file] (def-db-fns &form &env file {}))
  ([file options]
   `(doseq [~'pdef (parsed-defs-from-file ~file)]
      (let [~'sql (:sql ~'pdef)
            ~'nam (symbol (first (:name (:hdr ~'pdef))))
            ~'doc (or (first (:doc (:hdr ~'pdef))) "")
            ~'cmd (command-sym (:hdr ~'pdef))
            ~'res (result-sym (:hdr ~'pdef))]
        (intern *ns*
                (with-meta ~'nam {:doc ~'doc})
                (db-fn* ~'sql ~'cmd ~'res ~options))))))

(defmacro def-db-fns-from-string
  "Given a HugSQL SQL string, define the database
   functions in the current namespace.

   Usage:

   (def-db-fns-from-string s options?)

   where:
    - string is HugSQL-flavored sql statements
    - options (optional) hashmap:
      {:quoting :off(default) | :ansi | :mysql | :mssql
       :adapter adapter }

   :quoting options for identifiers are:
     :ansi double-quotes: \"identifier\"
     :mysql backticks: `identifier`
     :mssql square brackets: [identifier]
     :off no quoting (default)

   Identifiers containing a period/dot . are split, quoted separately,
   and then rejoined. This supports myschema.mytable conventions.

   :quoting can be overridden as an option in the calls to functions
   created by def-db-fns.

   :adapter specifies the HugSQL adapter to use for all defined
   functions. The default adapter used is
   (hugsql.adapter.clojure-java-jdbc/hugsql-adapter-clojure-java-jdbc)
   when :adapter is not given.

   See also hugsql.core/set-adapter! to set adapter for all def-db-fns
   calls.  Also, :adapter can be specified for individual function
   calls (overriding set-adapter! and the :adapter option here)."
  ([s] (def-db-fns-from-string &form &env s {}))
  ([s options]
   `(doseq [~'pdef (parsed-defs-from-string ~s)]
      (let [~'sql (:sql ~'pdef)
            ~'nam (symbol (first (:name (:hdr ~'pdef))))
            ~'doc (or (first (:doc (:hdr ~'pdef))) "")
            ~'cmd (command-sym (:hdr ~'pdef))
            ~'res (result-sym (:hdr ~'pdef))]
        (intern *ns*
                (with-meta ~'nam {:doc ~'doc})
                (db-fn* ~'sql ~'cmd ~'res ~options))))))

(defn db-run
  "Given a database spec/connection, sql string,
   parameter data, and optional command, result,
   and options, run the sql statement"
  ([db sql] (db-run db sql :default :default {} {}))
  ([db sql param-data] (db-run db sql param-data :default :default {}))
  ([db sql param-data command] (db-run db sql param-data command :default {}))
  ([db sql param-data command result] (db-run db sql param-data command result {}))
  ([db sql param-data command result options & command-options]
   (let [f (db-fn sql command result options)]
     (f db param-data command-options))))
