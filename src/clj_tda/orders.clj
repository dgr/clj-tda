(ns clj-tda.orders
  "Functions to more easily create various complex orders."
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.string :as str]))

(defn kw->json
  [kw]
  (csk/->SCREAMING_SNAKE_CASE_STRING kw))

(def instructions #{:buy :sell :buy-to-cover :sell-short :buy-to-open :buy-to-close
                    :sell-to-open :sell-to-close :exchange})
(def durations #{:day :good-till-cancel :fill-or-kill})
(def sessions #{:normal :am :pm :seamless})

(defn equity-base-order
  "Creates a basic equity order with most fields filled in. This is the
  root construction function for equity orders. You probably don't
  want to use this. Instead, see `equity-market-order`,
  `equity-limit-order`, `equity-stop-order`, and
  `equity-stop-limit-order`."
  [instruction quantity symbol duration session]
  (assert (instructions instruction))
  (assert (integer? quantity))
  (assert (string? symbol))
  (assert (durations duration))
  (assert (sessions session))
  {:session (kw->json session)
   :duration (kw->json duration)
   :order-strategy-type "SINGLE"
   :order-leg-collection [{:instruction (kw->json instruction)
                           :quantity quantity
                           :instrument {:symbol (str/upper-case symbol)
                                        :asset-type "EQUITY"}}]})

(defn equity-market-order
  "Creates a map that corresponds to a TDA equity market order. Note
  that this must be convert to JSON before communicating it to the TDA
  API. The order is for `symbol`. Note that market orders are only
  applicable during the normal session."
  ([instruction quantity symbol]
   (equity-market-order instruction quantity symbol :day))
  ([instruction quantity symbol duration]
   (-> (equity-base-order instruction quantity symbol duration :normal)
       (assoc :order-type "MARKET"))))

(defn equity-limit-order
  "Creates a limit order."
  ([instruction quantity symbol price]
   (equity-limit-order instruction quantity symbol price :day :normal))
  ([instruction quantity symbol price duration session]
   (assert (number? price))
   (let [price (double price)]
     (-> (equity-base-order instruction quantity symbol duration session)
         (assoc :order-type "LIMIT")
         (assoc :price (format "%.2f" (double price)))))))

(defn equity-stop-base-order
  [instruction quantity symbol stop-price duration session]
  (assert (number? stop-price))
  (let [stop-price (double stop-price)]
    (-> (equity-base-order instruction quantity symbol duration session)
        (assoc :stop-price (format "%.2f" (double stop-price))))))

(defn equity-stop-market-order
  ([instruction quantity symbol stop-price]
   (equity-stop-market-order instruction quantity symbol stop-price :day :normal))
  ([instruction quantity symbol stop-price duration session]
   (-> (equity-stop-base-order instruction quantity symbol stop-price duration session)
       (assoc :order-type "STOP"))))

(defn equity-stop-limit-order
  ([instruction quantity symbol stop-price price]
   (equity-stop-limit-order instruction quantity symbol stop-price price :day :normal))
  ([instruction quantity symbol stop-price price duration session]
   (assert (number? price))
   (let [price (double price)]
     (-> (equity-stop-base-order instruction quantity symbol stop-price duration session)
         (assoc :order-type "STOP_LIMIT")
         (assoc :price (format "%.2f" (double price)))))))

(defn first-triggers-rest-order
  [order1 & orders]
  (-> order1
      (assoc :order-strategy-type "TRIGGER")
      (assoc :child-order-strategies (vec orders))))

(defn oco-order
  [& orders]
  {:order-strategy-type "OCO"
   :child-order-strategies (vec orders)})
