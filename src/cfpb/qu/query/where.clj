(ns cfpb.qu.query.where
  "This namespace parses WHERE clauses into an AST and turns that AST
into a Monger query."
  (:require
   [clojure.string :as str]
   [protoflex.parse :as p]
   [cfpb.qu.query.parser :refer [where-expr]])
  (:import (java.util.regex Pattern)))

(defn parse
  "Parse a valid WHERE expression and return an abstract syntax tree
for use in constructing Mongo queries."
  [clause]
  (p/parse where-expr clause))

(def mongo-operators
  {:AND "$and"
   :OR "$or"
   :< "$lt"
   :<= "$lte"
   :> "$gt"
   :>= "$gte"
   :!= "$ne"})

(defmulti mongo-fn
  (fn [name _]
    name))

;; TODO Make sure the strings coming into these are escaped so that
;; they do not allow other regex characters

(defmethod mongo-fn :starts_with [_ [ident string]]
  (let [string (Pattern/quote string)]
    {ident (re-pattern (str "^" string))}))

(defmethod mongo-fn :contains [_ [ident string]]
  (let [string (Pattern/quote string)]
    {ident (re-pattern string)}))

(defmethod mongo-fn :default [name args]
  (throw (ex-info "Function not found" {:error "func-not-found"
                                        :function name
                                        :args args})))

(defn mongo-not [comparison]
  (let [ident (first (keys comparison))
        operation (first (vals comparison))]

    (cond
     (map? operation)
     (let [operator (first (keys operation))
           value (first (vals operation))]
       (if (= operator "$ne")
         {ident value}
         {ident {"$not" operation}}))

     (= (type operation) Pattern)
     {ident {"$not" operation}}

     :default
     {ident {"$ne" operation}})))

(declare mongo-eval-not)

(defn sql-pattern-to-regex-str
  "Converts a SQL search string, such as 'foo%', into a regular expression string"
  [value]
  (str "^"
    (str/replace value
      #"[%_]|[^%_]+"
      (fn [match]
        (case match
          "%" ".*"
          "_" "."
          (Pattern/quote match))))
    "$"))

(defn like-to-regex
  "Converts a SQL LIKE value into a regular expression."
  [like]
  (re-pattern (sql-pattern-to-regex-str like)))

(defn ilike-to-regex
  "Converts a SQL ILIKE value into a regular expression."
  [ilike]
  (re-pattern
    (str "(?i)"
      (sql-pattern-to-regex-str ilike))))


(defn mongo-eval
  "Take an abstract syntax tree generated by `parse` and turn it into
a valid Monger query."
  [ast]
  (cond
   (get ast :not)
   (mongo-eval-not (:not ast))
   
   (get ast :op)
   (let [{:keys [op left right]} ast]
     {(op mongo-operators) [(mongo-eval left) (mongo-eval right)]})

   (get ast :comparison)
   (let [[ident op value] (:comparison ast)
         value (mongo-eval value)]
     (case op
       := {ident value}
       :LIKE {ident (like-to-regex value)}
       :ILIKE {ident (ilike-to-regex value)}
       {ident {(op mongo-operators) value}}))

   (get ast :bool)
   (:bool ast)

   (get ast :function)
   (let [fnname (get-in ast [:function :name])
         args (get-in ast [:function :args])]
     (mongo-fn fnname args))

   (get ast :error)
   {:_id false}
   
   :default
   ast))

(defn- mongo-eval-not [ast]
  (cond
   (get ast :not)
   (mongo-eval (:not ast))
   
   (get ast :op)
   (let [{:keys [op left right]} ast]
     (case op
       :OR {"$nor" [(mongo-eval left) (mongo-eval right)]}
       :AND {"$or" [(mongo-eval-not left) (mongo-eval-not right)]}))

   (get ast :comparison)
   (mongo-not (mongo-eval ast))

   (get ast :function)
   (mongo-not (mongo-eval ast))

   (get ast :bool)
   (not (:bool ast))

   (get ast :error)
   (mongo-eval ast)

   :default
   (not ast)))
