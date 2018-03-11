(ns damaris.core
  (:require
   [clojure.set :refer [difference]]
   [com.rpl.specter :refer :all]
   [clojure.core.async :as async :refer [<! >! <!! timeout chan alt! go go-loop]]))

;; Relationships. each entitiy holds a list of things to which it has
;; a certain relationship. the "object" of the relationship holds a
;; list of the inverse relationship, which must then include
;; the "subject" thing.

;; when we assert a new relationship, we call the "assert" function
;; on the relationship, with the subject and object entities. that
;; function should compute the new entity sets for the subject and
;; object, then tell those entities, over their channels, if their
;; relationships have changed (for IO)

;; if we assert an entity is next to a new thing, the entity is
;; considered also next to those things which the new entity is next
;; to.

(def instances
  (atom {}))

(def objects
  (atom
   {:person {:attrs {:name ""}
             :interfaces {:lifecycle {:create (fn [self context]
                                                (str (-> self :attrs :name) " was wrought from the void."))
                                      :destroy (fn [self context]
                                                 (str (-> self :attrs :name) " was returned to the void."))}
                          :standard {:identity (fn [self context]
                                                 (str (-> self :attrs :name)))}
                          :converse {:tell (fn [self from msg]
                                             (go (>! (:ui-chan self)
                                                     (str (-> from :attrs :name)
                                                          ": " msg))))}}}
    :cat {:attrs {:name ""
                  :color ""}
          :interfaces {:lifecycle {:create (fn [self context]
                                             (str "Moew! "(-> self :attrs :name) " was wrought from the void."))
                                   :destroy (fn [self context]
                                              (str (-> self :attrs :name) " crossed over the rainbow bridge"))}
                       :standard {:identity (fn [self context]
                                              (str (-> self :attrs :name)))}
                       :converse {:tell (fn [self from msg]
                                          (do
                                            (go (>! (:ui-chan self)
                                                    (str (-> from :attrs :name)
                                                         ": " msg)))
                                            (go (>! (:ui-chan from)
                                                    (str (-> self :attrs :name)
                                                         ": Meow?")))))}}}}))

(defn message [subject reltype rel dir object voice]
  (go (>! (:inbox (subject @instances))
          [reltype rel dir object voice])))

