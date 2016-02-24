(ns sibiro.core
  "Simple data-driven request routing for Clojure and ClojureScript."
  (:require [clojure.string :as str]))

;;; URL encoding

(defn- url-encode [string]
  (some-> string str
          #?(:clj (java.net.URLEncoder/encode "UTF-8")
             :cljs (js/encodeURIComponent))
          (.replace "+" "%20")))

(defn- url-decode [string]
  (some-> string str
          #?(:clj (java.net.URLDecoder/decode "UTF-8")
             :cljs (js/decodeURIComponent))))


;;; Internals for matching.

(defn- routes-tree [routes]
  (reduce (fn [result [method path handler]]
            (let [parts (map (fn [p] (if (.startsWith p ":") [(keyword (subs p 1))] p))
                             (str/split path #"/"))]
              (assoc-in result (concat parts [method]) handler)))
          {}
          routes))

(defn- match-uri* [tree parts params method]
  (if-let [part (first parts)]
    (or (when-let [subtree (get tree part)]
          (match-uri* subtree (rest parts) params method))
        (some (fn [keyv]
                (when-not (= keyv [:*])
                  (match-uri* (get tree keyv) (rest parts) (assoc params (first keyv) part) method)))
              (filter vector? (keys tree)))
        (when-let [subtree (get tree [:*])]
          (match-uri* subtree nil (assoc params :* (apply str (interpose "/" parts))) method)))
    (when-let [handler (or (get tree method) (:any tree))]
      {:route-handler handler
       :route-params (zipmap (keys params) (map url-decode (vals params)))})))


;;; Internals for uri creation.

(defn- query-string [data]
  (->> (for [[k v] data]
         (str (url-encode (name k)) "=" (url-encode (str v))))
       (interpose "&")
       (apply str "?")))

#?(:clj
   (defn- uri-for-fn-form [path]
     (let [parts  (map (fn [p] (if (.startsWith p ":") (keyword (subs p 1)) p))
                       (str/split path #"/"))
           keyset (set (filter keyword? parts))
           data   (gensym)]
       `(fn [~data]
          (when-let [diff# (seq (reduce disj ~keyset (keys ~data)))]
            (throw (ex-info "Missing data for path." {:missing-keys diff#})))
          {:uri          (str ~@(->> (for [part parts]
                                       (if (keyword? part)
                                         `(#'sibiro.core/url-encode (get ~data ~part))
                                         part))
                                     (interpose "/")))
           :query-string (when-let [keys# (seq (reduce disj (set (keys ~data)) ~keyset))]
                           (#'sibiro.core/query-string (select-keys ~data keys#)))}))))

#?(:clj
   (defn- uri-for-fn [path]
     (eval (uri-for-fn-form path))))

#?(:cljs
   (defn- uri-for-fn [path]
     (let [parts  (map (fn [p] (if (.startsWith p ":") (keyword (subs p 1)) p))
                       (str/split path #"/"))
           keyset (set (filter keyword? parts))]
       (fn [data]
         (when-let [diff (seq (reduce disj keyset (keys data)))]
           (throw (ex-info "Missing data for path." {:missing-keys diff})))
         {:uri          (apply str (->> (for [part parts]
                                          (if (keyword? part)
                                            (url-encode (get data part))
                                            part))
                                        (interpose "/")))
          :query-string (when-let [keys (seq (reduce disj (set (keys data)) keyset))]
                          (query-string (select-keys data keys)))}))))

(defn- routes-tags [routes opts]
  (reduce (fn [result [_ path handler tag]]
            (let [uff        (uri-for-fn path)
                  ufhandler? (not (:uri-for-tagged-only? opts))]
              (cond-> result
                tag        (assoc tag uff)
                ufhandler? (assoc handler uff))))
          {} routes))


;;; Public API

(defn ^:no-doc compiled? [routes]
  (contains? routes :tree))

(defn compile-routes
  "Compiles a routes datastructure for use in `match-uri` and
  `uri-for`. Routes is a sequence of sequences (e.g. a vector of
  vectors) containing 3 or 4 elements: a method keyword (or :any), a
  clout-like path, a result object (can be a handler), and optionally
  a tag. For example:

  [[:get  \"/admin/user/\" user-list]
   [:get  \"/admin/user/:id\" user-get :user-page]
   [:post \"/admin/user/:id\" user-update]
   [:any  \"/:*\" handle-404]]

  The order in which the routes are specified does not matter. Longer
  routes always take precedence, exact uri parts take precedence over
  route parameters, catch-all (:*) is tried last, and specific request
  methods take precedence over :any.

  Compiling takes some opional keyword arguments:

   :uri-for-tagged-only? - When set to true, only tagged routes are
     compiled for use with `uri-for` and can only be found by their
     tag. Defaults to false.

  The routes are compiled into a tree structure, for fast matching.
  Functions for creating URIs (`uri-for`) are also precompiled for
  every route."
  [routes & {:as opts}]
  {:tree (routes-tree routes)
   :tags (routes-tags routes opts)})

(defn match-uri
  "Given compiled routes, an URI and a request-method, returns
  {:route-handler handler, :route-params {...} for a match, or nil.
  For example:

  (match-uri (compile-routes [[:post \"/admin/user/:id\" :update-user]])
             \"/admin/user/42\" :post)
  ;=> {:route-handler :update-user, :route-params {:id \"42\"}

  The values in :route-params are URL decoded for you."
  [compiled uri request-method]
  (match-uri* (:tree compiled) (str/split uri #"/") {} request-method))

(defn uri-for
  "Given compiled routes and a handler (or tag), and optionally
  parameters, returns {:uri \"...\", :query-string \"?...\"}. For
  example:

  (uri-for (compile-routes [[:post \"/admin/user/:id\" :update-user]])
           :update-user {:id 42 :name \"alice\"})
  ;=> {:uri \"/admin/user/42\", :query-string \"?name=alice\"}

  An exception is thrown if parameters for the URI are missing in the
  data map. The values in the data map are URL encoded for you."
  {:arglists '([compiled handler] [compiled tag])}
  ([compiled obj]
   (uri-for compiled obj nil))
  ([compiled obj data]
   (when-let [f (get (:tags compiled) obj)]
     (f data))))
