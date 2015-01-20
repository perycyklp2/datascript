(ns datascript.core
  (:require
    [datascript.btset :as btset]
    [goog.array :as garray])
  (:require-macros
    [datascript :refer [combine-cmp case-tree]]))

(def ^:const tx0 0x20000000)

(defrecord Datom [e a v tx added]
  Object
  (toString [this]
    (pr-str this)))

(extend-type Datom
  IHash
  (-hash [d] (or (.-__hash d)
                 (set! (.-__hash d)
                       (-> (hash (.-e d))
                           (hash-combine (hash (.-a d)))
                           (hash-combine (hash (.-v d)))))))
  IEquiv
  (-equiv [d o] (and (= (.-e d) (.-e o))
                     (= (.-a d) (.-a o))
                     (= (.-v d) (.-v o))))

  ISeqable
  (-seq [d] (list (.-e d) (.-a d) (.-v d) (.-tx d) (.-added d))))


;;;;;;;;;; Searching

(defprotocol ISearch
  (-search [data pattern]))

(defprotocol IIndexAccess
  (-datoms [db index components])
  (-seek-datoms [db index components])
  (-index-range [db attr start end]))

(defprotocol IDB
  (-schema [db])
  (-refs [db]))

(defn- cmp [o1 o2]
  (if (and o1 o2)
    (compare o1 o2)
    0))

(defn- cmp-num [n1 n2]
  (if (and n1 n2)
    (- n1 n2)
    0))

(defn cmp-val [o1 o2]
  (if (and (some? o1) (some? o2))
    (let [t1 (type o1)
          t2 (type o2)]
      (if (identical? t1 t2)
        (compare o1 o2)
        (garray/defaultCompare t1 t2)))
    0))

;; Slower cmp-* fns allows for datom fields to be nil.
;; Such datoms come from slice method where they are used as boundary markers.

(defn cmp-datoms-eavt [d1 d2]
  (combine-cmp
    (cmp-num (.-e d1) (.-e d2))
    (cmp (.-a d1) (.-a d2))
    (cmp-val (.-v d1) (.-v d2))
    (cmp-num (.-tx d1) (.-tx d2))))

(defn cmp-datoms-aevt [d1 d2]
  (combine-cmp
    (cmp (.-a d1) (.-a d2))
    (cmp-num (.-e d1) (.-e d2))
    (cmp-val (.-v d1) (.-v d2))
    (cmp-num (.-tx d1) (.-tx d2))))

(defn cmp-datoms-avet [d1 d2]
  (combine-cmp
    (cmp (.-a d1) (.-a d2))
    (cmp-val (.-v d1) (.-v d2))
    (cmp-num (.-e d1) (.-e d2))
    (cmp-num (.-tx d1) (.-tx d2))))


;; fast versions without nil checks

;; see http://dev.clojure.org/jira/browse/CLJS-892
(defn- compare-keywords-quick [a b]
  (cond
    (identical? (.-fqn a) (.-fqn b)) 0
    (and (not (.-ns a)) (.-ns b)) -1
    (.-ns a) (if-not (.-ns b)
               1
               (let [nsc (garray/defaultCompare (.-ns a) (.-ns b))]
                 (if (zero? nsc)
                   (garray/defaultCompare (.-name a) (.-name b))
                   nsc)))
    :default (garray/defaultCompare (.-name a) (.-name b))))

(defn- cmp-attr-quick [a1 a2]
  ;; either both are keywords or both are strings
  (if (keyword? a1)
    (compare-keywords-quick a1 a2)
    (garray/defaultCompare a1 a2)))

(defn- cmp-val-quick [o1 o2]
  (let [t1 (type o1)
        t2 (type o2)]
    (if (identical? t1 t2)
      (compare o1 o2)
      (garray/defaultCompare t1 t2))))

(defn cmp-datoms-eavt-quick [d1 d2]
  (combine-cmp
    (- (.-e d1) (.-e d2))
    (cmp-attr-quick (.-a d1) (.-a d2))
    (cmp-val-quick  (.-v d1) (.-v d2))
    (- (.-tx d1) (.-tx d2))))

