(ns artifactory-crawler.core
  (:require [itsy.core :as itsy]
            [clojure.core.async :refer [go go-loop <! >! <!! >!! chan alts! timeout close! put!]]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.pprint :as pp]
            [clojure.string :as string]
            [clojure.tools.cli :as cli])
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
    (when artifact
      [url artifact (get-timestamp date time) (Integer/parseInt number)])))

;; Answer a list of artifact info (vector of url, id, date-time and number) for the urls, 
;;  created before "before"
(defn collect-artifacts [urls before allow-empty]
  (let [artifacts (->> (remove #(re-find #"(?x) \.  md5|sha1  $" %1) urls) ; ignore checksums
                       (map extract-artifact-info)
                       (remove nil?)
                       (sort #(compare (%2 created-idx) (%1 created-idx)))) ; descending date order
        most-recent-number (nth (first artifacts) number-idx)

        ;; Unless overriden, at least one artifact should remain in the directory for the snapshot version.
        rest (if allow-empty artifacts (remove #(= (% number-idx) most-recent-number) artifacts))]
    ;;  list artifacts older than the before date
    (filter #(time/before? (% created-idx) before) rest)))

;; Create an artifact vector suitable for csv output.  
(defn printable-artifact [artifact]
  (let [[url artifact created number] artifact
        created-str (if created
                      (time-format/unparse (time-format/formatters :basic-date-time) created)
                      "-")]
    [url artifact created-str (str number)]))

;; Minimal Itsy page handler for found pages, just put them on the supplied channel
;; Itsy has its own threadpool, a blocking put is sufficient.
(defn handle-page [c {:keys [url body]}]
   (put! c [url body]))

(def crawl-options {:host-limit true, :url-limit -1 :workers 3 :url-extractor extract-directory-urls})

;; Crawl the supplied url for maven artifacts older than age-days, answering a sequence of 
;;  artifact-infos.

(defn crawl [url filter-artifacts-fn]
  (let [c (chan)
        handler (partial handle-page c)
        crawl-handle (itsy/crawl (merge {:url url :handler handler} crawl-options))
        result-chan (go-loop [artifacts []]
                 (let [timeout-chan (timeout end-result-timeout-millis)
                       [[url body] _] (alts! [c timeout-chan])]
                   (if body
                     (let [urls (itsy/extract-all url body)
                           filtered (filter-artifacts-fn urls)]
                        ; realize the intermediate sequence, using concat on its own gives a stack overlow.
                       (recur (doall (concat artifacts filtered))))
                     (do (itsy/stop-workers crawl-handle)
                         (close! c)
                         artifacts))))]
    ;; Block until the result is ready, e.g. no new urls appear within the timeout interval.
    (<!! result-chan)))


(defn crawl-oldest [url age-days allow-empty]
  (let [before (time/minus (time/now) (time/days age-days))]
    (crawl url (fn [urls] (collect-artifacts urls before allow-empty)))))

;; Create an artifact vector for an artifact with a build number 
;; The id is the artifact without its build number  (e.g. "comments-2013.12"
(defn create-build-number-artifact [url]
  (let [[_ module sprint number] (re-find #"(?x) / ([^/]+) / ([^/]+) -build- (\d+) $" url)]
    (when module
      [url (str module "-" sprint) nil (Integer/parseInt number)])))

(defn collect-build-artifacts [urls sprint-build]
  "Collect artifacts with a specific sprint and build number, e.g. 2013.09-build-43"
  (->> urls
       (filter #(.endsWith %1 sprint-build))
       (map #(create-build-number-artifact %1))
       (remove nil?)))

(defn crawl-sprint-build [url sprint-build]
  (crawl url (fn [urls] (collect-build-artifacts urls sprint-build))))


(defn order-build-artifacts [artifacts nof-keep]
  "Answer the oldest artifacts (according to buildnumber) except for the last nof-keep items."
  (->> artifacts
       ;; juxt creates a vector of results to compare.
       (sort-by (juxt #(nth %1 id-idx) #(nth %1 number-idx)))
       (partition-by #(nth %1 id-idx))
       (map #(drop-last nof-keep %1))
       (apply concat)))

(defn filter-build-number-artifacts [urls]
  (->> urls
       (map #(create-build-number-artifact %1))
       (remove nil?)))

(defn crawl-releases [url nof-keep]
  (let [all-artifacts (crawl url filter-build-number-artifacts)]
    (order-build-artifacts all-artifacts nof-keep)))

(def cli-options
  [["-b" "--build BUILD" "Search for specfic sprint+build directies."]
   ["-e" "--empty" "Allow for empty SNAPSHOT artifact directories."]
   ["-r" "--release" "Answer all release artifacts which are 'younger' then nof-releases"]])

;; Print a csv list of all found artifacts older than nof-days on stdout.
;; Usage: lein run -b|--build <build> [-e|--empty] <url> <nof-days|nof-releases>
(defn -main
  [& args]
  ;; Must be set to true in order for itsy to work?
  (alter-var-root #'*read-eval* (constantly true))

  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        _ (when errors 
            (println (cli/summarize cli-options))
            (System/exit 1))
        artifacts (cond 
                   (:build options) (crawl-sprint-build (first arguments) (:build options))
                   (:release options) (crawl-releases (first arguments) (Integer/parseInt (second arguments)))
                   :else (crawl-oldest (first arguments) (Integer/parseInt (second arguments)) (:empty options)))]
    ;; Csv Format is url, artifact, date, sequence-number (all unquoted).
    (doseq [artifact artifacts]
      (println (string/join "," (printable-artifact artifact))))))
