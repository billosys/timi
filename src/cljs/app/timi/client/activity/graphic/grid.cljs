(ns timi.client.activity.graphic.grid
  (:require
    [cljs-time.coerce :as cljs-coerce]
    [cljs-time.format :as cljs-format]
    [clojure.string :as string]
    [clojure.walk :refer [keywordize-keys]]
    [om.core :as om]
    [om.dom :as dom]
    [timi.client.actions :as actions]
    [timi.client.activity.graphic.draw :as draw]
    [timi.client.logging :refer [log log-cljs]]
    [timi.client.time.state :as state]
    [timi.client.util :refer [parse-float]]))

(def ZoneId (.-ZoneId js/JSJoda))
(def LocalDate (.-LocalDate js/JSJoda))
(def LocalTime (.-LocalTime js/JSJoda))
(def ChronoUnit (.-ChronoUnit js/JSJoda))
(def Duration (.-Duration js/JSJoda))
(def DayOfWeek (.-DayOfWeek js/JSJoda))
(def Instant (.-Instant js/JSJoda))

(def long-format "EEEE, d MMMM yyyy")
(def long-formatter (cljs-format/formatter long-format))
(def default-min-time (.parse LocalTime "08:00"))
(def default-max-time (.parse LocalTime "18:00"))

(def days-order [6 0 1 2 3 4 5])

(defn find-first-monday
  [for-date]
  (if (string? for-date) (recur (.parse LocalDate for-date))
    (if (= (.dayOfWeek for-date) (.-MONDAY DayOfWeek))
      for-date
      (recur (.plusDays for-date -1)))))

(defn bars-sum-duration
  [bars]
  (.ofMinutes
    Duration
    (reduce (fn [sum {:keys [from till] :as next}]
              (+ sum (.until from till (.-MINUTES ChronoUnit))))
            0 bars)))

(defn bars-merge
  [bars]
  (letfn [(bars-overlap? [a b]
            (if (neg? (.compareTo (:from b) (:from a)))
              (recur b a)
              (>= (.compareTo (:till a) (:from b)) 0)))

          (merge-recur [merged [first second & more :as remaining]]
            (cond
              (not first) merged
              (or
                (not second)
                (not (bars-overlap? first second))) (recur (conj merged first)
                                                           (rest remaining))
              :else (recur merged (concat [{:from (:from first)
                                            :till (:till second)}]
                                          more))))]
    (merge-recur [] (sort (fn [{a-from :from} {b-from :from}]
                            (.compareTo a-from b-from))
                          bars))))

(defn date->grid-label
  [for-date]
  (str (.dayOfWeek for-date)))

(defn init-data
  [project-data]
  (->> project-data
       (sort (fn [{a-from :from} {b-from :from}] (compare a-from b-from)))
       (map (fn [{:keys [from till task-id] :as project-row}]
              (assoc project-row
                     :task-id (parse-float task-id)
                     :from (.ofEpochSecond Instant from)
                     :till (.ofEpochSecond Instant till))))
       (group-by :project)
       (map (fn [[k vs]]
              {:label k
               :bars (->> vs
                          (sort (fn [{a-from :from}
                                     {b-from :from}]
                                  (.compareTo a-from b-from))))}))))

