(ns clj-tda.api
  "Functions to access the TD Ameritrade API."
  (:require
   [camel-snake-kebab.core :as csk]
   [clj-http.client :as client]
   [cljc.java-time.format.date-time-formatter :as dtf]
   ;; [cljc.java-time.instant :as instant]
   [cljc.java-time.local-date :as ld]
   ;; [cljc.java-time.local-date-time :as ldt]
   ;; [cljc.java-time.zone-id :as zone-id]
   ;; [cljc.java-time.zoned-date-time :as zdt]
   ;; [clojure.java.io :as io]
   ;; [clojure.pprint :as pprint]
   [clojure.string :as str]
   ;; [etaoin.api :as etaoin]
   ;; [etaoin.keys :as etaoink]
   ;; [failjure.core :as f]
   [jsonista.core :as json]
   ;; [org.bovinegenius.exploding-fish :as uri]
   ;; [promesa.core :as p]
   ))

;;; API levels
;;; 1. Form the URL and convert paramters
;;; 2. Perform the request and handle transitory errors
;;; 3. If no error, decode the response to Clojure data
;;; 4. If error, pass along appropriately

;; (defn- millis->local-date
;;   "Converts from milliseconds past the epoch in UTC to a
;;   java.time.LocalDate. Assumes that java.time.ZoneId/systemDefault
;;   returns the correct system default time zone."
;;   [millis]
;;   (-> millis
;;       (instant/of-epoch-milli)
;;       (ldt/of-instant (zone-id/system-default))
;;       (ldt/to-local-date)))

(def ^:private tda-object-mapper
  (json/object-mapper {:encode-key-fn csk/->camelCaseString
                       :decode-key-fn csk/->kebab-case-keyword}))


(defn- bearer-access-token
  [access-token]
  (str "Bearer " access-token))

;; (defn decode-json-body
;;   [resp]
;;   (-> resp
;;       :body
;;       (json/read-value tda-object-mapper)))

(defn ->comma-sep-str
  [items]
  (str/join "," (map name items)))

(defn kw->constant
  [sym]
  (csk/->SCREAMING_SNAKE_CASE_STRING sym))

(defn ->iso-date
  "Returns the local-date as a string in ISO_DATE format."
  [local-date]
  (ld/format local-date dtf/iso-date))

(defn common-headers
  [access-token]
  {"Authorization" (bearer-access-token access-token)
   "Accept" "application/json"})

;;; Accounts and Trading
;;; See: https://developer.tdameritrade.com/account-access/apis

(defn cancel-order
  "Cancels the order specified by `order-id`. Returns status 200 if
  successful and status 400 if order ID invalid. Note that API page
  says it should return 404 if the order is not found. This doesn't
  seem to happen."
  [access-token account-id order-id]
  (client/delete (str "https://api.tdameritrade.com/v1/accounts/"
                      account-id "/orders/" order-id)
                 {:headers (common-headers access-token)}))


(defn get-order
  "Gets information about the order specified by `order-id`. Returns
  status 200 if successful, in which case the body contains order info
  in JSON."
  [access-token account-id order-id]
  (client/get (str "https://api.tdameritrade.com/v1/accounts/"
                   account-id "/orders/" order-id)
              {:headers (common-headers access-token)}))


(defn get-orders-by-path
  "Gets order status for multiple orders for the specified `account-id`.

  Status may be one of:
  :awaiting-parent-order
  :awaiting-condition
  :awaiting-manual-review
  :accepted
  :awaiting-ur-out
  :pending-activation
  :queued
  :working
  :rejected
  :pending-cancel
  :canceled
  :pending-replace
  :replaced
  :filled
  :expired

  Returns JSON in :body."
  [access-token account-id {:keys [max-results from-entered-time to-entered-time status]}]
  (client/get (str "https://api.tdameritrade.com/v1/accounts/" account-id "/orders")
              {:headers      (common-headers access-token)
               :query-params (cond-> {"maxResults" max-results}  ; TODO: required?
                               from-entered-time (assoc "fromEnteredTime" (->iso-date from-entered-time))
                               to-entered-time   (assoc "toEnteredTime" (->iso-date to-entered-time))
                               status            (assoc "status" (kw->constant status)))}))

