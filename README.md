# DataScript

> What if creating a database would be as cheap as creating a Hashmap?

An immutable in-memory database and Datalog query engine in ClojureScript.

DataScript is meant to run inside the browser. It is cheap to create, quick to query and ephemeral. You create a database on page load, put some data in it, track changes, do queries and forget about it when the user closes the page.

DataScript databases are immutable and based on persistent data structures. In fact, they’re more like data structures than databases (think Hashmap). Unlike querying a real SQL DB, when you query DataScript, it all comes down to a Hashmap lookup. Or series of lookups. Or array iteration. There’s no particular overhead to it. You put a little data in it, it’s fast. You put in a lot of data, well, at least it has indexes. That should do better than you filtering an array by hand anyway. The thing is really lightweight.

The intention with DataScript is to be a basic building block in client-side applications that needs to track a lot of state during their lifetime. There’s a lot of benefits:

- Central, uniform approach to manage all application state. Clients working with state become decoupled and independent: rendering, server sync, undo/redo do not interfere with each other.
- Immutability simplifies things even in a single-threaded browser environment. Keep track of app state evolution, rewind to any point in time, always render consistent state, sync in background without locking anybody.
- Datalog query engine to answer non-trivial questions about current app state.
- Structured format to track data coming in and out of DB. Datalog queries can be run against it too.

Also check out this blog post about [how DataScript fits into the current webdev ecosystem](http://tonsky.me/blog/decomposing-web-app-development/).

## Usage examples [![Build Status](https://travis-ci.org/tonsky/datascript.svg?branch=master)](https://travis-ci.org/tonsky/datascript)

```clj
:dependencies [
  [org.clojure/clojurescript "0.0-2280"]
  ...
  [datascript "0.2.0"]
]
```

```clj
(require '[datascript :as d])

;; Implicit join, multi-valued attribute

(let [schema {:aka {:db/cardinality :db.cardinality/many}}
      conn   (d/create-conn schema)]
  (d/transact! conn [ { :db/id -1
                        :name  "Maksim"
                        :age   45
                        :aka   ["Maks Otto von Stirlitz", "Jack Ryan"] } ])
  (d/q '[ :find  ?n ?a
          :where [?e :aka "Maks Otto von Stirlitz"]
                 [?e :name ?n]
                 [?e :age  ?a] ]
       @conn))

;; => #{ ["Maksim" 45] }


;; Desctucturing, function call, predicate call, query over collection

(d/q '[ :find  ?k ?x
        :in    [[?k [?min ?max]] ...] ?range
        :where [(?range ?min ?max) [?x ...]]
               [(even? ?x)] ]
      { :a [1 7], :b [2 4] }
      range)

;; => #{ [:a 2] [:a 4] [:a 6] [:b 2] }


;; Recursive rule

(d/q '[ :find  ?u1 ?u2
        :in    $ %
        :where (follows ?u1 ?u2) ]
      [ [1 :follows 2]
        [2 :follows 3]
        [3 :follows 4] ]
     '[ [(follows ?e1 ?e2)
         [?e1 :follows ?e2]]
        [(follows ?e1 ?e2)
         [?e1 :follows ?t]
         (follows ?t ?e2)] ])

;; => #{ [1 2] [1 3] [1 4]
;;       [2 3] [2 4]
;;       [3 4] }


;; Aggregates

(d/q '[ :find ?color (max ?amount ?x) (min ?amount ?x)
        :in   [[?color ?x]] ?amount ]
     [[:red 10]  [:red 20] [:red 30] [:red 40] [:red 50]
      [:blue 7] [:blue 8]]
     3)

;; => [[:red  [30 40 50] [10 20 30]]
;;     [:blue [7 8] [7 8]]]
```

## Using from vanilla JS

DataScript can be used from any JS engine without additional dependencies:

```html
<script src="datascript-0.2.0.min.js"></script>
```

[Download datascript-0.2.0.min.js](https://github.com/tonsky/datascript/releases/download/0.2.0/datascript-0.2.0.min.js), 40k gzipped.

Queries:

* Query and rules should be EDN passed as strings
* Results of `q` are returned as regular JS arrays

Transactions:

* Use strings such as `":db/id"`, `":db/add"`, etc. instead of db-namespaced keywords
* Use regular JS arrays and objects to pass data to `transact` and `with_datoms`

Transaction reports:

* `report.tempids` has string keys (`"-1"` for entity tempid `-1`)
* `report.tx_data` is list of objects with `e`, `a`, `v`, `tx` and `added` keys (instead of Datoms)

Check out [test/js/js.html](test/js/js.html) for usage examples.

## Project status

Alpha quality. Half of the features done, a lot of cases where error reporting is missing, no docs (use examples & Datomic documentation).

The following features are supported:

* Database as a value: each DB is an immutable value. New DBs are created on top of old ones, but old ones stay perfectly valid too
* Triple store model
* EAVT, AEVT and AVET indexes
* Multi-valued attributes via `:db/cardinality :db.cardinality/many`
* Database “mutations” via `transact!`
* Callback-based analogue to txReportQueue via `listen!`
* Direct index lookup and iteration via `datoms` and `seek-datoms`

Query engine features:

* Implicit joins
* Query over DB or regular collections
* Parameterized queries via `:in` clause
* Tuple, collection, relation binding forms in `:in` clause
* Query over multiple DB/collections
* Predicates and user functions in query
* Rules, recursive rules
* Aggregates

Interface differences:

* Custom query functions and aggregates should be passed as source instead of being referenced by symbol (due to lack of `resolve` in CLJS)
* Conn is just an atom storing last DB value, use `@conn` instead of `(d/db conn)`
* Instead of `#db/id[:db.part/user -100]` just use `-100` in place of `:db/id` or entity id
* Transactor functions can be called as `[:db.fn/call f args]` where `f` is a function reference and will take db as first argument (thx [@thegeez](https://github.com/thegeez))
* Additional `:db.fn/retractAttribute` shortcut
* `transact!` and `transact` return `TxReport`, `with` returns new DB value

Expected soon:

* Better error reporting
* Support for components in schema
* Lazy entities
* Proper documentation

## Differences from Datomic

* DataScript is built totally from scratch and is not related by any means to the popular Clojure database Datomic
* Runs in a browser
* Simplified schema, not queryable
* No need to declare attributes except for `:db/cardinality` `:db.cardinality/many` and `:db/valueType` `:db.type/ref`
* Any type can be used for values. It’s better if values are immutable and fast to compare
* No `db/ident` attributes, keywords are _literally_ attribute values, no integer id behind them
* AVET index for all datoms
* No schema migrations
* No history support, though history can be implemented on top of immutable DB values
* No cache segments management, no laziness. Entire DB must reside in memory
* No facilities to persist, transfer over the wire or sync DB with the server
* No pluggable storage options, no full-text search, no partitions
* No external dependencies
* Free

Some of these are omitted intentionally. Different apps have different needs in storing/transfering/keeping track of DB state. This library is a foundation to build exactly the right storage solution for your needs without selling too much “vision”.

## License

Copyright © 2014 Nikita Prokopov

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
