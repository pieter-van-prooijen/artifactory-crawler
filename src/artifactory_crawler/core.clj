(ns artifactory-crawler.core
  (:require [itsy.core :as itsy]
            [clojure.core.async :refer [go go-loop <! >! <!! chan alts! timeout close!]]
            [clojure-csv.core :as csv]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.pprint :as pp])
  (:gen-class))

;; Interval in which a new page should arrive from the crawler before stopping the crawl.
(def end-result-timeout-millis 10000)

;; A directory url is an url not ending in a "normal" file extension.
(defn directory-url? [url]
  (not (re-find #"(?x) \. \w+ $" url)))

;; Only extract / fetch "directory" pages, not actual maven artifacts.
(defn extract-directory-urls [original-url body]
  (let [urls (itsy/extract-all original-url body)
        answer (filter directory-url? urls)]
        answer))

;; Convert from date (yyyymmdd) and time (hhmmss) strings to a date-time.
(defn get-timestamp [date time]
  (let [[_ yyyy MM dd] (re-find #"(?x) (\d{4}) (\d{2}) (\d{2})" date)
        [_ hh mm ss] (re-find #"(?x) (\d{2}) (\d{2}) (\d{2})" time)]
    (apply time/date-time (map #(Integer/parseInt %1) [yyyy MM dd hh mm ss]))))

;; Indexes in the artifact info vector.
(def url-idx 0)
(def id-idx 1)
(def created-idx 2)
(def number-idx 3)

;; Answer artifact, date and sequence number from an artifact url.
(defn extract-artifact-info [url]
  (let [[_ artifact date time number] (re-find #"(?x)  ([^/]+) - (\d{8}) \. (\d{6}) - (\d+) (?: - \w+ )? \. " url)]
    (if artifact
      [url artifact (get-timestamp date time) (Integer/parseInt number)]
      nil)))

;; Answer a list of artifact info (vector of url, id, date-time and number) for the urls, 
;;  created before "before"
(defn collect-artifacts [urls before]
  (let [artifacts (->> (remove #(re-find #"(?x) \.  md5|sha1  $" %1) urls) ; ignore checksums
                       (map extract-artifact-info)
                       (remove nil?)
                       (sort #(compare (%2 created-idx) (%1 created-idx)))) ; descending date order
        most-recent-number (nth (first artifacts) number-idx)

        ;; At least one artifact should remain in the directory for the snapshot version,
        rest (remove #(= (% number-idx) most-recent-number) artifacts)]
    ;;  list artifacts older than the before date
    (filter #(time/before? (% created-idx) before) rest)))

;; Create an artifact vector suitable for csv output.  
(defn printable-artifact [artifact]
  (let [[url artifact created number] artifact]
    [url, artifact (time-format/unparse (time-format/formatters :basic-date-time) created) (str number)]))

;; Minimal Itsy page handler for found pages, just put them on the supplied channel
(defn handle-page [c {:keys [url body]}]
  (go (>! c [url body])))

(def crawl-options {:host-limit true, :url-limit -1 :workers 3 :url-extractor extract-directory-urls})

;; Crawl the supplied url for maven artifacts older than age-days, answering a sequence of 
;;  artifact-infos.
(defn crawl [url age-days]
  (let [before (time/minus (time/now) (time/days age-days))
        c (chan)
        handler (partial handle-page c)
        crawl-handle (itsy/crawl (merge {:url url :handler handler} crawl-options))
        result (go-loop [artifacts []]
                 (let [[[url body] _] (alts! [c (timeout end-result-timeout-millis)])]
                   (if body
                     (let [urls (itsy/extract-all url body)
                           oldest (collect-artifacts urls before)]
                       (recur (concat artifacts oldest)))
                     (do (itsy/stop-workers crawl-handle)
                         (close! c)
                         artifacts))))]
    ;; Block until the result is ready, e.g. no new urls appear within the timeout interval.
    (<!! result)))

;; Print a csv list of all found artifacts older than nof-days on stdout.
;; Usage: lein run <url> <nof-days>
(defn -main
  [& args]
  ;; Must be set to true in order for itsy to work?
  (alter-var-root #'*read-eval* (constantly true))
  
  (let [artifacts (crawl (first args) (Integer/parseInt (second args)))
        printable-artifacts (map printable-artifact artifacts)]
    ;; Csv Format is url, artifact, date, sequence-number (all unquoted).
    (print (csv/write-csv printable-artifacts))))