(defn calc-min-max-time
  [projects]
  (cond
    (zero? (count projects))
      [default-min-time default-max-time]
    :else
      [(->> projects
            (mapcat :bars)
            (map #(.ofInstant LocalTime (:from %)))
            (sort (fn [a b] (.compareTo a b)))
            (take 1)
            (map #(.truncatedTo % (.-HOURS ChronoUnit)))
            (first))
       (->> projects
            (mapcat :bars)
            (map #(.ofInstant LocalTime (:till %)))
            (sort (fn [a b] (.compareTo b a)))
            (take 1)
            (map #(.truncatedTo % (.-HOURS ChronoUnit)))
            (map #(.plusHours % 1))
            (first))]))

(defn instant-to-x
  [min-time min-date minutes-per-day pixels-per-minute instant]
  (let [day (.until min-date
              (.ofInstant LocalDate instant)
              (.-DAYS ChronoUnit))
        time (.ofInstant LocalTime instant)]
    (+ (* day minutes-per-day pixels-per-minute)
       (* pixels-per-minute (.until min-time time (.-MINUTES ChronoUnit)))
       (draw/const [:left-axis-px]))))

(defn iterate-day-grid
  [min-time min-date instant-to-x f init-value]
  (reduce
    (fn [acc day-index]
      (let [get-instant (fn [for-day-index]
                          (.. min-date
                              (plusDays for-day-index)
                              (atTime min-time)
                              (atZone (.systemDefault ZoneId))
                              (toInstant)))
            day-instant (get-instant day-index)
            next-day-instant (get-instant (inc day-index))
            x1 (instant-to-x day-instant)
            x2 (instant-to-x next-day-instant)]
        (f {:left-x x1
            :right-x x2
            :left-instant day-instant
            :right-instant next-day-instant
            :is-first (= day-index (first days-order))
            :is-last (= day-index (last days-order))} acc)))
    init-value
    days-order))

(defn render-grid-labels
  [on-change-date selected-date min-time max-time iterate-day-grid draw-result]
  (->>
    draw-result
    (iterate-day-grid
      (fn [{:keys [left-x left-instant]}
           {:keys [y-offset] :as draw-result}]
        (let [date (.ofInstant LocalDate left-instant)
              date-elem1 (.getElementById js/document "entries-for-day-date")
              date-elem2 (.getElementById js/document "day-entry-table-for-date")]
          ;; XXX these next two are hacks until I get to know Om better ... at
          ;;     which point, the elements will receive broadcast signals or
          ;;     somesuch.
          (->> selected-date
               (str)
               (cljs-coerce/from-string)
               (cljs-format/unparse long-formatter)
               (aset date-elem1 "innerHTML"))
          (aset date-elem2 "value" selected-date)
          (draw/result-append-el
            (dom/text
              #js
              {:className (str "grid-day-label"
                               (when (.equals date selected-date)
                                 " grid-day-label-is-selected"))
               :x (+ left-x
                     (draw/const
                       [:grid-day-label :x-offset-px]))
               :y (+ y-offset
                     (draw/const
                       [:grid-day-label :height-px]))
               :onClick #(on-change-date date)}
              (date->grid-label
                (.ofInstant LocalDate left-instant)))
            draw-result))))
    (draw/result-add-to-y
      (+ (draw/const [:grid-day-label :height-px])
         (draw/const [:grid-time-label :height-px])))
    (iterate-day-grid
      (fn [{:keys [left-x right-x]} {:keys [y-offset] :as draw-result}]
        (->> draw-result
             (draw/result-append-el
               (dom/text
                 #js
                 {:className "grid-time-label"
                  :x (+ left-x
                        (draw/const [:grid-time-label :x-offset-px]))
                  :y y-offset}
                 (str min-time)))
             (draw/result-append-el
               (dom/text
                #js
                {:className "grid-time-label"
                 :x (- right-x
                       (draw/const [:grid-time-label :x-offset-px]))
                 :y y-offset
                 :textAnchor "end"}
                (str max-time))))))))

(defn noop
  [& more]
  nil)

(defn render-row
  [selected-entry instant-to-x text bars
   {:keys [right-label on-mouse-over-bar on-mouse-leave-bar on-click-bar]
    :or {on-mouse-over-bar noop on-mouse-leave-bar noop}
    :as opts}
   draw-result]
  (let [x-start (-
                 (instant-to-x (->> bars (map :from)
                                    (sort (fn [a b] (.compareTo a b)))
                                    first))
                 (draw/const [:project-label-offset-px]))

        introduce-gap-between-adjacent-bars
        (fn [result [first second & more :as bars-with-positions]]
          (cond
            (not first) result
            (or (not second)
                (not= (:x2 first)
                      (:x1 second))) (recur (conj result first)
                                            (rest bars-with-positions))
            :else (recur (conj result (update first :x2 dec))
                         (rest bars-with-positions))))

        drawable-bars (->> bars
                           (map #(-> {:x1 (instant-to-x (:from %))
                                      :x2 (instant-to-x (:till %))
                                      :bar %}))
                           (introduce-gap-between-adjacent-bars []))
        draw-bars
        (fn [draw-result]
          (reduce
            (fn [draw-result bar]
              (cond->> draw-result
                (= (get-in bar [:bar :entry-id]) selected-entry)
                (draw/result-append-el
                  (let [height (draw/const [:hour-entry :height-px])]
                    (dom/rect
                      #js
                      {:className "hour-entry-active"
                       :x (:x1 bar)
                       :width (- (:x2 bar) (:x1 bar))
                       :y (- (:y-offset draw-result) (/ height 2))
                       :height height
                       :style #js {:filter "url(#hour-entry-active-glow)"}})))

                :always
                (draw/result-append-el
                  (dom/line
                   #js
                   {:className (str "time-line hour-line "
                                    (when (-> bar :bar :billable?)
                                      "hour-line-is-billable"))
                    :x1 (:x1 bar)
                    :x2 (:x2 bar)
                    :y1 (:y-offset draw-result)
                    :y2 (:y-offset draw-result)

                    :onMouseOver
                    (fn [ev]
                      (let [rect (draw/get-abs-bounding-client-rect
                                   (.-target ev))]
                        (on-mouse-over-bar
                          {:entry-id (get-in bar [:bar :entry-id])
                           :pos rect})))

                    :onMouseOut #(on-mouse-leave-bar)

                    :onClick
                    (fn [ev]
                      (on-click-bar (get-in bar [:bar :entry-id])))}))))
            draw-result
            drawable-bars))

        draw-right-label
        (fn [draw-result]
          (if-not right-label
            draw-result
            (draw/result-append-el
              (dom/text
                #js {:className "project-label project-label-summary"
                     :x (+ (:x2 (last drawable-bars))
                           (draw/const [:project-label-offset-px]))
                     :y (+ (:y-offset draw-result)
                           (draw/const
                             [:project-label-vert-offset-px]))}
                right-label)
              draw-result)))]
    (->> draw-result
         (draw/result-append-el
           (dom/text
             #js {:className "project-label project-label-taskname"
                  :x x-start
                  :y (+ (:y-offset draw-result)
                        (draw/const [:project-label-vert-offset-px]))
                  :textAnchor "end"}
            text))
         (draw-bars)
         (draw-right-label)
         (draw/result-add-to-y (draw/const [:row-spacing-px])))))

(defn format-duration
  [duration]
  (let [hours (str (.toHours duration))
        minutes (str (mod (.toMinutes duration) 60))
        minutes (if (< (count minutes) 2) (str "0" minutes) minutes)]
    (str hours ":" minutes)))

(defn render-project-heading
  [label start-at-instant draw-result]
  (let [x (draw/const [:project-heading-x-px])]
    (->> draw-result
         (draw/result-append-el
           (dom/text
             #js {:className "project-heading"
                  :x x
                  :y (:y-offset draw-result)}
            label))
         (draw/result-add-to-y
           (draw/const [:heading-to-rows-px])))))

(defn render-project-tasks
  [render-row tasks draw-result]
  (reduce
    (fn [draw-result [task-id entries]]
      (render-row (-> entries first :task)
                  entries
                  {:right-label (format-duration
                                  (bars-sum-duration entries))}
                  draw-result))
    draw-result
    tasks))

(defn render-project
  [render-project-tasks project draw-result]
  (let [tasks (->> (:bars project)
                   (group-by :task-id)
                   (sort (fn [[_ [a & _]] [_ [b & more]]]
                           (.compareTo (:from a) (:from b)))))]
    (->> draw-result
         (render-project-heading
           (:label project)
           (get-in tasks [0 :items 0 :from]))
         (render-project-tasks tasks)
         (draw/result-add-to-y
           (draw/const [:project-spacing-px])))))

(defn render-projects
  [projects render-row draw-result]
  (let [render-project-tasks (partial render-project-tasks render-row)
        render-project (partial render-project render-project-tasks)
        draw-projects
        (fn [draw-result]
          (reduce #(render-project %2 %1) draw-result projects))]

    (->> draw-result
         (draw/result-add-to-y
           (draw/const [:projects :vertical-offset-px]))
         (draw-projects))))

(defn render-grid
  [iterate-day-grid selected-date height draw-result]
  (iterate-day-grid
    (fn [{:keys [left-x right-x left-instant]}
         draw-result]
      (cond->> draw-result
        (.equals (.ofInstant LocalDate left-instant)
                 selected-date)
        (draw/result-append-el
          (dom/rect
            #js {:className "selected-date-highlight"
                 :x left-x :width (- right-x left-x)
                 :y 0 :height height}))
        true
        (draw/result-append-el
          (dom/line
            #js {:className "grid-day-separator"
                 :x1 left-x :x2 left-x
                 :y1 0 :y2 height}))))
    draw-result))

(defn render-now-indicator
  [instant-to-x min-time max-time height draw-result]
  (let [now (.now Instant)
        time (.ofInstant LocalTime now)
        max-time-is-midnight? (.equals (.-MIDNIGHT LocalTime) max-time)]
    (if (or (neg? (.compareTo time min-time))
            (and (not max-time-is-midnight?)
                 (pos? (.compareTo time max-time))))
      draw-result
      (let [x (- (.floor js/Math (instant-to-x now)) 0.5)]
        (draw/result-append-el
          (dom/line
            #js {:className "now-indicator"
                 :x1 x :x2 x
                 :y1 0 :y2 height})
          draw-result)))))

(defn bars-filter-date
  [date bars]
  (filter
    #(let [bar-date (.ofInstant LocalDate (:from %))]
       (zero? (.compareTo bar-date date)))
    bars))

(defn bars-filter-billable
  [billable? bars]
  (filter
    #(= billable? (:billable? %))
    bars))

(defn render-bars
  [instant-to-x bars y-offset {:keys [bar-classes] :as opts} draw-result]
  (if-not (seq bars)
    draw-result
    (reduce
      (fn [draw-result bar]
        (let [x1 (instant-to-x (:from bar))
              x2 (instant-to-x (:till bar))
              classes (string/join " "(conj bar-classes "time-line"))
              el (dom/line
                   #js {:className classes
                        :x1 x1
                        :x2 x2
                        :y1 y-offset
                        :y2 y-offset})]
          (draw/result-append-el el draw-result)))
      draw-result
      bars)))

(defn render-day-summaries
  [render-bars iterate-day-grid projects {:keys [y-offset] :as draw-result}]
  (let [all-bars (mapcat :bars projects)
        total-duration (bars-sum-duration all-bars)
        bar-offset (+ y-offset
                      (draw/const [:day-summary :bar :y-offset-px]))
        text-offset (+ bar-offset
                       (draw/const [:day-summary :label :y-offset-px]))
        min-hours (.ofHours Duration 8)]
    (iterate-day-grid
      (fn [{:keys [right-x left-instant]} draw-result]
        (let [date (.ofInstant LocalDate left-instant)
              day-bars (bars-filter-date date all-bars)
              billable (bars-merge (bars-filter-billable true day-bars))
              non-billable (bars-merge
                             (bars-filter-billable false day-bars))
              day-duration (bars-sum-duration day-bars)
              day-complete-class
              (if (>= (.compareTo day-duration min-hours) 0)
                "day-complete-complete"
                "day-complete-not-complete")]
          (if-not (seq day-bars)
            draw-result
            (->> draw-result
                 (render-bars
                   billable (+ bar-offset 0.5)
                   {:bar-classes ["day-summary" "billable"
                                  day-complete-class]})
                 (render-bars
                   non-billable (- bar-offset 0.5)
                   {:bar-classes ["day-summary" "non-billable"
                                  day-complete-class]})
                 (draw/result-append-el
                   (dom/text
                     #js {:className "day-summary-label"
                          :x (- right-x
                                (draw/const
                                  [:day-summary :label :x-offset-px]))
                          :y text-offset
                          :textAnchor "end"}
                     (dom/tspan
                       #js {:className day-complete-class}
                       (format-duration day-duration))
                     (dom/tspan
                       nil
                       (let [day-billability
                             (/ (.toMinutes (bars-sum-duration billable))
                                (.toMinutes day-duration))]
                         (str " (" (.floor js/Math (* 100 day-billability))
                              "%)")))))
                 (draw/result-set-y-offset text-offset)))))
      draw-result)))

(defn get-render-fns
  [dispatch!
   selected-date-str
   projects
   selected-entry
   on-change-date]
  (let [selected-date (.parse LocalDate selected-date-str)
        min-date (find-first-monday selected-date)
        [min-time max-time] (calc-min-max-time projects)
        minutes-per-day (.until min-time max-time (.-MINUTES ChronoUnit))
        minutes-per-day (if (pos? minutes-per-day)
                          minutes-per-day
                          (+ minutes-per-day (.. Duration (ofDays 1) toMinutes)))
        pixels-per-minute (/ (draw/const [:pixels-per-day]) minutes-per-day)

        instant-to-x (partial instant-to-x min-time min-date minutes-per-day
                              pixels-per-minute)
        iterate-day-grid (partial iterate-day-grid min-time min-date instant-to-x)
        render-row (fn [text bars opts draw-result]
                     (let [opts' (merge
                                   {:on-mouse-over-bar #(dispatch! {:action :mouse-over-entry :entry %})
                                    :on-mouse-leave-bar #(dispatch! {:action :mouse-leave-entry})
                                    :on-click-bar #(dispatch! {:action :edit-entry :entry-id %})}
                                   opts)]
                       (render-row selected-entry instant-to-x text bars opts'
                                   draw-result)))

        render-bars (partial render-bars instant-to-x)]
    {:render-projects (partial render-projects projects render-row)
     :render-grid (partial render-grid iterate-day-grid selected-date)
     :render-grid-labels (partial render-grid-labels on-change-date selected-date
                                 min-time max-time iterate-day-grid)
     :render-now-indicator (partial render-now-indicator instant-to-x min-time
                                   max-time)
     :render-day-summaries (partial render-day-summaries render-bars
                                    iterate-day-grid projects)}))

(defn render-svg
  [dispatch!
   selected-date-str
   projects
   on-change-date
   {:keys [selected-entry] :as opts}]
  (let [{:keys [render-grid-labels
                render-projects
                render-grid
                render-now-indicator
                render-day-summaries]} (get-render-fns
                                         dispatch!
                                         selected-date-str
                                         projects
                                         selected-entry
                                         on-change-date)
        has-project-data? (seq projects)

        draw-result (render-grid-labels (draw/result-empty))
        draw-result (if has-project-data?
                      (->> draw-result
                           (render-projects)
                           (render-day-summaries)
                           (draw/result-add-to-y
                             (draw/const [:canvas
                                          :bottom-padding-px])))
                      (draw/no-data-msg draw-result))
        height (:y-offset draw-result)
        grid (->> (draw/result-empty)
                  (render-grid height)
                  (render-now-indicator height))]
    (apply dom/svg
      #js {:version "1.1"
           :baseProfile "full"
           :width "100%"
           :height height}
      (dom/defs
        #js {:dangerouslySetInnerHTML
             #js {:__html (draw/svg-glow
                            "hour-entry-active-glow"
                            (draw/const [:hour-entry :active :color])
                            :radius (draw/const
                                      [:hour-entry :active :radius] 1)
                            :std-dev (draw/const
                                      [:hour-entry :active :std-dev] 1))}})
      ;TODO assign keys
      (concat (:els grid) (:els draw-result)))))
      ;(map-indexed
        ;; TODO assign proper keys
        ;(fn [idx [el attrs-or-child & more]]
          ;(let [attrs (when (map? attrs-or-child) attrs-or-child)
                ;children (concat (when-not attrs [attrs]) more)]
            ;[el (assoc attrs :key idx) children]))
        ;(do (log "grid" (:els grid))
            ;(log "draw-result" (:els draw-result))
            ;(concat (:els grid) (:els draw-result)))))))

