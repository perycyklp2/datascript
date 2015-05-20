(defproject datascript "0.11.1"
  :description "An implementation of Datomic in-memory database and Datalog query engine in ClojureScript"
  :license {:name "Eclipse"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/tonsky/datascript"
  
  :dependencies [
    [org.clojure/clojure "1.7.0-beta3" :scope "provided"]
    [org.clojure/clojurescript "0.0-3211" :scope "provided"]
  ]
  
  :global-vars {
    *warn-on-reflection* true
    *unchecked-math* :warn-on-boxed
  }
  
  :jvm-opts ["-Xmx2g"]

  :aliases {"test-clj"
            ["test"
             "datascript.test.core"
             "datascript.test.btset"
             "datascript.test.components"
             "datascript.test.entity"
             "datascript.test.explode"
             "datascript.test.filter"
             "datascript.test.index"
             "datascript.test.listen"
             "datascript.test.lookup-refs"
             "datascript.test.parser"
             "datascript.test.parser-find"
             "datascript.test.parser-rules"
             "datascript.test.parser-query"
             "datascript.test.parser-where"
             "datascript.test.perf"
             "datascript.test.pull-api"
             "datascript.test.pull-parser"
             "datascript.test.query"
             "datascript.test.query-aggregates"
             "datascript.test.query-find-specs"
             "datascript.test.query-fns"
             "datascript.test.query-pull"
             "datascript.test.query-rules"
             "datascript.test.query-v3"
             "datascript.test.serialization"
             "datascript.test.transact"
             "datascript.test.validation"
             "datascript.test.upsert"]}
  
  :cljsbuild { 
    :builds [
      { :id "release"
        :source-paths ["src"]
        :assert false
        :compiler {
          :output-to     "release-js/datascript.bare.js"
          :optimizations :advanced
          :pretty-print  false
          :elide-asserts true
          :output-wrapper false 
          :warnings      {:single-segment-namespace false}
        }
        :notify-command ["release-js/wrap_bare.sh"]}
  ]}

  :profiles {
    :dev {
      :plugins [
        [lein-cljsbuild "1.0.6"]
        [com.cemerick/clojurescript.test "0.3.3"]
      ]
      :cljsbuild { 
        :builds [
          { :id "advanced"
            :source-paths ["src" "test"]
            :compiler {
              :main          datascript.test
              :output-to     "target/datascript.js"
;;               :output-dir    "target/advanced"
              :optimizations :advanced
              :source-map    "target/datascript.js.map"
              :pretty-print  true
              :warnings     {:single-segment-namespace false}
              :recompile-dependents false
            }}
          { :id "none"
            :source-paths ["src" "test"]
            :compiler {
              :main          datascript.test
              :output-to     "target/datascript.js"
              :output-dir    "target/none"
              :optimizations :none
              :source-map    true
              :warnings     {:single-segment-namespace false}
              :recompile-dependents false
            }}
        ]
        :test-commands {
          "datascript.test"    [ "phantomjs" :runner "target/datascript.js" ]
          "datascript.test.js" [ "phantomjs" "test/js/runner.js" ]
        }
      }
    }
  }
  
  :clean-targets ^{:protect false} [
    "target"
    "release-js/datascript.bare.js"
    "release-js/datascript.js"
  ]
)
