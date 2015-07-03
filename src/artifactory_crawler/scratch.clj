(ns artifactory-crawler.scratch)

(defn- do-curried
  [name doc meta args body]
  (let [cargs (vec (butlast args))]
    `(defn ~name ~doc ~meta
       (~cargs (fn [x#] (~name ~@cargs x#)))
       (~args ~@body))))

(defmacro defcurried
  "Builds another arity of the fn that returns a fn awaiting the last
param"
  [name doc meta args & body]
  (do-curried name doc meta args body))