(def relationships
  (atom
   {:containing  {:in       {:short "inside of"
                              :inv :contains}
                  :contains {:short "contains"
                             :inv :in}}
    :adjacency   {:next-to {:short "next to"
                            :inv :next-to
                            :assert
                            (fn [sub-id obj-id instances]
                              ;; "S is next to O"
                              (let [subject   (sub-id instances)
                                    object    (obj-id instances)
                                    s-next-to (get-in subject
                                                      [:rel-self
                                                       :adjacency
                                                       :next-to]
                                                      #{})
                                    o-next-to (conj (get-in object
                                                            [:rel-self
                                                             :adjacency
                                                             :next-to]
                                                            #{})
                                                    (:id object))
                                    no-longer-next-to (difference
                                                       s-next-to o-next-to)
                                    newly-next-to (difference o-next-to s-next-to)]
                                (doseq [t no-longer-next-to]
                                  (message t :adjacency :next-to :part
                                           sub-id :passive))
                                (doseq [t no-longer-next-to]
                                  (message sub-id :adjacency
                                           :next-to :part t :active))
                                (doseq [t newly-next-to]
                                  (message t :adjacency :next-to :join
                                           sub-id :passive))
                                (doseq [t newly-next-to]
                                  (message sub-id :adjacency
                                           :next-to :join t :active))))
                            :join
                            (fn [self target voice]
                              (let [next-to (get-in @instances
                                                    [(:id self)
                                                     :rel-self
                                                     :adjacency
                                                     :next-to] #{})
                                    target-type (:type (target @instances))
                                    target-ident ((-> @objects
                                                      target-type
                                                      :interfaces :standard
                                                      :identity)
                                                  (-> @instances target) {})]
                                (swap! instances assoc-in
                                       [(:id self)
                                        :rel-self
                                        :adjacency
                                        :next-to]
                                       (conj next-to target))
                                ;; probably want to allow objects to alter
                                ;; their UI for this...
                                (go (>! (:ui-chan self)
                                        (if (= voice :active)
                                          (str "You are next to "
                                               target-ident
                                               " (a " (name target-type) ").")
                                          (str
                                           target-ident
                                           " (a " (name target-type) ") "
                                           "is next to you."))))))
                            :part
                            (fn [self target voice]
                              (let [next-to (get-in @instances
                                                    [(:id self)
                                                     :rel-self
                                                     :adjacency
                                                     :next-to] #{})
                                    target-type (:type (target @instances))
                                    target-ident ((-> @objects
                                                      target-type
                                                      :interfaces :standard
                                                      :identity)
                                                  (-> @instances target) {})]
                                (swap! instances assoc-in
                                       [(:id self)
                                        :rel-self
                                        :adjacency
                                        :next-to]
                                       (disj next-to target))
                                (go (>! (:ui-chan self)
                                        (if (= voice :active)
                                          (str "You are no longer next to "
                                               target-ident ".")
                                          (str
                                           target-ident
                                           " is no longer next to you."))))))}}}))

(defn uuid [] (str (java.util.UUID/randomUUID)))

;; actions!
;; there are abstract things which the user can use directly.
;; they figure out, based on who we are and where we are (and if we've passed
;; them an explicit parameter), *what is affected by our action*
;; users should be able to define these
(defn lifecycle-create [obj-name args context]
  (if-let [o (-> @objects obj-name :interfaces :lifecycle :create)]
    (let [attrs   (reduce (fn [a [k v]] (assoc a k (or (k args) v)))
                          {}
                          (-> @objects obj-name :attrs))
          self    {:id     (keyword (uuid))
                   :type   obj-name
                   :attrs  attrs
                   :inbox  (chan) ;; so far just notifications of changing relationships
                   :ui-chan (chan)}]
      (go-loop []
        (let [[reltype rel dir object voice] (<! (:inbox self))]
          ;; call the relationship function
          ;; with my current value
          ((-> @relationships reltype rel dir)
           (-> @instances (:id self))
           object voice))
        (recur))
      (go-loop []
        (let [msg   (<! (:ui-chan self))
              ident ((-> @objects obj-name :interfaces :standard :identity)
                     (-> @instances (:id self)) {})]
          (println (str  ident " :: " msg)))
        (recur))
      (swap! instances assoc (:id self) self)
      (println (o self {}))
      (:id self))))

(defn lifecycle-destroy [instance args context]
  (let [obj ((:type instance) @objects)
        f   (-> obj :interfaces :lifecycle :destroy)]
    (do
      (println (f instance {}))
      (swap! instances dissoc (:id instance)))))

;; directy tell someone something. operates over infinite space
(defn tell [from to msg]
  (let [to-inst   (to @instances)
        from-inst (from @instances)
        type      (:type to-inst)
        f         (-> @objects type :interfaces :converse :tell)]
    (f to-inst from-inst msg)
    nil))

;; say. anyone adjacenct to us will hear it
(defn say [from msg]
  (let [from-inst (from @instances)
        adjs      (get-in from-inst
                          [:rel-self :adjacency :next-to] #{})]
    ;; hmm from the perspective of the listener, this is indistinguishable
    ;; from a `tell`. What's the expectation?
    (doseq [to adjs]
      (tell from to msg))))

;; primary movement command?
(defn move-next-to [subject object]
  ((-> @relationships :adjacency :next-to :assert) subject object @instances))


(def p (lifecycle-create :person {:name "pw"} {}))
(def p (lifecycle-create :cat {:name "Sal"} {}))
(def abby (lifecycle-create :cat {:name "Abby"} {}))
(def bailey (lifecycle-create :cat {:name "Bailey"} {}))

(move-next-to j m)
(move-next-to bailey j)
(say bailey "Oh hi")
((-> @relationships :adjacency :next-to :assert) j p @instances)
((-> @relationships :adjacency :next-to :assert) m f @instances)
((-> @relationships :adjacency :next-to :assert) j p @instances)
((-> @relationships :adjacency :next-to :assert) p f @instances)
((-> @relationships :adjacency :next-to :assert) bailey j @instances)
((-> @relationships :adjacency :next-to :assert) abby m @instances)

(tell j p "Here kitty!")
(tell j m "Here kitty!")
(tell m j "Well, well.")
(say f "That's funny")
(say j "Yum")

(lifecycle-destroy j {} {})
(lifecycle-destroy bailey {} {})



;; (defn create-relationship [subj [fam rel] obj]
;;   ;; the cat (subj) is in (rel) the bag (obj)
;;   (let [[current-rel current-obj] (get-in subj [:rel-self fam])
;;         new-sub (-> subj
;;                    (update-in [:rel-self] dissoc fam)
;;                    (assoc-in [:rel-self fam] [rel (:id obj)]))

;;         obj-set-leaving (disj (get-in current-obj [:rel-others fam current-rel] #{})
;;                               (:id subj))

;;         inv-rel (-> @relationships fam rel :inv)
;;         new-leaving-obj (assoc-in current-obj [:rel-others fam inv-rel] obj-set-leaving)
;;         obj-set-joining (conj (get-in current-obj [:rel-others fam inv-rel] #{})
;;                               (:id subj))
;;         new-joining-obj (assoc-in obj [:rel-others fam inv-rel] obj-set-joining)]
;;     (println current-rel)
;;     (println current-obj)
;;     (println new-sub)
;;     (println obj-set-leaving)
;;     (println new-leaving-obj)
;;     (println inv-rel)
;;     (println obj-set-joining)
;;     (println new-joining-obj)
;;     [new-sub new-leaving-obj new-joining-obj]))


                            ;; (->> instances
                            ;;     (transform
                            ;;      [(must (apply multi-path (vec no-longer-next-to)))
                                ;;       :rel-self (nil->val {})
                                ;;       :adjacency (nil->val {})
                                ;;       :next-to (nil->val #{:zonk})]
                                ;;      (fn [s] (disj s (:id subject))))
                                ;;     (transform
                                ;;      [(must (apply multi-path (vec newly-next-to)))
                                ;;       :rel-self (nil->val {})
                                ;;       :adjacency (nil->val {})
                                ;;       :next-to (nil->val #{})]
                                ;;      (fn [s] (conj s (:id subject))))
                                ;;     (transform
                                ;;      [(:id subject)
                                ;;       :rel-self (nil->val {})
                                ;;       :adjacency (nil->val {})
                                ;;       :next-to (nil->val #{:adasd})]
                                ;;      (fn [s] (conj s (:id object)))))


    ;; :directional {:next-to {:short "next to"
    ;;                          :inv :next-to}
    ;;                :above   {:short "above"
    ;;                          :inv :below}
    ;;                :below   {:short "below"
    ;;                          :inv :above}
    ;;                :north   {:short "north of"
    ;;                          :inv :south}
    ;;                :south   {:short "south of"
    ;;                          :inv :north}
    ;;                :east    {:short "east of"
    ;;                          :inv :west}
    ;;                :west    {:short "west of"
    ;;                          :inv :east}}

;; object instances

;; ;; relationship instances
;; (def rinstances
;;   (atom {}))
