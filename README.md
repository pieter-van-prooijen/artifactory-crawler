# Artifactory Crawler

Crawls an Artifactory repository and writes a list of all snapshot
artifacts older than a specified number of days.

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
default (see admin tab => repository =>
<select a local snapshot" => edit repo => ).
Switching this limit on at a later date will only clean up the version
directories in which new time-stamped artifacts are deployed, but will leave the older
version directories untouched.

This crawler will find all of the time-stamped artifacts in a repository
older then a specified number of days. The URLs of these artifacts can be
used with a HTTP "delete" request, removing them from the repository and
freeing up disk space.

Care is taken to leave at least one time-stamped artifact under each
version, so older snapshot builds still work.

Note: this tool is for remedying a one-time administration issue. If you want
more sophisticated artifactory management tools, check out the pro <link>
or cloud <link> versions of artifactory.

## Installation

git clone <>

## Usage

   $ cd artifactory-crawler
   $ lein run <artifactory-repo-url> <older-than-days> > artifacts.csv

This will create a CSV file with the urls of all the artifacts.

*The following command is potentially dangerous to the health of your
artifactory repository, so first do the following:* 

- Make a backup of the repository before running the command.
- (Spot) check the output of crawler (by loading the .csv
file into spreadsheet) to see if the correct artifacts are going to be deleted.

Pipe the output to curl:
     $ cut -f 1 -c , artifacts.csv | xargs curl --user <admin>:<password>
     --request DELETE

Artifactory will automatically delete the checksum files for an artifact.

Explicitly reclaim the freed disk space in Artifactory by running the
garbage collector (via Admin => Maintainance => ...)

## Options

- url, the url of the local snapshot repository, this usually has the form
  "http://<artifactory-host>/libs-snapshot-local".
- nof-days, only list artifacts older than this number of days. 

## Code

The clojure code uses the excellent itsy (link) crawler library for retrieving the
various artifactory pages. It only crawls the directory pages of
artifactory and not the actual artifacts themselves.

Itsy invokes a callback for signalling the retrieval of an url /  page body, the crawler uses
core.async to process the callback results in a straight for loop. Doing this
avoids having an explicit global state containing the collected artifact
urls.

When itsy doesn't report any new urls after a certain period, the crawl is
stopped and the artifact information is written as a CSV file to stdout.

## License

Copyright © 2013 Pieter van Prooijen

Distributed under the MIT Licence.
