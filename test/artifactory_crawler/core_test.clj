(ns artifactory-crawler.core-test
  (:use clojure.test
        artifactory-crawler.core)
  (:require [clj-time.core :as time]
            [clojure.java.io :as io]))

(deftest extract-artifact-info-test
  (testing "Extracting artifact info"
    (let [orig-url "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9.pom"
          [url sprint-year sprint-number id date num] (extract-artifact-info orig-url)]
      (is (= url orig-url))
      (is (= sprint-year 2013))
      (is (= sprint-number 11))
      (is (= id "comments-2013.11-build-1"))
      (is (= date (time/date-time 2013 11 5 10 3 52)))
      (is (= num 9)))

    ;; sources/javadoc jars
    (let [orig-url "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9-sources.jar"
          [url sprint-year sprint-number id date num] (extract-artifact-info orig-url)]
      (is (= url orig-url))
      (is (= sprint-year 2013))
      (is (= sprint-number 11))
      (is (= id "comments-2013.11-build-1"))
      (is (= date (time/date-time 2013 11 5 10 3 52)))
      (is (= num 9)))))


;; Make list unordered.
(def artifact-urls ["http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9.pom"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9.jar"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9-sources.jar"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131103.100352-7.pom"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131103.100352-7.jar"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131103.100352-7-sources.jar"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131104.100352-8.pom"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131104.100352-8.jar"
                    "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131104.100352-8-sources.jar"])


;; cut-off dates for list above.
(def older-11-01 (time/date-time 2013 11 1 10 3 52))
(def older-11-04 (time/date-time 2013 11 4 10 3 52))
(def older-11-06 (time/date-time 2013 11 6 10 3 52))

(deftest collect-artifact-test
  (testing "Collecting artifacts"

    (let [artifacts-11-01 (collect-artifacts artifact-urls older-11-01 false)]
      (is (empty artifacts-11-01)))

    (let [artifacts-11-04 (collect-artifacts artifact-urls older-11-04 false)]
      (is (= 3 (count artifacts-11-04)))
      (is (= 7 ((first artifacts-11-04) number-idx))))

    ;; The 11-5 artifact should not be listed, even if it's older,
    ;;  to keep at least one artifact for this version.
    (let [artifacts-11-06 (collect-artifacts artifact-urls older-11-06 false)]
      (is (= 6 (count artifacts-11-06)))
      (is (= 8 ((nth artifacts-11-06 0) number-idx)))
      (is (= 7 ((nth artifacts-11-06 3) number-idx))))

    ;; .. unless overriden by the allow-empty argument
    (let [artifacts-11-06 (collect-artifacts artifact-urls older-11-06 true)]
      (is (= 9 (count artifacts-11-06)))
      (is (= 9 ((nth artifacts-11-06 0) number-idx)))
      (is (= 8 ((nth artifacts-11-06 3) number-idx))))

    (let [artifacts-invalid-urls (collect-artifacts ["http://hostname/not-an-artifact-url"] older-11-01 false)]
      (is (empty artifacts-invalid-urls)))))

(def release-artifact-urls 
  [
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments-cms/2013.13-build-3"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments-cms/2013.13-build-1"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments-cms/2013.13-build-2"

   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2013.13-build-3"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2013.13-build-1"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2013.13-build-2"

   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2014.12-build-2"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2014.12-build-1"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2014.12-build-3"

   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2014.9-build-2"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2014.9-build-1"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2014.9-build-3"

])

(deftest create-build-number-artifact-info
  (let [orig-url "/home/pieter/.m2/repository/nl/rijksoverheid/platform/platform/1.0-build-1"
        [url sprint id _ num] (create-build-number-artifact orig-url)]
    (is (nil? url))))

;; Test for removing all artifacts in a sprint except for the last n
(deftest filter-artifacts-by-build-number
  (let [artifacts (map create-build-number-artifact release-artifact-urls)]
    (let [to-delete-keep-1 (order-build-artifacts artifacts Integer/MAX_VALUE 1)
          artifact-0 (first to-delete-keep-1)]
      (is (= (count to-delete-keep-1) 8))
      (is (= (nth artifact-0 id-idx) "platform-comments"))
      (is (= (nth artifact-0 sprint-number-idx) 13))
      (is (= (nth artifact-0 number-idx) 1)))
    (let [to-delete-keep-3 (order-build-artifacts artifacts Integer/MAX_VALUE 3)]
      (is (= (count to-delete-keep-3) 0)))))

