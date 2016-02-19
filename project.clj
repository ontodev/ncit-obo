(defproject ncit-obo "0.1.0-SNAPSHOT"
  :description "Convert NCI Thesaurus OWL to OBO-standard OWL"
  :url "https://github.com/ontodev/ncit-obo"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [clj-yaml "0.4.0"]
                 [org.obolibrary/robot "0.0.1-SNAPSHOT"]]
  :repositories [["local_maven_repo"
                  {:url "file:local_maven_repo"
                   :username ""
                   :password ""}]]
  :main ^:skip-aot ncit-obo.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
