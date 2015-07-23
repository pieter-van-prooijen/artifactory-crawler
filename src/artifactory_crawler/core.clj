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
    (apply time/date-time (map #(Integer/parseInt %) [yyyy MM dd hh mm ss]))))

;; Indexes in the artifact info vector.
(def url-idx 0)
(def sprint-year-idx 1)
(def sprint-number-idx 2)
(def branch-idx 3)
(def id-idx 4)
(def created-idx 5)
(def number-idx 6)

(defn sprint-year-number [sprint]
  (let [[_ year num branch] (re-find #"(?x) (\d{4}) \. (\d{1,2}) (?: - (.+))? $" sprint)]
    (when year
      [(Integer/parseInt year) (Integer/parseInt num) branch])))

;; Answer the url-sprint-artifact, date and sequence number from an artifact url.
(defn extract-artifact-info [url]
  (let [[_ artifact date time number] (re-find #"(?x)  ([^/]+) - (\d{8}) \. (\d{6}) - (\d+) (?: - \w+ )? \. " url)]
    (when artifact
      (let [[sprint-year sprint-number branch] (sprint-year-number artifact)]
        (when sprint-year
          [url sprint-year sprint-number branch artifact (get-timestamp date time) (Integer/parseInt number)])))))

;; Answer a list of artifact info (vector of url, id, date-time and number) for the urls, 
;;  created before "before"
(defn collect-artifacts [urls before allow-empty]
  (let [artifacts (->> (remove #(re-find #"(?x) \.  md5|sha1  $" %) urls) ; ignore checksums
                       (map extract-artifact-info)
                       (remove nil?)
                       (sort #(compare (%2 created-idx) (% created-idx)))) ; descending date order
        most-recent-number (nth (first artifacts) number-idx)

        ;; Unless overriden, at least one artifact should remain in the directory for the snapshot version.
        rest-artifacts (if allow-empty artifacts (remove #(= (% number-idx) most-recent-number) artifacts))]
    ;;  list artifacts older than the before date
    (filter #(time/before? (% created-idx) before) rest-artifacts)))

;; Create an artifact vector suitable for csv output.  
(defn printable-artifact [artifact]
  (let [[url _ _ _ artifact created number] artifact
        created-str (if created
                      (time-format/unparse (time-format/formatters :basic-date-time) created)
                      "-")]
    [url artifact created-str (str number)]))

;; Minimal Itsy page handler for found pages, just put them on the supplied channel
;; Itsy has its own threadpool, a blocking put is sufficient.
(defn handle-page [c {:keys [url body]}]
   (put! c [url body]))

(def crawl-options {:host-limit true, :url-limit -1 :workers 3 :url-extractor extract-directory-urls})

;;
;; Craw a url or a file containing a list of paths, dispatch on scheme
(defmulti crawl (fn [url _]
                  (let [[_ scheme] (re-find #"^(\w+):" url)]
                    (keyword scheme))))

(defmethod crawl :default [url filter-artifacts-fn]
  (throw (IllegalArgumentException. (str "Url argument should either be a http or file url"))))

(defmethod crawl :http [url filter-artifacts-fn]
  "Crawl the supplied url for artifacts and filter them through
  filter-artifacts-fn, which takes a collection of artifact urls and
  answers the ones to report in the crawl"
  (let [c (chan)
        handler (partial handle-page c)
        crawl-handle (itsy/crawl (merge {:url url :handler handler} crawl-options))
        result-chan (go-loop [artifacts []]
                 (let [timeout-chan (timeout end-result-timeout-millis)
                       [[url body] _] (alts! [c timeout-chan])]
                   (if body
                     (let [urls (itsy/extract-all url body)
                           filtered (filter-artifacts-fn urls)]
                        ;; realize the intermediate sequence, using concat on its own gives a stack overflow.
                       (recur (doall (concat artifacts filtered))))
                     (do (itsy/stop-workers crawl-handle)
                         (close! c)
                         artifacts))))]
    ;; Block until the result is ready, e.g. no new urls appear within the timeout interval.
    (<!! result-chan)))

(defmethod crawl :file [url filter-artifacts-fn]
  (let [s (slurp url)
        files (string/split-lines s)]
    (filter-artifacts-fn files)))


;; Crawl for artifacts older than age-days
(defn crawl-oldest [url age-days allow-empty]
  (let [before (time/minus (time/now) (time/days age-days))]
    (crawl url (fn [urls] (collect-artifacts urls before allow-empty)))))

;; Create an artifact vector for an artifact with a build number 
;; The id is the artifact without its build number (e.g. "comments-2013.12")
;; Use the directory above the artifact to disambiguate similar module names like "core" or
;;  "functional-test" in the various projects. SNAPSHOT versions are treated as separate artifacts.

(defn create-build-number-artifact [url]
  (let [[_ parent module sprint-branch number snapshot] 
        (re-find #"(?x) ([^/]+) / ([^/]+) / ([^/]+)  -build- (\d+) (-SNAPSHOT)? $" url)]
    (when module
      (let [[sprint-year sprint-number branch] (sprint-year-number sprint-branch)]
        (when sprint-year
          [url sprint-year sprint-number branch
           (str parent "-" module (when snapshot "-" snapshot)) nil (Integer/parseInt number)])))))

(defn collect-build-artifacts [urls sprint-build]
  "Collect artifacts with a specific sprint and build number, e.g. 2013.09-build-43"
  (->> urls
       (filter #(.endsWith % sprint-build))
       (map create-build-number-artifact)
       (remove nil?)))

(defn crawl-sprint-build [url sprint-build]
  (crawl url (fn [urls] (collect-build-artifacts urls sprint-build))))

;; Function for sorting / partitioning on sprint ids
(def sprint-vec (juxt #(nth % sprint-year-idx) #(nth % sprint-number-idx) #(nth % branch-idx)))

;; split the list of artifacts in a list of sprints to remove and one to keep
(defn split-for-sprints [artifacts nof-keep]
  (let [partitioned (->> artifacts
                         (sort-by sprint-vec)
                         (partition-by sprint-vec))
        nof-sprints (count partitioned)
        nof-to-remove (- nof-sprints (min nof-sprints nof-keep))
        [sprints-to-remove sprints-to-keep] (split-at nof-to-remove partitioned)]
    [(apply concat sprints-to-remove) (apply concat sprints-to-keep)]))

(defn order-build-artifacts-for-single-id [artifacts nof-keep-sprints nof-keep-builds]
  (let [[sprints-to-remove sprints-to-keep] (split-for-sprints artifacts nof-keep-sprints)
        builds-to-keep (->> sprints-to-keep
                            (partition-by sprint-vec)
                            (map #(drop-last nof-keep-builds %))
                            (apply concat))]
    (concat sprints-to-remove builds-to-keep)))

(defn order-build-artifacts [artifacts nof-keep-sprints nof-keep-builds]
  "Answer the oldest artifacts (according to sprint / buildnumber) except for the last nof-keep items / sprints.
   Note that nof-keep-sprints is *per module*, not per artifact"
  (->> artifacts
       (sort-by (juxt #(nth % id-idx) sprint-vec #(nth % number-idx)))
       (partition-by #(nth % id-idx))
       (map #(order-build-artifacts-for-single-id % nof-keep-sprints nof-keep-builds))
       (apply concat)))

(defn filter-build-number-artifacts [urls]
  (->> urls
       (map create-build-number-artifact)
       (remove nil?)))

(defn crawl-releases [url nof-keep-sprints nof-keep-builds]
  (let [all-artifacts (crawl url filter-build-number-artifacts)]
    (order-build-artifacts all-artifacts nof-keep-sprints nof-keep-builds)))

(def cli-options
  [["-b" "--build BUILD" "Search for specfic sprint+build directies."]
   ["-e" "--empty" "Allow for empty SNAPSHOT artifact directories."]
   ["-r" "--release" "Operate in release mode: answer all artifacts which are 'younger' than nof-builds-to-keep instead of looking at the timestamp of snapshot releases. Assumes each (snapshot) build has a separate buildnumber"]
   ["-s" "--sprints-to-keep NOF-SPRINTS" "Answer all sprints which are younger than nof-sprints."
    :parse-fn #(Integer/parseInt %) ]
   ["-h" "--help" "This message"]])

(def usage (str 
            "Usage: artifactory-crawler <options> url nof-days|nof-builds-to-keep-per-sprint\n"
            "\n"
            "Crawl the supplied url for maven artifacts and print a list of artifacts which can be removed.\n"
            "If url is a file:/// url, use the contents of that file as a list of artifacts to inspect.\n"
            "nof-days/nof-builds-to-keep determines how many builds should remain in a certain sprint or\n"
            "how old a SNAPSHOT artifact\n"
            "can before it's flagged for removal.\n"
            "<options> are:\n"))

(defn -main
  [& args]
  ;; Must be set to true in order for itsy to work?
  (alter-var-root #'*read-eval* (constantly true))

  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)
        _ (when (or errors (:help options)) 
            (println (str usage "\n" summary))
            (System/exit 1))
        artifacts (cond 
                   (:build options) (crawl-sprint-build (first arguments) (:build options))
                   (:release options) (crawl-releases (first arguments)
                                                      (or (:sprints-to-keep options) (Integer/MAX_VALUE))
                                                      (Integer/parseInt (second arguments)))
                   :else (crawl-oldest (first arguments) (Integer/parseInt (second arguments)) (:empty options)))]
    ;; Csv Format is url, artifact, date, sequence-number (all unquoted).
    (doseq [artifact artifacts]
      (println (string/join "," (printable-artifact artifact))))))
