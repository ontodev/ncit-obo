(ns ncit-obo.align
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.math.combinatorics :as combo]
            [clojure.core.reducers :as r]
            ;[clj-fuzzy.metrics :refer [levenshtein]]
            [clojure-stemmer.porter.stemmer :refer [stemming]]
            [clucy.core :as clucy]))
            ;[clojure.core.matrix :as m]


(set! *warn-on-reflection* true)

;; This code compares sets of terms from two different ontologies,
;; using their annotations to determine the probability that:
;;
;; 1. X EquivalentTo Y
;; 2. X SubClassOf Y
;; 3. Y SubClassOf X
;; 4. None of the above

;; We process string annotations into a normalized form for comparison.
;; Apache Lucene is used for some of these steps.
;;
;; 1. break string into tokens
;; 2. remove stop words
;; 3. stem tokens
;; 4. sort
;; 5. concatenate into a single string

(def stop-words
  #{"of" "a" "an" "the" "process"})

;; Loop over the tokens,
;; equivalent to while (tokenizer.incrementToken()) {},
;; collect strings (using CharTermAttribute),
;; and return a sorted, concatentated string.

(defn process-annotation
  "Given an input string,
   return a single, processed, concatenated string for comparison."
  [input]
  (->> (-> input
           string/lower-case
           (string/split #"\W+"))
       (remove (partial contains? stop-words))
       (map stemming)
       sort
       (string/join "")))

(defn shorten
  "Given a string with IRIs,
   return a string with CURIEs."
  [s]
  (-> s
      (string/replace "http://www.w3.org/2000/01/rdf-schema#" "rdfs:")
      (string/replace "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#" "ncit:")
      (string/replace "http://www.geneontology.org/formats/oboInOwl#" "oio:")
      (string/replace "http://purl.obolibrary.org/obo/GO_" "GO:")))

;; A "SynonymMap" represents a synonym for a term.

;; TODO: define schema for SynonymMap

(defn process-annotations
  "Given a path a CSV file with the results of a SPARQL query (subclass.rq)
   and return a list of SynonymMaps."
  [path]
  (with-open [reader (io/reader path)]
    (->> reader
         csv/read-csv
         rest
         (map
          (fn [[s p o]]
            {:subject      s
             :predicate    p
             :synonym      o
             :synonym-type :exact ; TODO: allow for other types
             :normalized   (process-annotation o)}))
         doall)))

;; These are the headers of our output table.

(def headers
  ["Term 1"
   "Term 2"
   "Predicate 1"
   "Predicate 2"
   "Match Type"
   "Synonym 1"
   "Synonym 2"
   "Distance"
   "Normalized Synonym 1"
   "Normalized Synonym 2"])

(defn format-row
  "Given a map of results,
   return a vector for a row of the output table."
  [{:keys [syn1 syn2 distance]}]
  [(:subject syn1)
   (:subject syn2)
   (:predicate syn1)
   (:predicate syn2)
   "exact-exact"
   (:synonym syn1)
   (:synonym syn2)
   distance
   (:normalized syn1)
   (:normalized syn2)])

;; We use a global counter to help show progress.

(def counter (atom 1))

;; These functions compare pairs of synonyms
;; by the Levenshtein distance of their normalized strings.

;; Naive Levenshtein implementation
;; https://rosettacode.org/wiki/Levenshtein_distance#Clojure
(defn levenshtein [str1 str2]
  (let [len1 (count str1)
        len2 (count str2)]
    (cond (zero? len1) len2
          (zero? len2) len1
          :else
          (let [cost (if (= (first str1) (first str2)) 0 1)]
            (min (inc (levenshtein (rest str1) str2))
                 (inc (levenshtein str1 (rest str2)))
                 (+ cost
                    (levenshtein (rest str1) (rest str2))))))))

;; There are supposedly faster Levenshtein implementations here:
;; http://stackoverflow.com/a/25771676
;; but both failed for me when used with pmap.
;; I couldn't figure out why.

#_(defn da-lev [^String str1 ^String str2]
    (let [l1 (count str1)
          l2 (count str2)
          mx (m/new-matrix :ndarray (inc l1) (inc l2))]
      (m/mset! mx 0 0 0)
      (dotimes [i l1]
        (m/mset! mx (inc i) 0 (inc i)))
      (dotimes [j l2]
        (m/mset! mx 0 (inc j) (inc j)))
      (dotimes [i l1]
        (dotimes [j l2]
          (let [i+ (inc i) j+ (inc j)
                i- (dec i) j- (dec j)
                cost (if (= (.charAt str1 i)
                            (.charAt str2 j))
                       0 1)]
            (m/mset! mx i+ j+
                     (min (inc (m/mget mx i j+))
                          (inc (m/mget mx i+ j))
                          (+ (m/mget mx i j) cost)))
            (if (and (pos? i) (pos? j)
                     (= (.charAt str1 i)
                        (.charAt str2 j-))
                     (= (.charAt str1 i-)
                        (.charAt str2 j)))
              (m/mset! mx i+ j+
                       (min (m/mget mx i+ j+)
                            (+ (m/mget mx i- j-) cost)))))))
      (m/mget mx l1 l2)))

(defn measure
  [syn1 syn2]
  [syn1
   syn2
   ;(da-lev (:normalized syn1) (:normalized syn2))
   (levenshtein (:normalized syn1) (:normalized syn2))])

(defn find-best
  "Given a sequence of SynonymMaps for a branch,
   and a sequence of SynonymMaps for a single subject,
   find the shortest distance between synonyms
   and return a map for the best result."
  [branch synonyms]
  (println
   (format
    "MATCHING %d: %s %s"
    (swap! counter inc)
    (-> synonyms first :subject shorten)
    (-> synonyms first :synonym)))
  (->> (combo/cartesian-product synonyms branch)
       (map (partial apply measure))
       (apply min-key #(nth % 2))
       (zipmap [:syn1 :syn2 :distance])))

;; These alternative functions use a Lucene index.
;; They are *much* faster,
;; but we don't have as much control over the measure function.
;; Maybe this can be improved.
;; WARN: Lucene likes the normalized synonyms to be joined with spaces.

(defn measure2
  [index synonym]
  (let [results  (clucy/search index (:normalized synonym) 1)
        distance (:_max-score (meta results))]
    [synonym (first results) (if (number? distance) distance 0.0)]))

(defn find-best2
  "Given a sequence of SynonymMaps for a branch,
   and a sequence of SynonymMaps for a single subject,
   find the shortest distance between synonyms
   and return a map for the best result."
  [index synonyms]
  (println
   (format
    "MATCHING %d: %s %s"
    (swap! counter inc)
    (-> synonyms first :subject shorten)
    (-> synonyms first :synonym)))
  (->> synonyms
       (map (partial measure2 index))
       (apply min-key #(nth % 2))
       (zipmap [:syn1 :syn2 :distance])))

;; A set of terms for testing.

(def test-terms
  #{"http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#C16399" ; Cell Differentiation Process
    "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#C28391" ; B-Cell Differentiation
    "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#C19064"}) ; Thymic T-Cell Selection


;; This is the main function:
;;
;; 1. runs two SPARQL queries and processes the results into SynonymMaps
;; 2. group branch1 by consecutive subjects (see ORDER BY above)
;; 3. run find-best for each subject -- in parallel using pmap
;; 4. sort by distance
;; 5. write results to a table

(defn align
  "Given two paths to results of a SPARQL query (subclass.rq)
   and an output file path,
   match the first branch against the second branch,
   and write a report to the output file."
  [input-path-1 input-path-2 output-path]
  (let [branch2 (process-annotations input-path-2)]
    ;; When using the Lucene methods,
    ;; you need to create and fill an index.
    ; index   (clucy/memory-index)
    ;(apply clucy/add index branch2)
    (reset! counter 0)
    (->> (process-annotations input-path-1)
         ;(filter #(contains? test-terms (:subject %)))
         (partition-by :subject)
         ;(take 20)
         ;(map (partial find-best branch2))
         (pmap (partial find-best branch2))
         ;(pmap (partial find-best2 index))
         (sort-by :distance)
         (map format-row)
         (concat [headers])
         (map (partial string/join "\t"))
         (map shorten)
         (string/join "\n")
         (spit output-path))))
