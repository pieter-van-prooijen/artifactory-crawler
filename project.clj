(defproject artifactory-crawler "0.2.0-SNAPSHOT"

  :description "Artifactory crawler for listing all artifacts older than a specified age or number of builds"

  :url "https://github.com/pieter-van-prooijen/artifactory-crawler"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}

  ;;:jvm-opts ["-Xmx512M" "-Xdebug" "-Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [itsy "0.1.1" :exclusions [clj-http]] ; older clj-http gives error for clojure 1.7.0
                 [clj-http "1.1.2"]
                 [clj-time "0.10.0"]
                 [org.clojure/tools.cli "0.3.1"]]
  
  :profiles {:dev {:plugins [[lein-kibit "0.0.8"]]}}
  :main artifactory-crawler.core
  :aot  [artifactory-crawler.core]
  ;; Limit the number of items to print in a collection
  :global-vars {*print-length* 100})
