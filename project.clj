(defproject artifactory-crawler "0.1.0-SNAPSHOT"
  

  :description "Artifactory crawler for all snapshot artifacts older than a specified age"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]
                 [itsy "0.1.1"]
                 [clojure-csv/clojure-csv "2.0.1"]
                 [clj-time "0.6.0"]]
  :profiles {:user {:plugins [[lein-kibit "0.0.8"]]}}
  :main artifactory-crawler.core)
