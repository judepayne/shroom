(ns shroom.dot
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;;;

(def ^:private escapable-characters "\\|{}\"")

(defn- escape-string
  "Escape characters that are significant for the dot format."
  [s]
  (reduce
    #(str/replace %1 (str %2) (str "\\" %2))
    s
    escapable-characters))

;;;

(def ^:private default-options
  {:dpi 72})

(def ^:private default-node-options
  {})

(def ^:private default-edge-options
  {})

;;;

(def ^:private option-translations
  {:vertical? [:rankdir {true :TP, false :LR}]})

(defn translate-options [m]
  (->> m
    (map
      (fn [[k v]]
        (if-let [[k* f] (option-translations k)]
          (when-not (contains? m k*)
            [k* (f v)])
          [k v])))
    (remove nil?)
    (into {})))

;;;

(defn ->literal [s]
  ^::literal [s])

(defn literal? [x]
  (-> x meta ::literal))

(defn unwrap-literal [x]
  (if (literal? x)
    (first x)
    x))

;;;



(defn- format-options-value [v]
  (let [v-str (str v)]
    (cond
      ;; added first condition to handle html like labels which don't
      ;; work if wrapped in quotes
      (str/starts-with? v-str "<<") v-str
      (string? v) (str \" (escape-string v) \")
      (keyword? v) (name v)
      (coll? v) (if (literal? v)
                  (str "\"" (unwrap-literal v) "\"")
                  (str "\""
                       (->> v
                            (map format-options-value)
                            (interpose ",")
                            (apply str))
                       "\""))
      :else (str v))))

(defn format-label [label]
  (cond
    (sequential? label)
    (->> label
      (map #(str "{ " (-> % format-label unwrap-literal) " }"))
      (interpose " | ")
      (apply str)
      ->literal)

    (string? label)
    label

    (nil? label)
    ""

    :else
    (pr-str label)))

(defn- format-options [m separator]
  (->>
    (update-in m [:label] #(when % (format-label %)))
    (remove (comp nil? second))
    (map
      (fn [[k v]]
        (str (name k) "=" (format-options-value v))))
    (interpose separator)
    (apply str)))

(defn- format-edge [src dst {:keys [directed?] :as options}]
  (let [options (update-in options [:label] #(or % ""))]
    (str src
      (if directed?
        " -> "
        " -- ")
      dst
      "[" (format-options (dissoc options :directed?) ", ") "]")))

(defn- format-node [id {:keys [label shape] :as options}]
  (let [shape (or shape
                (when (sequential? label)
                  :record))
        options (assoc options
                  :label (or label "")
                  :shape shape)]
    (str id "["
      (format-options options ", ")
      "]")))


;; added formatter for ranks
(defn- format-rank [ids]
  (apply str "{ rank=same; "
         (concat (interpose ", " ids) ["}"])))

;;;

(def ^:private ^:dynamic *node->id* nil)
(def ^:private ^:dynamic *cluster->id* nil)

(defn- node->id [n]
  (*node->id* n))

(defn- cluster->id [s]
  (*cluster->id* s))

;; added to allow for a node to be in more than one cluster which
;; is needed when we have nested clusters
(defn- clust->nds [f nodes] 
  (reduce-kv
   (fn [m k v]
     (let [separated (zipmap k (repeat (into #{} v)))]
       (merge-with set/union separated m)))
   {}
   (dissoc (group-by f nodes) nil)))


(defmacro dbg [body]
  `(let [x# ~body]
     (println "dbg:" '~body "=" x#)
     x#))


(defn tree-nodes [root children]
  (tree-seq children children root))


(defn graph->dot
  "Takes a description of a graph, and returns a string describing a GraphViz dot file.
   Requires two fields: `nodes`, which is a list of the nodes in the graph, and `adjacent`, which
   is a function that takes a node and returns a list of adjacent nodes."
  [nodes adjacent
   & {:keys [directed?
             vertical?
             options
             node->descriptor
             edge->descriptor
             root-cluster
             cluster->children
             node->cluster
             cluster->descriptor
             cluster->ranks]
      :or {directed? true
           vertical? true
           node->descriptor (constantly nil)
           edge->descriptor (constantly nil)
           root-cluster nil
           cluster->children (constantly nil)
           node->cluster (constantly nil)
           cluster->descriptor (constantly nil)
           cluster->ranks (constantly nil)}
      :as graph-descriptor}]

  (binding [*node->id* (or *node->id* (memoize (fn [_] (gensym "node"))))
            *cluster->id* (or *cluster->id* (memoize (fn [_] (gensym "cluster"))))]
    (let [clusters (when root-cluster
                     (if-let [clstrs (::clusters graph-descriptor)]
                       clstrs
                       (set (tree-seq cluster->children cluster->children root-cluster))))
          node? (set nodes)
          subsequent-pass? (::subsequent-pass? graph-descriptor)
          ranks (cluster->ranks root-cluster)]
      (letfn [(nodesfn [clstr]
                (->> nodes
                     (remove #(not= clstr (node->cluster %)))
                     (map
                      #(format-node (node->id %)
                                    (merge
                                     default-node-options
                                     (node->descriptor %))))))

              (subgraph [clstr]
                (apply graph->dot
                       nodes
                       adjacent
                       (apply concat
                              (assoc graph-descriptor
                                     :root-cluster clstr
                                     :options (cluster->descriptor clstr)
                                     ::clusters clusters
                                     ::subsequent-pass? true))))

              (clusterfn [clstr]
                (->> (cluster->children clstr)
                     (map subgraph)))]

        (apply
         str
         (if subsequent-pass?
           (str "\nsubgraph " (cluster->id root-cluster))
           (if directed?
             "digraph"
             "graph"))
         " {\n"

         ;; global options
         (let [edge-options (:edge options)
               node-options (:node options)]
           (str
            ;; graph[...]
            "graph["
            (-> (merge default-options options)
                (update-in [:fontname] #(or % (when subsequent-pass? "Monospace")))
                (assoc :vertical? vertical?)
                (dissoc :edge :node)
                translate-options
                (format-options ", "))
            "]\n"

            ;; node[...]
            "node["
            (-> node-options
                (update-in [:fontname] #(or % "Monospace"))
                (translate-options)
                (format-options ", "))
            "]\n"

            ;; edge[...]
            "edge["
            (-> edge-options
                (update-in [:fontname] #(or % "Monospace"))
                (translate-options)
                (format-options ", "))
            "]"))


         (concat
          
          ;; nodes
          "\n"
          (interpose "\n" (if subsequent-pass? (nodesfn root-cluster) (nodesfn nil)))
          

          ;; ranks
          (->> ranks
               (map
                (fn [r]
                  (format-rank
                   (mapv *node->id* r)))))

          ;; clusters
          (if-not subsequent-pass?
            (subgraph root-cluster)
            (clusterfn root-cluster))

          ;; edges
          (when-not subsequent-pass? "\n")
          (when-not subsequent-pass?

            (interpose "\n"
                       (->> nodes

                            ;; filter out destinations that aren't in `nodes`, and differentiate
                            ;; between nodes and clusters
                            (mapcat
                             (fn [node]
                               (map vector
                                    (repeat node)
                                    (->> node
                                         adjacent
                                         (map
                                          #(cond
                                             (node? %) [:node %]
                                             (clusters %) [:cluster %]
                                             :else nil))
                                         (remove nil?)))))

                            ;; format the edges
                            (map (fn [[a [type b]]]
                                   (let [descriptor (edge->descriptor a b)
                                         format #(format-edge
                                                  (node->id a)
                                                  (if (= :node type)
                                                    (node->id b)
                                                    (cluster->id b))
                                                  (merge
                                                   default-edge-options
                                                   {:directed? directed?}
                                                   %))]
                                     (if (vector? descriptor)
                                       (->> descriptor
                                            (map format)
                                            (interpose "\n")
                                            (apply str))
                                       (format descriptor))))))))
          "\n"

          ["}"]))))))