(deftest filter-artifacts-by-sprint
  (let [artifacts (map create-build-number-artifact release-artifact-urls)]
    (let [to-delete-keep-1 (order-build-artifacts artifacts 1 2) ; keep one sprint and within that sprint two builds
          artifact-0 (first to-delete-keep-1)
          artifact-second-last (nth to-delete-keep-1 (- (count to-delete-keep-1) 3))]
      (is (= (count to-delete-keep-1) 8))
      (is (= (nth artifact-0 id-idx) "platform-comments"))
      (is (= (nth artifact-0 sprint-number-idx) 13))
      (is (= (nth artifact-0 number-idx) 1))
      (is (= (nth artifact-second-last id-idx) "platform-comments"))
      (is (= (nth artifact-second-last sprint-number-idx) 9))) ; test numerical sprint ordering
    (let [to-delete-keep-0 (order-build-artifacts artifacts 2 2)] ; keep two sprints *per id* and two builds within those sprints
      (is (= (count to-delete-keep-0) 6)))
    (let [to-delete-keep-0 (order-build-artifacts artifacts 0 3)] ; keep no sprints
      (is (= (count to-delete-keep-0) 12)))))

;; Test for same name modules in different projects
(def same-name-artifact-urls 
  [
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2013.13-build-3"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/rop/comments/2013.13-build-1"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/platform/comments/2013.13-build-2"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/rop/comments/2013.13-build-4"
])

(deftest filter-artifacts-with-same-name
  (let [artifacts (map create-build-number-artifact same-name-artifact-urls)]
    (let [kept-1 (order-build-artifacts artifacts Integer/MAX_VALUE 1)
          artifact-0 (first kept-1)]
      (is (= 2 (count kept-1)))
      (is (= (nth artifact-0 id-idx) "platform-comments"))
      (is (= (nth artifact-0 number-idx) 2)))
    (let [kept-3 (order-build-artifacts artifacts Integer/MAX_VALUE 3)]
      (is (= 0 (count kept-3))))))

;; Test for same snaphot modules
(def snapshot-artifact-urls 
  [
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/rop/comments/2013.13-build-3-SNAPSHOT"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/rop/comments/2013.13-build-1"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/rop/comments/2013.13-build-2-SNAPSHOT"
   "http://artifactory.rijksoverheid.nl/libs-release-local/nl/rijksoverheid/rop/comments/2013.13-build-4"
])

(deftest filter-artifacts-with-snapshot
  (let [artifacts (map create-build-number-artifact snapshot-artifact-urls)]
    (let [kept-1 (order-build-artifacts artifacts Integer/MAX_VALUE 1)
          artifact-1 (nth kept-1 1)]
      (is (= 2 (count kept-1)))
      (is (= (nth artifact-1 id-idx) "rop-comments-SNAPSHOT"))
      (is (= (nth artifact-1 number-idx) 2))
      )
    (let [kept-3 (order-build-artifacts artifacts Integer/MAX_VALUE 3)]
      (is (= 0 (count kept-3))))))

;; Test crawling from a list of files
(deftest crawl-file-list
  (let [list-file (java.io.File/createTempFile "artifactory-crawler-urls-" ".txt")
        list-url (.toString (io/as-url list-file))]
    (try
      (with-open [w (io/writer list-file)]
        (.write w "/home/pieter/.m2/repository/nl/rijksoverheid/platform/platform/1.0-build-1\n")
        (.write w "/home/pieter/.m2/repository/nl/rijksoverheid/ro-common-utils/2012.8-build-1\n"))
      (is (= (count (crawl list-url identity)) 2))
      (is (= (count (crawl list-url filter-build-number-artifacts)) 1))
      (is (= (count (crawl-releases list-url Integer/MAX_VALUE 0)) 1))
      (finally #_(io/delete-file list-file)))))
