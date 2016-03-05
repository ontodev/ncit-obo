(ns ncit-obo.core
  (:require [ncit-obo.convert :refer [convert]]
            [ncit-obo.align :refer [align]])
  (:gen-class))

(defn usage
  []
  (println "USAGE: covert config input output")
  (println "    OR align output graph1 root1 graph2 root2 "))

(defn -main
  [& args]
  (condp = (first args)
    "convert" (apply convert (rest args))
    "align"   (apply align (rest args))
    "help"    (usage)
    "-h"      (usage)
    "--help"  (usage)
    (do
      (apply println "Unknown command:" args)
      (usage)
      (System/exit 1)))
  (shutdown-agents))
