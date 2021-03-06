(ns levanzo.spec.jsonld
  (:require [clojure.string :as string]
            [clojure.spec :as s]
            [clojure.spec.test :as stest]
            [clojure.spec.gen :as gen]
            [clojure.test.check.generators :as tg])
  (:import [org.apache.commons.validator.routines UrlValidator]))


;; URI string

(def scheme-generator (s/gen #{"http://" "https://"}))
(def domain-generator (tg/fmap (fn [parts] (string/join "." parts))
                               (s/gen (s/or :dns (s/tuple (s/and string?
                                                                 #(re-matches #"[a-zA-Z0-9]+" %))
                                                          #{"com" "org"})
                                            :numeric (s/coll-of (s/and integer?
                                                                       #(<= % 255)
                                                                       #(>= % 0))
                                                                :min-count 4
                                                                :max-count 4)))))

(def path-component-regex #"^([a-zA-ZA-Z0-9-._~!$&'()*+,;=:]|%[0-9A-F]{2})+$")
(s/def ::path-component (s/with-gen (s/and string?
                                           #(re-matches path-component-regex %))
                          #(tg/fmap (fn [s] (apply str (take 5 s)))
                                    tg/string-alphanumeric)))

;; URI path definitions
(s/def ::relative-path (s/with-gen (s/and string?
                                          #(not (re-matches #"^(\/|\#).*$" %))
                                          (fn [path]
                                            (let [path (-> (string/split path #"\?") first)
                                                  parts (string/split path #"\/")]
                                              (->> parts
                                                   (mapv #(re-matches path-component-regex %))
                                                   (reduce (fn [acc v] (and acc v)) true)))))
                         #(tg/fmap (fn [parts] (string/join "/" parts))
                                   (s/gen (s/coll-of ::path-component :min-count 1 :max-count 10)))))



(s/def ::absolute-path (s/with-gen (s/and string?
                                          #(re-matches #"^(\/|\#).*$" %)
                                          (fn [path]
                                            (let [path (-> (string/split path #"\?") first)
                                                  path (string/replace-first path "#" "")
                                                  parts (string/split path #"\/")]
                                              (->> parts
                                                   (drop 1)
                                                   (mapv #(re-matches path-component-regex %))
                                                   (reduce (fn [acc v] (and acc v)) true)))))
                         #(tg/fmap (fn [relative-path] (str "/" relative-path))
                                   (s/gen ::relative-path))))


(s/def ::path-variable (s/with-gen keyword?
                         #(s/gen #{:user_id :ticket_id :order_id :event_id})))

(def url-validator (UrlValidator. UrlValidator/ALLOW_LOCAL_URLS))

(s/def ::uri (s/with-gen (s/and string?
                                #(or
                                  (.isValid url-validator %)
                                  (.isValid url-validator (string/replace-first (str "http://localhost/" %) "http://localhost//" "http://localhost/")))
                                #(re-matches #"^([a-zA-Z0-9+.-]+):(?://(?:((?:[a-zA-Z0-9-._~!$&'()*+,;=:]|%[0-9A-F]{2})*)@)?((?:[a-zA-Z0-9-._~!$&'()*+,;=]|%[0-9A-F]{2})*)(?::(\d*))?(/(?:[a-zA-Z0-9-._~!$&'()*+,;=:@/]|%[0-9A-F]{2})*)?|(/?(?:[a-zA-Z0-9-._~!$&'()*+,;=:@]|%[0-9A-F]{2})+(?:[a-zA-Z0-9-._~!$&'()*+,;=:@/]|%[0-9A-F]{2})*)?)(?:\?((?:[a-zA-Z0-9-._~!$&'()*+,;=:/?@]|%[0-9A-F]{2})*))?(?:#((?:[a-zA-Z0-9-._~!$&'()*+,;=:/?@]|%[0-9A-F]{2})*))?$" %))
               #(tg/fmap (fn [comps] (apply str comps))
                         (tg/tuple scheme-generator domain-generator (s/gen ::absolute-path)))))

;; CURIE string
(s/def ::curie (s/with-gen
                 (s/and string? #(re-matches #".*\:.+" %))
                 #(tg/fmap (fn [[p s]] (str p ":" s))
                           (tg/tuple (tg/fmap (fn [s] (apply str (take 5 s))) tg/string-alphanumeric)
                                     (tg/fmap (fn [s] (apply str (take 5 s))) tg/string-alphanumeric)))))


;; Hydra vocabulary term for this element in the model
(s/def ::term (s/or
               :uri ::uri
               :curie ::curie))

(s/def ::path (s/or
               :relative-path ::relative-path
               :absolute-path ::absolute-path))

(s/def ::datatype (s/with-gen ::uri
                    #(s/gen #{"http://www.w3.org/2001/XMLSchema#string"
                              "http://www.w3.org/2001/XMLSchema#decimal"
                              "http://www.w3.org/2001/XMLSchema#boolean"
                              "http://www.w3.org/2001/XMLSchema#float"})))

(s/def ::jsonld-literal-value (s/or
                               :string string?
                               :boolean boolean?
                               :number number?))

(s/def ::language-value (s/with-gen string?
                          #(s/gen #{"en" "es" "it"})))

(s/def ::jsonld-literal (s/with-gen (s/and
                                     (s/merge (s/every (s/or :language (s/tuple #{"@language"} ::language-value)
                                                             :type     (s/tuple #{"@type"} ::datatype)
                                                             :value    (s/tuple #{"@value"} ::jsonld-literal-value))
                                                       :gen-min 1
                                                       :gen-max 3
                                                       :into {}))
                                     ;; value is mandatory
                                     #(some? (get % "@value"))
                                     ;; language should only be there if this is a string
                                     #(if (some? (get % "@language"))
                                        (or (nil? (get % "@type"))
                                            (= (get % "@type")
                                               "http://www.w3.org/2001/XMLSchema#string"))
                                        true))
                          ;; generator
                          #(tg/fmap (fn [literal-value]
                                      (let [ret (cond
                                                  (string? literal-value) {"@value" literal-value
                                                                           "@language" (first (gen/sample (s/gen ::language-value)))}
                                                  (boolean? literal-value) {"@value" literal-value
                                                                            "@type" "http://www.w3.org/2001/XMLSchema#boolean"}
                                                  (float? literal-value) {"@value" literal-value
                                                                          "@type" "http://www.w3.org/2001/XMLSchema#float"}
                                                  (number? literal-value) {"@value" literal-value
                                                                           "@type" "http://www.w3.org/2001/XMLSchema#decimal"}
                                                  :else                  {"@value" literal-value
                                                                          "@type" "http://www.w3.org/2001/XMLSchema#any"})]
                                        ret))
                                    (s/gen ::jsonld-literal-value))))

(s/def ::link (s/map-of #{"@id"} (s/or :id ::uri
                                       :path ::path)))
(s/def ::expanded-jsonld (s/merge (s/every (s/or :id (s/tuple #{"@id"} ::uri)
                                                 :type (s/tuple #{"@type"} (s/coll-of ::uri :gen-max 2 :min-count 1))
                                                 :literal (s/tuple ::uri (s/coll-of ::jsonld-literal :gen-max 2 :min-count 1))
                                                 :nested (s/tuple ::uri (s/coll-of ::expanded-jsonld :gen-max 2 :min-count 1)))
                                           :gen-max 4
                                           :gen-min 1
                                           :into {})))
