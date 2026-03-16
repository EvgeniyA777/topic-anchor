(ns topic-anchor.scoring)

(defn dot-product [left right]
  (reduce + (map * left right)))

(defn magnitude [values]
  (Math/sqrt (double (reduce + (map #(* % %) values)))))

(defn cosine-similarity [left right]
  (let [left-mag (magnitude left)
        right-mag (magnitude right)]
    (if (or (zero? left-mag) (zero? right-mag))
      0.0
      (/ (dot-product left right) (* left-mag right-mag)))))

(defn median [values]
  (let [sorted (vec (sort values))
        count* (count sorted)
        midpoint (quot count* 2)]
    (when (seq sorted)
      (if (odd? count*)
        (nth sorted midpoint)
        (/ (+ (nth sorted (dec midpoint))
              (nth sorted midpoint))
           2.0)))))

(defn median-absolute-deviation [values]
  (let [center (median values)]
    (when (some? center)
      (median (map #(Math/abs (double (- % center))) values)))))

(defn threshold-policy [scores]
  (when (>= (count scores) 6)
    (let [center (double (median scores))
          mad (double (median-absolute-deviation scores))
          mad-floor (max mad 0.02)]
      {:median-score center
       :mad mad
       :mad-floor mad-floor
       :outlier-threshold (- center (* 3.0 mad-floor))
       :suspect-threshold (- center (* 2.0 mad-floor))})))

(defn classify-score [score policy]
  (cond
    (< score (:outlier-threshold policy)) "OUTLIER"
    (< score (:suspect-threshold policy)) "SUSPECT"
    :else "OK"))

(defn review-index [scores]
  (->> (map-indexed vector scores)
       (sort-by (fn [[idx score]] [score idx]))
       ffirst))
