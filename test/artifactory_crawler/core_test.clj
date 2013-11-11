(ns artifactory-crawler.core-test
  (:use clojure.test
        artifactory-crawler.core)
  (:require [clj-time.core :as time]))

(deftest extract-artifact-info-test
  (testing "Extracting artifact info"
    (let [orig-url "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9.pom"
          [url id date num] (extract-artifact-info orig-url)]
      (is (= url orig-url))
      (is (= id "comments-2013.11-build-1"))
      (is (= date (time/date-time 2013 11 5 10 3 52)))
      (is (= num 9)))

    ;; sources/javadoc jars
    (let [orig-url "http://hostname/2013.11-build-1-SNAPSHOT/comments-2013.11-build-1-20131105.100352-9-sources.jar"
          [url id date num] (extract-artifact-info orig-url)]
      (is (= url orig-url))
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

    (let [artifacts-11-01 (collect-artifacts artifact-urls older-11-01)]
      (is (empty artifacts-11-01)))

    (let [artifacts-11-04 (collect-artifacts artifact-urls older-11-04)]
      (is (= 3 (count artifacts-11-04)))
      (is (= 7 ((first artifacts-11-04) number-idx))))

    ;; The 11-5 artifact should not be listed, even if it's older,
    ;;  to keep at least one artifact for this version.
    (let [artifacts-11-06 (collect-artifacts artifact-urls older-11-06)]
      (is (= 6 (count artifacts-11-06)))
      (is (= 8 ((nth artifacts-11-06 0) number-idx)))
      (is (= 7 ((nth artifacts-11-06 3) number-idx))))

    (let [artifacts-invalid-urls (collect-artifacts ["http://hostname/not-an-artifact-url"] older-11-01)]
      (is (empty artifacts-invalid-urls)))))