(defn cmp-datoms-aevt-quick [d1 d2]
  (combine-cmp
    (cmp-attr-quick (.-a d1) (.-a d2))
    (- (.-e d1) (.-e d2))
    (cmp-val-quick (.-v d1) (.-v d2))
    (- (.-tx d1) (.-tx d2))))

(defn cmp-datoms-avet-quick [d1 d2]
  (combine-cmp
    (cmp-attr-quick (.-a d1) (.-a d2))
    (cmp-val-quick  (.-v d1) (.-v d2))
    (- (.-e d1) (.-e d2))
    (- (.-tx d1) (.-tx d2))))

(defn- components->pattern [index [c0 c1 c2 c3]]
  (case index
    :eavt (Datom. c0 c1 c2 c3 nil)
    :aevt (Datom. c1 c0 c2 c3 nil)
    :avet (Datom. c2 c0 c1 c3 nil)))

(defrecord DB [schema eavt aevt avet max-eid max-tx refs]
  Object
  (toString [this]
    (pr-str* this))
  
  IDB
  (-schema [_] schema)
  (-refs   [_] refs)

  ISearch
  (-search [_ [e a v tx]]
    (case-tree [e a (some? v) tx] [
      (btset/slice eavt (Datom. e a v tx nil))                 ;; e a v tx
      (btset/slice eavt (Datom. e a v nil nil))                ;; e a v _
      (->> (btset/slice eavt (Datom. e a nil nil nil))         ;; e a _ tx
           (filter #(= tx (.-tx %))))
      (btset/slice eavt (Datom. e a nil nil nil))              ;; e a _ _
      (->> (btset/slice eavt (Datom. e nil nil nil nil))       ;; e _ v tx
           (filter #(and (= v (.-v %)) (= tx (.-tx %)))))
      (->> (btset/slice eavt (Datom. e nil nil nil nil))       ;; e _ v _
           (filter #(= v (.-v %))))
      (->> (btset/slice eavt (Datom. e nil nil nil nil))       ;; e _ _ tx
           (filter #(= tx (.-tx %))))
      (btset/slice eavt (Datom. e nil nil nil nil))            ;; e _ _ _
      (->> (btset/slice avet (Datom. nil a v nil nil))         ;; _ a v tx
           (filter #(= tx (.-tx %))))
      (btset/slice avet (Datom. nil a v nil nil))              ;; _ a v _
      (->> (btset/slice avet (Datom. nil a nil nil nil))       ;; _ a _ tx
           (filter #(= tx (.-tx %))))
      (btset/slice avet (Datom. nil a nil nil nil))            ;; _ a _ _
      (filter #(and (= v (.-v %)) (= tx (.-tx %))) eavt) ;; _ _ v tx
      (filter #(= v (.-v %)) eavt)                       ;; _ _ v _
      (filter #(= tx (.-tx %)) eavt)                     ;; _ _ _ tx
      eavt]))                                            ;; _ _ _ _

  IIndexAccess
  (-datoms [this index cs]
    (btset/slice (get this index) (components->pattern index cs)))

  (-seek-datoms [this index cs]
    (btset/slice (get this index) (components->pattern index cs) (Datom. nil nil nil nil nil)))

  (-index-range [_ attr start end]
    (btset/slice avet (Datom. nil attr start nil nil)
                      (Datom. nil attr end nil nil))))

(defrecord FilteredDB [unfiltered-db pred]
  Object
  (toString [this]
    (pr-str* this))
  
  IDB
  (-schema [_] (-schema unfiltered-db))
  (-refs   [_] (-refs unfiltered-db))
  
  ISearch
  (-search [_ pattern]
    (filter pred (-search unfiltered-db pattern)))
  
  IIndexAccess
  (-datoms [_ index cs]
    (filter pred (-datoms unfiltered-db index cs)))

  (-seek-datoms [_ index cs]
    (filter pred (-seek-datoms unfiltered-db index cs)))

  (-index-range [_ attr start end]
    (filter pred (-index-range unfiltered-db attr start end))))
  
(defn- -equiv-index [x y]
  (and (= (count x) (count y))
    (loop [xs (seq x)
           ys (seq y)]
      (cond
        (nil? xs) true
        (= (first xs) (first ys)) (recur (next xs) (next ys))
        :else false))))

(defn- -hash-db [db]
  (or (.-__hash db)
      (set! (.-__hash db) (hash-coll (-datoms db :eavt [])))))

(defn- -equiv-db [this other]
  (and (or (instance? DB other) (instance? FilteredDB other))
       (= (-schema this) (-schema other))
       (-equiv-index (-datoms this :eavt []) (-datoms other :eavt []))))

(extend-type DB
  IHash (-hash [this] (-hash-db this))
  IEquiv (-equiv [this other] (-equiv-db this other)))

(extend-type FilteredDB
  IHash (-hash [this] (-hash-db this))
  IEquiv (-equiv [this other] (-equiv-db this other)))

(defrecord TxReport [db-before db-after tx-data tempids tx-meta])

(defn ^boolean multival? [db attr]
  (= (get-in (-schema db) [attr :db/cardinality]) :db.cardinality/many))

(defn ^boolean ref? [db attr]
  (contains? (-refs db) attr))

(defn ^boolean component? [db attr]
  (get-in (-schema db) [attr :db/isComponent] false))

(defn unique [db attr]
  (get-in (-schema db) [attr :db/unique]))

;;;;;;;;;; Transacting

(defn- current-tx [report]
  (inc (get-in report [:db-before :max-tx])))

(defn- next-eid [db]
  (inc (:max-eid db)))

(defn- advance-max-eid [db eid]
  (cond-> db
    (and (> eid (:max-eid db))
         (< eid tx0)) ;; do not trigger advance if transaction id was referenced
      (assoc :max-eid eid)))

(defn- allocate-eid
  ([report eid]
     (update-in report [:db-after] advance-max-eid eid))
  ([report e eid]
     (-> report
       (assoc-in [:tempids e] eid)
       (update-in [:db-after] advance-max-eid eid))))

(defn validate-datom [db datom]
  (when (and (.-added datom)
             (unique db (.-a datom)))
    (when-let [found (not-empty (-datoms db :avet [(.-a datom) (.-v datom)]))]
      (throw (ex-info (str "Cannot add " datom " because of unique constraint: " found)
                      {:error :transact/unique
                       :attribute (.-a datom)
                       :datom datom})))))

;; In context of `with-datom` we can use faster comparators which
;; do not check for nil (~10-15% performance gain in `transact`)

(defn- with-datom [db datom]
  (validate-datom db datom)
  (if (.-added datom)
    (-> db
      (update-in [:eavt] btset/btset-conj datom cmp-datoms-eavt-quick)
      (update-in [:aevt] btset/btset-conj datom cmp-datoms-aevt-quick)
      (update-in [:avet] btset/btset-conj datom cmp-datoms-avet-quick)
      (advance-max-eid (.-e datom)))
    (let [removing (first (-search db [(.-e datom) (.-a datom) (.-v datom)]))]
      (-> db
        (update-in [:eavt] btset/btset-disj removing cmp-datoms-eavt-quick)
        (update-in [:aevt] btset/btset-disj removing cmp-datoms-aevt-quick)
        (update-in [:avet] btset/btset-disj removing cmp-datoms-avet-quick)))))

(defn- transact-report [report datom]
  (-> report
      (update-in [:db-after] with-datom datom)
      (update-in [:tx-data] conj datom)))

(defn- ^boolean reverse-ref? [attr]
  (cond
    (keyword? attr)
    (= "_" (nth (name attr) 0))
    
    (string? attr)
    (boolean (re-matches #"(?:([^/]+)/)?_([^/]+)" attr))
   
    :else
    (throw (ex-info (str "Bad attribute type: " attr ", expected keyword or string")
                    {:error :transact/syntax, :attribute attr}))))

(defn- reverse-ref [attr]
  (cond
    (keyword? attr)
    (if (reverse-ref? attr)
      (keyword (namespace attr) (subs (name attr) 1))
      (keyword (namespace attr) (str "_" (name attr))))

   (string? attr)
   (let [[_ ns name] (re-matches #"(?:([^/]+)/)?([^/]+)" attr)]
     (if (= "_" (nth name 0))
       (if ns (str ns "/" (subs name 1)) (subs name 1))
       (if ns (str ns "/_" name) (str "_" name))))
   
   :else
    (throw (ex-info (str "Bad attribute type: " attr ", expected keyword or string")
                    {:error :transact/syntax, :attribute attr}))))

(defn- validate-eid [eid at]
  (when-not (number? eid)
    (throw (ex-info (str "Bad entity id " eid " at " at ", expected number")
                    {:error :transact/syntax, :entity-id eid, :context at}))))

(defn- validate-attr [attr at]
  (when-not (or (keyword? attr) (string? attr))
    (throw (ex-info (str "Bad entity attribute " attr " at " at ", expected keyword or string")
                    {:error :transact/syntax, :attribute attr, :context at}))))

(defn- validate-val [v at]
  (when (nil? v)
    (throw (ex-info (str "Cannot store nil as a value at " at)
                    {:error :transact/syntax, :value v, :context at}))))

(defn- explode [db entity]
  (let [eid (:db/id entity)]
    (for [[a vs] entity
          :when  (not= a :db/id)
          :let   [_          (validate-attr a {:db/id eid, a vs})
                  reverse?   (reverse-ref? a)
                  straight-a (if reverse? (reverse-ref a) a)
                  _          (when (and reverse? (not (ref? db straight-a)))
                               (throw (ex-info (str "Bad attribute " a ": reverse attribute name requires {:db/valueType :db.type/ref} in schema")
                                               {:error :transact/syntax, :attribute a, :context {:db/id eid, a vs}})))]
          v      (if (and (or (array? vs) (coll? vs))
                          (not (map? vs))
                          (or reverse? (multival? db a)))
                   vs [vs])]
      (if (and (ref? db straight-a) (map? v)) ;; another entity specified as nested map
        (assoc v (reverse-ref a) eid)
        (if reverse?
          [:db/add v   straight-a eid]
          [:db/add eid straight-a v])))))

(defn- transact-add [report [_ e a v :as ent]]
  (validate-eid e ent)
  (validate-attr a ent)
  (validate-val v ent)
  (let [tx    (current-tx report)
        db    (:db-after report)
        datom (Datom. e a v tx true)]
    (when (and (ref? db a) (not (number? v)))
      (throw (ex-info (str "Bad value at " ent ", expected number due to {:db/valueType :db.type/ref}")
                      {:error :transact/syntax, :value v, :context ent} )))
    (if (multival? db a)
      (if (empty? (-search db [e a v]))
        (transact-report report datom)
        report)
      (if-let [old-datom (first (-search db [e a]))]
        (if (= (.-v old-datom) v)
          report
          (-> report
            (transact-report (Datom. e a (.-v old-datom) tx false))
            (transact-report datom)))
        (transact-report report datom)))))

(defn- transact-retract-datom [report d]
  (let [tx (current-tx report)]
    (transact-report report (Datom. (.-e d) (.-a d) (.-v d) tx false))))

(defn- tx-id? [e]
  (or (= e :db/current-tx)
      (= e ":db/current-tx"))) ;; for datascript.js interop

(defn- retract-components [db datoms]
  (into #{} (comp
              (filter #(component? db (.-a %)))
              (map #(vector :db.fn/retractEntity (.-v %)))) datoms))

(defn- transact-tx-data [report es]
  (when-not (or (nil? es) (sequential? es))
    (throw (ex-info (str "Bad transaction data " es ", expected sequential collection")
                    {:error :transact/syntax, :tx-data es})))
  (let [[entity & entities] es
        db (:db-after report)]
    (cond
      (nil? entity)
        (-> report
            (assoc-in  [:tempids :db/current-tx] (current-tx report))
            (update-in [:db-after :max-tx] inc))

      (map? entity)
        (cond
          (tx-id? (:db/id entity))
            (let [entity (assoc entity :db/id (current-tx report))]
              (recur report (concat (explode db entity) entities)))
          (nil? (:db/id entity))
            (let [eid    (next-eid db)
                  entity (assoc entity :db/id eid)]
              (recur (allocate-eid report eid)
                     (concat [entity] entities)))
          :else
            (recur report (concat (explode db entity) entities)))

      (sequential? entity)
        (let [[op e a v] entity]
          (cond
            (= op :db.fn/call)
              (let [[_ f & args] entity]
                (recur report (concat (apply f db args) entities)))

            (= op :db.fn/cas)
              (let [[_ e a ov nv] entity
                    _ (validate-eid e entity)
                    _ (validate-attr a entity)
                    _ (validate-val ov entity)
                    _ (validate-val nv entity)
                    datoms (-search db [e a])]
                (if (multival? db a)
                  (if (some #(= (.-v %) ov) datoms)
                    (recur (transact-add report [:db/add e a nv]) entities)
                    (throw (ex-info (str ":db.fn/cas failed on datom [" e " " a " " (map :v datoms) "], expected " ov)
                                    {:error :transact/cas, :old datoms, :expected ov, :new nv})))
                  (let [v (.-v (first datoms))] 
                    (if (= v ov)
                      (recur (transact-add report [:db/add e a nv]) entities)
                      (throw (ex-info (str ":db.fn/cas failed on datom [" e " " a " " v "], expected " ov)
                                      {:error :transact/cas, :old (first datoms), :expected ov, :new nv }))))))
           
            (tx-id? e)
              (recur report (concat [[op (current-tx report) a v]] entities))
           
            (and (ref? db a) (tx-id? v))
              (recur report (concat [[op e a (current-tx report)]] entities))

            (neg? e)
              (if-let [eid (get-in report [:tempids e])]
                (recur report (concat [[op eid a v]] entities))
                (recur (allocate-eid report e (next-eid db)) es))

            (and (ref? db a) (neg? v))
              (if-let [vid (get-in report [:tempids v])]
                (recur report (concat [[op e a vid]] entities))
                (recur (allocate-eid report v (next-eid db)) es))

            (= op :db/add)
              (recur (transact-add report entity) entities)

            (= op :db/retract)
              (do
                (validate-eid e entity)
                (validate-attr a entity)
                (validate-val v entity)
                (if-let [old-datom (first (-search db [e a v]))]
                  (recur (transact-retract-datom report old-datom) entities)
                  (recur report entities)))

            (= op :db.fn/retractAttribute)
              (do
                (validate-eid e entity)
                (validate-attr a entity)
                (let [datoms (-search db [e a])]
                  (recur (reduce transact-retract-datom report datoms)
                         (concat (retract-components db datoms) entities))))

            (= op :db.fn/retractEntity)
              (do
                (validate-eid e entity)
                (let [e-datoms (-search db [e])
                      v-datoms (mapcat (fn [a] (-search db [nil a e])) (-refs db))]
                  (recur (reduce transact-retract-datom report (concat e-datoms v-datoms))
                         (concat (retract-components db e-datoms) entities))))
           
           :else
             (throw (ex-info (str "Unknown operation at " entity ", expected :db/add, :db/retract, :db.fn/call, :db.fn/retractAttribute or :db.fn/retractEntity")
                             {:error :transact/syntax, :operation op, :tx-data entity}))))
     :else
       (throw (ex-info (str "Bad entity type at " entity ", expected map or vector")
                       {:error :transact/syntax, :tx-data entity}))
     )))
