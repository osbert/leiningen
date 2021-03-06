(ns leiningen.pom
  "Write a pom.xml file to disk for Maven interoperability."
  (:require [leiningen.core.main :as main]
            [leiningen.core.project :as project]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.data.xml :as xml]))

(defn- relativize [project]
  (let [root (str (:root project) "/")]
    (reduce #(update-in %1 [%2]
                        (fn [xs]
                          (if (seq? xs)
                            (vec (for [x xs]
                                   (.replace x root "")))
                            (and xs (.replace xs root "")))))
            project
            [:target-path :compile-path :source-paths :test-paths
             :resource-paths :java-source-paths])))

;; git scm

(defn- resolve-git-dir [project]
  (let [alternate-git-root (io/file (get-in project [:scm :dir]))
        git-dir-file (io/file (or alternate-git-root (:root project)) ".git")]
    (if (.isFile git-dir-file)
      (io/file (second (re-find #"gitdir: (\S+)" (slurp (str git-dir-file)))))
      git-dir-file)))

(defn- read-git-ref
  "Reads the commit SHA1 for a git ref path, or nil if no commit exist."
  [git-dir ref-path]
  (let [ref (io/file git-dir ref-path)]
    (if (.exists ref)
      (.trim (slurp ref))
      nil)))

(defn- read-git-head
  "Reads the value of HEAD and returns a commit SHA1, or nil if no commit
  exist."
  [git-dir]
  (let [head (.trim (slurp (str (io/file git-dir "HEAD"))))]
    (if-let [ref-path (second (re-find #"ref: (\S+)" head))]
      (read-git-ref git-dir ref-path)
      head)))

(defn- read-git-origin
  "Reads the URL for the remote origin repository."
  [git-dir]
  (with-open [rdr (io/reader (io/file git-dir "config"))]
    (->> (map #(.trim %) (line-seq rdr))
         (drop-while #(not= "[remote \"origin\"]" %))
         (next)
         (take-while #(not (.startsWith % "[")))
         (map #(re-matches #"url\s*=\s*(\S*)\s*" %))
         (filter identity)
         (first)
         (second))))

(defn- parse-github-url
  "Parses a GitHub URL returning a [username repo] pair."
  [url]
  (if url
    (rest
     (or (re-matches #"(?:git@)?github.com:([^/]+)/([^/]+).git" url)
         (re-matches #"[^:]+://(?:git@)?github.com/([^/]+)/([^/]+).git" url)))))

(defn- github-urls [url]
  (if-let [[user repo] (parse-github-url url)]
    {:public-clone (str "git://github.com/" user "/" repo ".git")
     :dev-clone (str "ssh://git@github.com/" user "/" repo ".git")
     :browse (str "https://github.com/" user "/" repo)}))

(defn- make-git-scm [git-dir]
  (try
    (let [origin (read-git-origin git-dir)
          head (read-git-head git-dir)
          urls (github-urls origin)]
      [:scm
       (and (:public-clone urls)
            [:connection (str "scm:git:" (:public-clone urls))])
       (and (:dev-clone urls)
            [:developerConnection (str "scm:git:" (:dev-clone urls))])
       (and head
            [:tag head])
       [:url (:browse urls)]])
    (catch java.io.FileNotFoundException _)))

(def ^{:doc "A notice to place at the bottom of generated files."} disclaimer
     "\n<!-- This file was autogenerated by Leiningen.
  Please do not edit it directly; instead edit project.clj and regenerate it.
  It should not be considered canonical data. For more information see
  https://github.com/technomancy/leiningen -->\n")

(defn- camelize [string]
  (s/replace string #"[-_](\w)" (comp s/upper-case second)))

(defn- pomify [key]
  (->> key name camelize keyword))

(defmulti ^:private xml-tags
  (fn [tag value] (keyword "leiningen.pom" (name tag))))

(defn- guess-scm [project]
  "Returns the name of the SCM used in project.clj or \"auto\" if nonexistant.
  Example: :scm {:name \"git\" :tag \"deadbeef\"}"
  (or (-> project :scm :name) "auto"))

(defn- xmlify [scm]
  "Converts the map identified by :scm"
  (map #(xml-tags (first %) (second %)) scm))

(defn- write-scm-tag [scm project]
  "Write the <scm> tag for pom.xml.
  Retains backwards compatibility without an :scm map."
  (if (= "auto" scm)
    (make-git-scm (resolve-git-dir project))
    (xml-tags :scm (xmlify (select-keys (:scm project)
                                        [:url :connection
                                         :tag :developerConnection])))))

(defmethod xml-tags :default
  ([tag value]
     (and value [(pomify tag) value])))

(defmethod xml-tags ::list
  ([tag values]
     [(pomify tag) (map (partial xml-tags
                                 (-> tag name (s/replace #"ies$" "y") keyword))
                        values)]))

(doseq [c [::dependencies ::repositories]]
  (derive c ::list))

(defmethod xml-tags ::exclusions
  [tag values]
  (and (seq values)
       [:exclusions
        (for [exclusion-spec values
              :let [[dep & {:keys [classifier extension]}]
                    (if (symbol? exclusion-spec)
                      [exclusion-spec]
                      exclusion-spec)]]
          [:exclusion (map (partial apply xml-tags)
                           {:group-id (or (namespace dep)
                                          (name dep))
                            :artifact-id (name dep)
                            :classifier classifier
                            :type extension})])]))

(defmethod xml-tags ::dependency
  ([_ [dep version & {:keys [optional classifier
                             exclusions scope
                             extension]}]]
     [:dependency
      (map (partial apply xml-tags)
           {:group-id (or (namespace dep) (name dep))
            :artifact-id (name dep)
            :version version
            :optional optional
            :classifier classifier
            :type extension
            :exclusions exclusions
            :scope scope})]))

(defmethod xml-tags ::repository
  ([_ [id opts]]
     [:repository
      (map (partial apply xml-tags)
           {:id id
            :url (:url opts)
            :snapshots (xml-tags :enabled
                                 (str (if (nil? (:snapshots opts))
                                        true
                                        (boolean
                                         (:snapshots opts)))))
            :releases (xml-tags :enabled
                                (str (if (nil? (:releases opts))
                                       true
                                       (boolean
                                        (:releases opts)))))})]))

(defmethod xml-tags ::license
  ([_ opts]
     (and opts
          (if-let [tags (if (string? opts)
                          [:name opts]
                          (seq (for [key [:name :url :distribution :comments]
                                     :let [val (opts key)] :when val]
                                 [key (name val)])))]
            [:licenses [:license tags]]))))

(defn- resource-tags [project type]
  (if-let [resources (seq (:resource-paths project))]
    (let [types (keyword (str (name type) "s"))]
      (vec (concat [types]
                   (for [resource resources]
                     [type [:directory resource]]))))))

(defmethod xml-tags ::build
  ([_ [project test-project]]
     (let [[src & extra-src] (concat (:source-paths project)
                                     (:java-source-paths project))
           [test & extra-test] (:test-paths test-project)]
       [:build
        [:sourceDirectory src]
        (xml-tags :testSourceDirectory test)
        (resource-tags project :resource)
        (resource-tags test-project :testResource)
        (if-let [extensions (seq (:extensions project))]
          (vec (concat [:extensions]
                       (for [[dep version] extensions]
                         [:extension
                          [:artifactId (name dep)]
                          [:groupId (or (namespace dep) (name dep))]
                          [:version version]]))))
        [:directory (:target-path project)]
        [:outputDirectory (:compile-path project)]
        (if (or (seq extra-src) (seq extra-test))
          [:plugins
           [:plugin
            [:groupId "org.codehaus.mojo"]
            [:artifactId "build-helper-maven-plugin"]
            [:version "1.7"]
            [:executions
             (if (seq extra-src)
               [:execution
                [:id "add-source"]
                [:phase "generate-sources"]
                [:goals [:goal "add-source"]]
                [:configuration
                 (vec (concat [:sources]
                              (map (fn [x] [:source x]) extra-src)))]])
             (if (seq extra-src)
               [:execution
                [:id "add-test-source"]
                [:phase "generate-test-sources"]
                [:goals [:goal "add-test-source"]]
                [:configuration
                 (vec (concat [:sources]
                              (map (fn [x] [:source x]) extra-test)))]])]]])])))

(defmethod xml-tags ::parent
  ([_ [dep version & opts]]
     (let [opts (apply hash-map opts)]
       [:parent
        [:artifactId (name dep)]
        [:groupId (or (namespace dep) (name dep))]
        [:version version]
        [:relativePath (:relative-path opts)]])))

(defmethod xml-tags ::mailing-list
  ([_ opts]
     (if opts
       [:mailingLists
        [:mailingList
         [:name (:name opts)]
         [:subscribe (:subscribe opts)]
         [:unsubscribe (:unsubscribe opts)]
         [:post (:post opts)]
         [:archive (:archive opts)]
         (if-let [other-archives (:other-archives opts)]
           (vec (concat [:otherArchives]
                        (for [other other-archives]
                          [:otherArchive other]))))]])))

(defn- distinct-key [k xs]
  ((fn step [seen xs]
     (lazy-seq
      (when (seq xs)
        (let [x (first xs), key (k x)]
          (if (seen key)
            (step seen (rest xs))
            (cons x (step (conj seen key) (rest xs))))))))
   #{} (seq xs)))

(defn- make-scope [scope [dep version & opts]]
  (list* dep version (apply concat (assoc (apply hash-map opts) :scope scope))))

(defmethod xml-tags ::project
  ([_ project]
     (let [reprofile #(-> project (project/merge-profiles %) (relativize))
           provided-project (reprofile [:provided])
           test-project (reprofile [:provided :dev :test :default])]
       (list
        [:project {:xsi:schemaLocation
                   (str "http://maven.apache.org/POM/4.0.0"
                        " http://maven.apache.org/xsd/maven-4.0.0.xsd")
                   :xmlns "http://maven.apache.org/POM/4.0.0"
                   :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"}
         [:modelVersion "4.0.0"]
         (and (:parent project) (xml-tags :parent (:parent project)))
         [:groupId (:group project)]
         [:artifactId (:name project)]
         [:packaging (:packaging project "jar")]
         [:version (:version project)]
         (and (:classifier project) [:classifier (:classifier project)])
         [:name (:name project)]
         [:description (:description project)]
         (xml-tags :url (:url project))
         (xml-tags :license (:license project))
         (xml-tags :mailing-list (:mailing-list project))
         (write-scm-tag (guess-scm project) project)
         (xml-tags :build [project test-project])
         (xml-tags :repositories (:repositories project))
         (xml-tags :dependencies
                   (->> (concat (->> project :dependencies)
                                (->> provided-project :dependencies
                                     (map (partial make-scope "provided")))
                                (->> test-project :dependencies
                                     (map (partial make-scope "test"))))
                        (distinct-key (partial take 2))))
         (and (:pom-addition project) (:pom-addition project))]))))

(defn snapshot? [project]
  (re-find #"SNAPSHOT" (:version project)))

(defn check-for-snapshot-deps [project]
  (when (and (not (snapshot? project))
             (not (System/getenv "LEIN_SNAPSHOTS_IN_RELEASE"))
             (some #(re-find #"SNAPSHOT" (second %)) (:dependencies project)))
    (main/abort "Release versions may not depend upon snapshots."
                "\nFreeze snapshots to dated versions or set the"
                "LEIN_SNAPSHOTS_IN_RELEASE environment variable to override.")))

(defn make-pom
  ([project] (make-pom project false))
  ([project disclaimer?]
     (let [special-profiles [:user :provided :dev :test :default]
           project (project/unmerge-profiles project special-profiles)]
       (check-for-snapshot-deps project)
       (str
        (xml/indent-str
         (xml/sexp-as-element
          (xml-tags :project (relativize project))))
        (if disclaimer? disclaimer)))))

(defn make-pom-properties [project]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (let [properties (doto (java.util.Properties.)
                       (.setProperty "version" (:version project))
                       (.setProperty "groupId" (:group project))
                       (.setProperty "artifactId" (:name project)))
          git-head (resolve-git-dir project)]
      (when (.exists git-head)
        (if-let [revision (read-git-head git-head)]
          (.setProperty properties "revision" revision)))
      (.store properties baos "Leiningen"))
    (str baos)))

(defn ^{:help-arglists '([])} pom
  "Write a pom.xml file to disk for Maven interoperability."
  ([project pom-location]
     (let [pom (make-pom project true)
           pom-file (io/file (:root project) pom-location)]
       (.mkdirs (.getParentFile pom-file))
       (with-open [pom-writer (io/writer pom-file)]
         (.write pom-writer pom))
       (main/info "Wrote" (str pom-file))
       (.getAbsolutePath pom-file)))
  ([project] (pom project "pom.xml")))
