# NCIt ncit-obo Makefile
# James A. Overton
# 2016-02-11
#
# This file contains scripts for converting
# the NCI Thesaurus OWL representation
# to an OBO-compatible OWL representation.
#
# Requirements:
#
# - curl
# - unzip
# - Java 7
# - Maven
# - Leiningen 2.5+


### Configuration
#
# These are standard options to make Make sane:
# <http://clarkgrubb.com/makefile-style-guide#toc2>

MAKEFLAGS += --warn-undefined-variables
SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := all
.DELETE_ON_ERROR:
.SUFFIXES:


APACHE_MIRROR := http://www.us.apache.org/dist
SPARQL_URL := http://localhost:3030/db
NCIT := http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl
NCIT_RELEASE := 16.03d
NCIT_DOWNLOAD := http://evs.nci.nih.gov/ftp1/NCI_Thesaurus/archive/$(NCIT_RELEASE)_Release/Thesaurus_$(NCIT_RELEASE).OWL.zip
OBO  := http://purl.obolibrary.org/obo
JAVA := java -Xmx4g
ROBOT := $(JAVA) -jar lib/robot.jar
NCIT_OBO_JAR := target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar
NCIT_OBO := $(JAVA) -jar $(NCIT_OBO_JAR)


### Ontology Tools
#
# Download and use tools for working with ontologies.

lib:
	mkdir $@


#### ROBOT
#
# download latest ROBOT jar
# load into local Maven repository

lib/robot.jar: | lib
	curl -Lko $@ https://build.berkeleybop.org/job/robot/lastSuccessfulBuild/artifact/bin/robot.jar

local_maven_repo: lib/robot.jar
	mkdir -p $@
	lein deploy $@ org.obolibrary/robot 0.0.1-SNAPSHOT $<


#### Apache Jena
#
# Download and extract.

lib/jena.zip: | lib
	curl -Lo $@ $(APACHE_MIRROR)/jena/binaries/apache-jena-3.0.1.zip

lib/jena: lib/jena.zip
	rm -rf $@
	unzip -q -d lib $<
	mv lib/apache-jena-3.0.1 $@

lib/jena/bin/tdbloader: | lib/jena


#### Apache Jena Fuseki
#
# Download, extract, and configure.

lib/fuseki.zip: | lib
	curl -Lo $@ $(APACHE_MIRROR)/jena/binaries/apache-jena-fuseki-2.3.1.zip

lib/fuseki: lib/fuseki.zip
	rm -rf $@
	unzip -q -d lib $<
	mv lib/apache-jena-fuseki-2.3.1 $@

lib/fuseki/shiro.ini: | lib/fuseki
	echo '[urls]' > $@
	echo '# Everything open' >> $@
	echo '/**=anon' >> $@


### Build

$(NCIT_OBO_JAR): project.clj src/ncit_obo/ | local_maven_repo
	lein uberjar


### NCI Thesaurus OWL
#
# TODO: use a link to the latest Thesaurus.owl
# TODO: use inferred file?

build:
	mkdir -p $@

build/Thesaurus.owl.zip: | build
	curl -L -o $@ $(NCIT_DOWNLOAD)

build/Thesaurus.owl: build/Thesaurus.owl.zip
	unzip -qc $< Thesaurus.owl > $@


### Gene Ontology

build/go.owl: | build
	curl -Lko $@ $(OBO)/go.owl


### Cell Ontology
#
# TODO: Get a standalone OWL file for CL.

build/cl.owl: | build
	curl -Lko $@ $(OBO)/cl.owl


### Configuration Sheets
#
# https://docs.google.com/spreadsheets/d/1PZ2lHSeRK_yn5LqYnejRyI-_GSXbkGKE5pjhTRqFjKI/edit
#
# WARN: The config.yml drives the configuration, not these sheets.