;;; TODO: (defn get-orders-by-query [...] ...)

(defn place-order
  "Places the `order` for the specified `account-id`. Returns status 201
  if order accepted. Returns status 400 if the order is rejected. Note
  that the Location header specifies a URL that can be used to
  retrieve current information about the order (essentially the
  equivalent of `get-order`."
  [access-token account-id order]
  (client/post (str "https://api.tdameritrade.com/v1/accounts/" account-id "/orders")
               {:headers (common-headers access-token)
                :content-type "application/json"
                :body (json/write-value-as-string order tda-object-mapper)}))


(defn replace-order
  "Replaces the order specified by `order-id` with the new
  `order`. Returns status 201 if order replaced."
  [access-token account-id order-id order]
  (client/put (str "https://api.tdameritrade.com/v1/accounts/"
                   account-id "/orders/" order-id)
              {:headers (common-headers access-token)
               :content-type "application/json"
               :body (json/write-value-as-string order tda-object-mapper)}))


;;; TODO: Saved Orders


(defn get-account
  "Returns account balances, positions, and orders for the specified
  `account-id`. If present, `fields` must be a sequence of
  `:positions` and/or `:orders`" 
  ([access-token account-id]
   (get-account access-token account-id []))
  ([access-token account-id fields]
   (client/get (str "https://api.tdameritrade.com/v1/accounts/" account-id)
               {:headers (common-headers access-token)
                :query-params {"fields" (->comma-sep-str fields)}})))


(defn get-accounts
  "Returns account balances, positions, and orders for all linked accounts.
  If present, `fields` must be a sequence of `:positions` and/or
  `:orders`."
  ([access-token]
   (get-accounts access-token []))
  ([access-token fields]
   (client/get "https://api.tdameritrade.com/v1/accounts"
               {:headers (common-headers access-token)
                :query-params {"fields" (->comma-sep-str fields)}})))


;;; Authentication
;;; See clj-tda.auth and clj-tda.token-manager

;;; TODO: Instruments
;;; See: https://developer.tdameritrade.com/instruments/apis

;;; Market Hours
;;; See: https://developer.tdameritrade.com/market-hours/apis

;;; TODO: get-hours-multiple-markets

(defn get-hours-single-market
  "Retrieves market hours information for the specified `market` on the
  given `date`. Note that `date` must either be today or sometime in
  the future, based on the current time in New York. Thus, you are in
  a timezone west of New York and you make this query after midnight
  in New York and set `date` to your local date, it will fail since
  that date will be yesterday in New York.

  Valid specifiers for `market` include:
  :equity
  :option
  :future
  :bond
  :forex

  `date` is a java.time.LocalDate or a date string in uuuu-MM-DD format."
  [access-token market date]
  (client/get (str "https://api.tdameritrade.com/v1/marketdata/"
                   (kw->constant market) "/hours")
              {:headers (common-headers access-token)
               :query-params (if date
                               {"date" (->iso-date date)}
                               {})}))


;;; TODO: Movers
;;; See: https://developer.tdameritrade.com/movers/apis


;;; Option Chains
;;; See: https://developer.tdameritrade.com/option-chains/apis

(defn get-option-chain
  "`symbol` : the underlying symbol as a string, keyword, or Clojure symbol
  
  `:contract-type`: Type of contracts to return in the chain. Can be
  `:call`, `:put`, or `:all`. Default is `:all`.
  
  `:strike-count`: The number of strikes to return above and below the
  at-the-money price.
  
  `:include-quotes`: Include quotes for options in the option
  chain. Can be truthy or falsey. Default is `false`.
  
  `:strategy`: Passing a value returns a Strategy Chain. Possible
  values are `:single`, `:analytical` (allows use of the volatility,
  underlyingPrice, interestRate, and daysToExpiration params to
  calculate theoretical values), `:covered`, `:vertical`, `:calendar`,
  `:strangle`, `:straddle`, `:butterfly`, `:condor`, `:diagonal`, `:collar`, or
  `:roll`. Default is `:single`.

  `:interval`: Strike interval for spread strategy chains (see
  strategy param).

  `:strike`: Provide a strike price to return options only at that
  strike price.

  `:range`: Returns options for the given range. Possible values are:
      `:itm`: In-the-money
      `:ntm`: Near-the-money
      `:otm`: Out-of-the-money
      `:sak`: Strikes Above Market
      `:sbk`: Strikes Below Market
      `:snk`: Strikes Near Market
      `:all`: All Strikes (default)

  `:from-date`: Only return expirations after this date. For
  strategies, expiration refers to the nearest term expiration in the
  strategy. Must be a `java.time.LocalDate` or string in uuuu-MM-DD
  format.

  `:to-date`: Only return expirations before this date. For
  strategies, expiration refers to the nearest term expiration in the
  strategy. Must be a `java.time.LocalDate` or string in uuuu-MM-DD
  format.

  `:exp-month`: Return only options expiring in the specified
  month. Month is given in standard three character format.
  Example: `:jan`, `:feb`, etc. Default is `:all`.

  `:option-type`: Type of contracts to return. Possible values are:
      `:s`  : Standard contracts
      `:ns` : Non-standard contracts
      `:all`: All contracts (default)"
  [access-token symbol & {:keys [contract-type strike-count include-quotes strategy interval
                                 strike range from-date to-date exp-month option-type]
                          :or   {include-quotes ::not-present}}]
  (client/get "https://api.tdameritrade.com/v1/marketdata/chains"
              {:headers      (common-headers access-token)
               :query-params (cond-> {"symbol" (str/upper-case (name symbol))}
                               contract-type        (assoc "contractType" (kw->constant contract-type))
                               strike-count         (assoc "strikeCount" strike-count)
                               (not= include-quotes
                                     ::not-present) (assoc "includeQuotes" (if include-quotes "TRUE" "FALSE"))
                               strategy             (assoc "strategy" (kw->constant strategy))
                               interval             (assoc "interval" interval)
                               strike               (assoc "strike" strike)
                               range                (assoc "range" (kw->constant range))
                               from-date            (assoc "fromDate" (->iso-date from-date))
                               to-date              (assoc "toDate" (->iso-date to-date))
                               exp-month            (assoc "expMonth" (kw->constant exp-month))
                               option-type          (assoc "optionType" (kw->constant option-type)))}))


;;; TODO: Price History
;;; See: https://developer.tdameritrade.com/price-history/apis


;;; Quotes
;;; See: https://developer.tdameritrade.com/quotes/apis

(defn get-quote
  "Get's a quote for the specified `symbol`."
  [access-token symbol]
  (client/get (str "https://api.tdameritrade.com/v1/marketdata/"
                   (kw->constant symbol) "/quotes")
              {:headers {"Authorization" (bearer-access-token access-token)}}))

(defn get-quotes
  "Gets multiple quotes for the seq of `symbols`."
  [access-token symbols]
  (client/get "https://api.tdameritrade.com/v1/marketdata/quotes"
              {:headers {"Authorization" (bearer-access-token access-token)}
               :query-params {"symbol" (->comma-sep-str symbols)}}))


;;; TODO: Transaction History
;;; See: https://developer.tdameritrade.com/transaction-history/apis


;;; TODO: User Info and Preferences
;;; See: https://developer.tdameritrade.com/user-principal/apis


;;; TODO: Watch List
;;; See: https://developer.tdameritrade.com/watchlist/apis

