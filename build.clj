(ns build
  (:require
    [clojure.java.io :as io]
    [clojure.data.xml :as data.xml]
    [clojure.data.zip.xml :as zip.xml]
    [clojure.zip :as zip]
    [clojure.tools.build.api :as b]
    [juxt.pack.api :as pack]))

(data.xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn- pom
  [tag version]
  (let [xml (data.xml/parse (io/reader "template-pom.xml"))
        zipper (zip/xml-zip xml)]
    (-> zipper
        (zip.xml/xml1-> ::pom/project)
        (zip/insert-child (data.xml/element ::pom/version nil version))
        (zip.xml/xml1-> ::pom/scm)
        (zip/insert-child (data.xml/element ::pom/tag nil tag))
        zip/root)))

(defn jar
  [_]
  (pack/library
    {:basis (b/create-basis)
     :path "test-contract.jar"
     :lib 'com.griffin/test.contract
     :pom (let [tag (or (b/git-process {:git-args ["describe" "--exact-match"]})
                        (do
                          (binding [*out* *err*]
                            (println "WARNING: Not on a tagged version"))
                          (b/git-process {:git-args ["describe"]})))
                version (subs tag 1)
                xml (pom tag version)]
            (with-open [w (io/writer (io/file "pom.xml"))]
              (data.xml/emit xml w))
            (java.io.ByteArrayInputStream.
              (.getBytes
                (data.xml/emit-str xml)
                "UTF-8")))}))