ID  := 1PZ2lHSeRK_yn5LqYnejRyI-_GSXbkGKE5pjhTRqFjKI
URL := https://docs.google.com/spreadsheets/d/$(ID)/export?format=tsv&gid=

build/AnnotationProperties.tsv: | build
	curl -L -o $@ "$(URL)1392969515"

build/XMLLiterals.tsv: | build
	curl -L -o $@ "$(URL)601798304"


### Local Triplestore
#
# Run Fuseki locally from a second shell:
#
#     make fuseki-load
#     make fuseki

.PHONY: fuseki-ncit
fuseki-ncit: build/ncit.owl | lib/jena/bin/tdbloader lib/fuseki
	$(word 1,$|) --loc $(word 2,$|)/tdb --graph '$(NCIT)' $<

# Handle OBO ontologies:

.PHONY: fuseki-%
fuseki-%: build/%.owl | lib/jena/bin/tdbloader lib/fuseki
	$(word 1,$|) --loc $(word 2,$|)/tdb --graph '$(OBO)/$*.owl' $<

.PHONY: fuseki-load
fuseki-load: fuseki-ncit fuseki-go

# Run Fuseki in a second shell!
.PHONY: fuseki
fuseki: | lib/fuseki/tdb lib/fuseki/shiro.ini
	cd lib/fuseki && export FUSEKI_HOME=. && ./fuseki-server --loc tdb /db


### Subsets
#
# Use ROBOT to extract various subsets of the converted ontology.

build/subsets:
	mkdir -p $@

build/subsets/biological_process.owl: build/ncit.owl | lib/robot.jar build/subsets
	$(ROBOT) extract \
	--input $< \
	--method MIREOT \
	--branch-from-term '$(NCIT)#C17828' \
	annotate \
	--ontology-iri '$(OBO)/ncit/subsets/biological_process.owl' \
	--output $@

build/subsets/neoplasm.owl: build/ncit.owl | lib/robot.jar build/subsets
	$(ROBOT) extract \
	--input $< \
	--method MIREOT \
	--branch-from-term '$(NCIT)#C3262' \
	annotate \
	--ontology-iri '$(OBO)/ncit/subsets/neoplasm.owl' \
	--output $@


### Run

# Convert NCIt Thesaurus.owl to use OBO-style annotations.

build/ncit.owl: $(NCIT_OBO_JAR) src/config.yml src/base.ttl build/Thesaurus.owl
	$(NCIT_OBO) convert $(wordlist 2,9,$^) $@


# Align GO_0044763 single-organism cellular process
# to ncit:C20480 Cellular Process
# and write a report.
#
# WARN: requires a runnig Fuseki instance, with NCIt and GO loaded.
# See above.

build/cellular_process.tsv: $(NCIT_OBO_JAR) build/Thesaurus.owl build/go.owl
	$(NCIT_OBO) align $(NCIT) ncit:C20480 $(OBO)/go.owl obo:GO_0044763 $@

# Compress build artifacts.

build/%.zip: build/%
	cd build && zip $*.zip $*

# Convert to OBO format.
# first to obtain valid OBO IDs, we have to satisfy the assumptions of the OWLAPI OBO writer that
# class IRIs are OBO purls (hack)

build/%-obouri.owl: build/%.owl
	perl -npe 's@http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#@http://purl.obolibrary.org/obo/NCIT_@g' $< > $@.tmp && mv $@.tmp $@
build/%.obo: build/%-obouri.owl | lib/robot.jar
	$(ROBOT) convert --input $< --output $@


### Common Tasks

.PHONY: all
all: build/ncit.owl
all: build/ncit.obo
all: build/subsets/biological_process.owl
all: build/subsets/biological_process.obo
all: build/subsets/neoplasm.owl
all: build/subsets/neoplasm.obo

.PHONY: clean
clean:
	rm -rf build lib local_maven_repo target

.PHONY: tidy
tidy:
	rm -f build/Thesaurus.owl*
