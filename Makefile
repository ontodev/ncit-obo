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
# - Leiningen


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


### ROBOT
#
# download latest ROBOT jar
# load into local Maven repository

build/robot.jar: | build
	curl -L -o $@ https://build.berkeleybop.org/job/robot/lastSuccessfulBuild/artifact/bin/robot.jar

local_maven_repo: build/robot.jar
	mkdir -p $@
	lein deploy $@ org.obolibrary/robot 0.0.1-SNAPSHOT $<


### Build

target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar: project.clj src/ncit_obo/ local_maven_repo
	lein uberjar


### NCI Thesaurus OWL
#
# TODO: use a link to the latest Thesaurus.owl
# TODO: use inferred file?
# http://evs.nci.nih.gov/ftp1/NCI_Thesaurus/ThesaurusInf_16.01d.OWL.zip

build:
	mkdir -p $@

build/ncit.owl.zip: | build
	curl -L -o $@ http://evs.nci.nih.gov/ftp1/NCI_Thesaurus/Thesaurus_16.01d.OWL.zip

build/ncit.owl: build/ncit.owl.zip
	unzip -qc $< Thesaurus.owl > $@


### Configuration Sheets

ID  := 1PZ2lHSeRK_yn5LqYnejRyI-_GSXbkGKE5pjhTRqFjKI
URL := https://docs.google.com/spreadsheets/d/$(ID)/export?format=tsv&gid=

build/AnnotationProperties.tsv: | build
	curl -L -o $@ "$(URL)1392969515"

build/XMLLiterals.tsv: | build
	curl -L -o $@ "$(URL)601798304"


### Run

build/ncit-obo.owl: target/uberjar/ncit-obo-0.1.0-SNAPSHOT-standalone.jar config.yml build/ncit.owl
	java -Xmx4g -jar $^ $@

build/%.zip: build/%
	cd build && zip $*.zip $*


### Common Tasks

.PHONY: all
all: build/ncit-obo.owl build/ncit-obo.owl.zip

.PHONY: clean
clean:
	rm -rf build target local_maven_repo
