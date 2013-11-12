# Artifactory Crawler

Crawls an Artifactory repository and writes a list of all snapshot
time-stamped artifacts older than a specified number of days. This list can
be used to delete these artifacts from the repository, freeing up disk-space.

## Motivation and Use Case

Each time a deploy is done of an artifact (pom, jar etc.) which has a
"-SNAPSHOT" version, artifactory doesn't overwrite the old instance of that
artifact, but creates a time-stamped instance in the version directory of
the artifact. This can eat up disk space fairly quickly if many snapshots
deploys are made, for example as part of a continuous integration build
proces. Maven only fetches the most recent artifact instance when making
snapshot builds, it doesn't really need the older instances.

Artifactory has a repository setting  which limits the number of unique
snapshot artifacts for a version, but this limit is switched off by
default (see Admin tab => Repository =>
Select a local snapshot repo => Choose "Edit" via the popup => Basic Settings
tab).

The problem is that switching this limit on at a later date will only clean
up the version directories in which new time-stamped artifacts are
deployed, but will leave the older version directories untouched.

This crawler will find all of the time-stamped artifacts in a repository
older then a specified number of days. The URLs of these artifacts can be
used with a HTTP "delete" request, removing them from the repository and
freeing up disk space.

Care is taken to leave at least one time-stamped artifact under each
version, so older snapshot builds still work.

This tool is for remedying a one-time administration issue. If you
want more sophisticated repository management tools, check out the [pro version](
http://www.jfrog.com/home/v_artifactorypro_overview) of artifactory.

## Installation / Usage

Just clone the repository and use the clojure leiningen build tool to run
the crawler (a valid JDK installation is necessary, 1.6 or higher) :

     $ git clone https://github.com/pieter-van-prooijen/artifactory-crawler.git
     $ cd artifactory-crawler
     $ lein run artifactory-repo-url older-than-days > artifacts.csv

This will create a CSV file with the urls of all the artifacts.

*The following command is potentially dangerous to the health of your
artifactory repository, so first take the following steps!* 

- Make a backup of the repository.
- (Spot)check the output of crawler (e.g. by loading the .csv
file into spreadsheet) to see if the correct artifacts are going to be deleted.

Pipe the first column of the csv to curl to delete each artifact:

     $ cut -f 1 -c , artifacts.csv | xargs curl --user <admin>:<password> --request DELETE

Artifactory will automatically delete the corresponding checksum files of an artifact.

Explicitly reclaim the freed disk space in Artifactory by running the
garbage collector (via Admin => Advanced => Maintainance => Garbage Collection)

## Options

The command takes two mandatory arguments:

- url, the url of the local snapshot repository, this usually has the form
  "http://artifactory-host/libs-snapshot-local".
- nof-days, only list artifacts older than this number of days. 

## Code

The Clojure code uses the excellent [Itsy](https://github.com/dakrone/itsy)
crawler library for retrieving the various artifactory pages. It is setup
to only crawl the directory pages of artifactory and not download the actual
artifacts themselves.

Itsy invokes a callback for signalling the retrieval of an url / page body,
the crawler uses core.async to process the callback arguments in a straight
for loop. Doing this avoids having an explicit global state for the
collected artifact urls. The for loop reads the results from the channel,
accumulating the results in a standard way.

When Itsy doesn't report any new urls after a certain period, the crawl is
stopped and the artifact information is written as a CSV file to stdout.

## License

Copyright © 2013 Pieter van Prooijen

Distributed under the MIT Licence.
