(defproject artifactory-crawler "0.1.0-SNAPSHOT"


  :description "Artifactory crawler for all snapshot artifacts older than a specified age"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [itsy "0.1.1"]
                 [clj-time "0.6.0"]
                 [org.clojure/tools.cli "0.3.1"]]
  :profiles {:dev {:plugins [[lein-kibit "0.0.8"]]}}
  :main artifactory-crawler.core
  :aot  [artifactory-crawler.core])
