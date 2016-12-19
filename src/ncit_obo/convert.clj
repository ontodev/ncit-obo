(ns ncit-obo.convert
  (:require [clojure.xml]
            [clojure.string :as string]
            [clj-yaml.core :as yaml])
  (:import (org.obolibrary.robot IOHelper)
           (org.semanticweb.owlapi.apibinding OWLManager)
           (org.semanticweb.owlapi.model
            AxiomType
            IRI
            OWLAnnotationAssertionAxiom
            OWLAnnotationProperty
            OWLAnnotationSubject
            OWLAnnotationValue
            OWLAxiom
            OWLClass
            OWLDataFactory
            OWLDatatype
            OWLEquivalentClassesAxiom
            OWLLiteral
            OWLOntology
            OWLOntologyManager
            OWLSubClassOfAxiom)))

(set! *warn-on-reflection* true)

;;;; OWL Helper Functions

(def ^IOHelper io-helper (IOHelper.))
(def ^OWLOntologyManager output-manager (. OWLManager createOWLOntologyManager))
(def ^OWLDataFactory data-factory (. output-manager getOWLDataFactory))

(defn annotation-property
  "Given an IRI string, return an OWL Annotation Property."
  [^String iri]
  (. data-factory getOWLAnnotationProperty (IRI/create iri)))

(def iao-definition
  (annotation-property "http://purl.obolibrary.org/obo/IAO_0000115"))

(def owl-deprecated
  (annotation-property "http://www.w3.org/2002/07/owl#deprecated"))

(def ncit-deprecated
  (annotation-property "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#P310"))

(defn get-datatype
  [^OWLAnnotationAssertionAxiom axiom]
  (let [^OWLAnnotationValue value (. axiom getValue)
        ^OWLLiteral literal (.. value asLiteral orNull)
        ^OWLDatatype datatype (. literal getDatatype)
        ^IRI iri (. datatype getIRI)]
    (. iri toString)))

(defn get-literal
  [^OWLAnnotationAssertionAxiom axiom]
  (let [^OWLAnnotationValue value (. axiom getValue)
        ^OWLLiteral literal (.. value asLiteral orNull)]
    (. literal getLiteral)))

;;;; Configuration

;;; We read a YAML file with several maps,
;;; and return a nested config map.

;;; - :prefixes map from prefix to URI
;;;   used to expand CURIEs to URIs
;;; - :mappings map from CURIE to CURIE
;;;   used to build :translations
;;; - :translations map from URI to URI
;;;   used to translate annotation properties
;;;   and XMLLiteral element names to annotation properties
;;; - :xml map of maps from the CURIE for a root element to:
;;;     - :primary the XML element to use for the main annotation property
;;;     - :secondary a list of other XML elements for annotating the annotation property

(defn expand
  "Given a map of prefixes and a string,
   try to expand string and return it,
   or just return it."
  [prefixes s]
  (let [[p l] (string/split s #":" 2)
        p (keyword p)]
    (if (find prefixes p)
      (str (get prefixes (keyword p)) l)
      s)))

(defn add-obsolete
  "Given a config map with :prefixes and :mappings maps,
   assoc :obsolete with a property."
  [config]
  (-> config
      (update-in
       [:obsolete :property]
       #(annotation-property (expand (:prefixes config) %)))
      (update-in [:obsolete :values] set)))

(defn add-translation
  "Given a config map with :prefixes and :mappings maps,
   assoc a :translation map,
   and return the config map."
  [config]
  (assoc
   config
   :translations
   (->> config
        :mapping
        (map
         (fn [[k v]]
           [(expand (:prefixes config) (name k))
            (expand (:prefixes config) v)]))
        (into {}))))

(defn read-config
  "Given a configuration path, read the YAML
   and return a nested config map."
  [config-path]
  (->> config-path
       slurp
       yaml/parse-string
       add-obsolete
       add-translation))

(defn get-property
  "Given the config map and an OWLAnnotationAssertionAxiom,
   if the property should be translated,
   then return the new property,
   otherwise return the axiom's same property."
  [config ^OWLAnnotationAssertionAxiom axiom]
  (let [^OWLAnnotationProperty property (.. axiom getProperty getIRI toString)]
    (if (find (:translations config) property)
      (annotation-property (get (:translations config) property))
      (. axiom getProperty))))

(defn obsolete?
  [{:keys [obsolete] :as config} ^OWLAxiom axiom]
  (and
   (= (. axiom getAxiomType) (. AxiomType ANNOTATION_ASSERTION))
   (= (. ^OWLAnnotationAssertionAxiom axiom getProperty) (:property obsolete))
   (contains? (:values obsolete) (get-literal axiom))))

(defn get-obsoletes
  "Given an ontology and an annotation property,
 return a set of subjects with that annotation perpoerty."
  [^OWLOntology ontology config]
  (->> (. ontology getAxioms)
       (filter (partial obsolete? config))
       (map #(. ^OWLAnnotationAssertionAxiom % getSubject))
       set))

; <ncicp:ComplexTerm xmlns:ncicp="http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#">
;  <ncicp:term-name>Display Name</ncicp:term-name>
;  <ncicp:term-group>SY</ncicp:term-group>
;  <ncicp:term-source>NCI</ncicp:term-source>
; </ncicp:ComplexTerm>" ,}))))

; <ncicp:ComplexDefinition xmlns:ncicp="http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#">
;  <ncicp:def-definition>Provides an alternative Preferred Name for use in some NCI systems.</ncicp:def-definition>
;  <ncicp:def-source>NCI</ncicp:def-source>
; </ncicp:ComplexDefinition>" ,

; <ncicp:ComplexGOAnnotation xmlns:ncicp="http://ncicb.nci.nih.gov/xml/owl/EVS/ComplexProperties.xsd#">
;  <ncicp:go-term>ATP binding</ncicp:go-term>
;  <ncicp:go-id>GO:0005524</ncicp:go-id>
;  <ncicp:go-evi>TAS</ncicp:go-evi>
;  <ncicp:source-date>29-SEP-2003</ncicp:source-date>
;  <ncicp:go-source>CGAP</ncicp:go-source>
; </ncicp:ComplexGOAnnotation>" ,

(defn parse
  "Given an XML string, return a nested map representation."
  [^String s]
  (try
    (clojure.xml/parse
     (java.io.ByteArrayInputStream. (.getBytes s)))
    (catch Exception e nil)))

(defn get-primary-value
  "Given the primary element name and and some parsed XML,
   filter the XML content for the first matching tag,
   then return its content as an OWLLiteral string."
  [primary xml]
  (let [^String value
        (->> xml
             :content
             (filter #(= (name (:tag %)) primary))
             (map :content)
             first
             (apply str))]
    (. data-factory getOWLLiteral value)))

(defn get-secondary-annotation
  "Given the config map, a tag name, and a content string,
   try to find a translation of the tag
   return an OWLAnnotation."
  [config tag ^String content]
  (if-let [^String property-iri
           (->> tag
                (expand (:prefixes config))
                (get (:translations config)))]
    (. data-factory
       getOWLAnnotation
       (. data-factory
          getOWLAnnotationProperty
          (IRI/create property-iri))
       (. data-factory
          getOWLLiteral
          content))
    (throw (Exception. (str "No translation for " tag)))))

(defn get-annotations
  "Given the config map and an OWLAnnotationAssertionAxiom,
   if the axiom value is an XMLLiteral then return a list
   with the primary value followed by zero or more annotations,
   otherwise return a list with just the axiom's value."
  [config ^OWLAnnotationAssertionAxiom axiom]
  (try
    (if (and (instance? OWLLiteral (. axiom getValue))
             (= (get-datatype axiom)
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral"))
      (let [xml       (parse (get-literal axiom))
            root      (:tag xml)
            primary   (-> config :xml root :primary)
            secondary (-> config :xml root :secondary set)]
        ;(when-not primary
        ;  (throw (Exception. (str "Unknown XML tag " root))))
        (if (and xml root primary)
          (concat
           [(get-primary-value primary xml)]
           (->> (:content xml)
                (filter #(contains? secondary (name (:tag %))))
                (map (juxt #(name (:tag %))
                           #(apply str (:content %))))
                (map (partial apply get-secondary-annotation config))))
          [(. axiom getValue)]))
      [(. axiom getValue)])
    (catch Exception e
      (println "XML Parse error" axiom e)
      [(. axiom getValue)])))

(defn assert-annotation!
  "Add an Annotation Assertion Axiom to an ontology."
  [^OWLOntology ontology
   ^OWLAnnotationSubject subject
   ^OWLAnnotationProperty property
   ^OWLAnnotationValue value]
  (. output-manager
     addAxiom
     ontology
     (. data-factory
        getOWLAnnotationAssertionAxiom
        property
        subject
        value)))

(defn assert-annotations!
  "Add an Annotation Assertion Axiom and a set of annotations on it
   to an ontology."
  [^OWLOntology ontology
   ^OWLAnnotationSubject subject
   ^OWLAnnotationProperty property
   ^OWLAnnotationValue value
   annotations]
  (. output-manager
     addAxiom
     ontology
     (. data-factory
        getOWLAnnotationAssertionAxiom
        property
        subject
        value
        (set annotations))))

(defn convert-annotation-axiom!
  "Given an ontology, a configuration, and an axiom
   try to convert the axiom, update the ontology,
   and return the updated config."
  [^OWLOntology ontology config ^OWLAnnotationAssertionAxiom axiom]
  (let [^OWLAnnotationSubject subject (. axiom getSubject)
        ^OWLAnnotationProperty property (get-property config axiom)
        [^OWLAnnotationValue value & annotations] (get-annotations config axiom)]
    ; NCIt contains some duplicate definions
    ; that break the OWLAPI's OBO converter.
    ; Don't define more than once.
    ; Otherwise assert the annotations
    (when-not
     (and (= property iao-definition)
          (contains? (:defined config) subject))
      (assert-annotation! ontology subject property value)
      (when (seq? annotations)
        (assert-annotations! ontology subject property value annotations)))

    (when (obsolete? config axiom)
      (assert-annotation! ontology subject owl-deprecated (. data-factory getOWLLiteral true)))

    ; Update the configuration state, tracking definitions
    (if (= property iao-definition)
      (update-in config [:defined] conj subject)
      config)))

;; When this is an annotation axiom, we might want to convert it.
;; Copy all non-annotation axioms

(defn convert-axiom!
  "Given an output ontology, a configuration map, and an axiom,
   copy/convert the axiom, add it to the output ontology,
   and return an updated config."
  [^OWLOntology output-ontology config ^OWLAxiom axiom]
  (cond
    ; Convert annotation axiom
    (= (. axiom getAxiomType) (. AxiomType ANNOTATION_ASSERTION))
    (convert-annotation-axiom! output-ontology config axiom)

    ; Ignore subClassOf for obsolete subject
    (and
     (= (. axiom getAxiomType) (. AxiomType SUBCLASS_OF))
     (contains?
      (:obsoletes config)
      (. ^OWLClass (. ^OWLSubClassOfAxiom axiom getSubClass) getIRI)))
    config

    ; Ignore equivalentClass for obsolete subject
    (and
     (= (. axiom getAxiomType) (. AxiomType EQUIVALENT_CLASSES))
     (some
      #(contains? (:obsoletes config) (. ^OWLClass % getIRI))
      (. ^OWLEquivalentClassesAxiom axiom getNamedClasses)))
    config

    ; Otherwise copy the axiom
    :else
    (do
      (. output-manager addAxiom output-ontology axiom)
      config)))

(defn convert
  "Given paths for the configuration YAML file, the base annotation file,
  the upstream NCI Taxonomy OWL file, and the output ncit.owl file,
  go through every axiom and convert it to use OBO-standard annotations,
  then save to the output path."
  [config-path ^String annotation-path ^String input-path ^String output-path]
  (println "Using" config-path "to convert" input-path "to" output-path)
  (let [config (read-config config-path)
        ^OWLOntology input-ontology  (. io-helper loadOntology input-path)
        ^OWLOntology output-ontology (. io-helper loadOntology annotation-path)]
    (reduce
     (partial convert-axiom! output-ontology)
     (assoc
      config
      :defined #{}
      :obsoletes (get-obsoletes input-ontology config))
     (. input-ontology getAxioms))
    (. io-helper saveOntology output-ontology output-path)))
