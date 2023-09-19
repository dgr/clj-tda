# clj-tda

*clj-tda* is a wrapper around TD Ameritrade's HTTP API. The wrapper
tries to make it easier to login and manage authentication tokens
while providing a thin, Clojure-friendly wrapper around each of the
various API endpoints.

The TD Ameritrade API can be found here: <https://developer.tdameritrade.com>

## Dependency Info

Leiningen project.clj:

```clojure
{:dependencies {[com.github.dgr/clj-tda "1.0.0"]}}
```

Clojure CLI tools deps.edn:
```clojure
{:deps {com.github.dgr/clj-tda {:mvn/version "1.0.0"}}}
```

## Getting Started

First, pull in the required packages:

```clojure
(require
   '[camel-snake-kebab.core :as csk]
   '[clj-tda.api :as tda]
   '[clj-tda.orders :as orders]
   '[clj-tda.token-manager :as tm]
   '[clojure.java.io :as io]
   '[clojure.string :as str]
   '[etaoin.api :as etaoin]
   '[jsonista.core :as json])
```

You'll need Etaoin in order to allow clj-tda to log you into your TDA
account via the web.

You don't have to use jsonista. Clj-tda works with Cheshire just as
well, but clj-tda already has a depdency on jsonista, and jsonista is
faster than Cheshire.

Next, we need to get set up to login to the TDA API.

```clojure
(def syspath (-> (System/getenv "PATH")
                 (str/split #":")))

(defn find-geckodriver
  []
  (first (for [d syspath
               :let [file (io/file d "geckodriver")]
               :when (and (.exists file)
                          (.canExecute file))]
           file)))

(defn driver-factory
  []
  (etaoin/firefox {:path-driver (find-geckodriver)}))

(def account-id "123456789") ; your account number at TD Ameritrade
(def api-key "01234567890ABCDEFGHIJ0123456789AB") ; your API key from the TDA API portal
(def redirect-uri "https://localhost") ; your redirect URI is from the TDA API portal
(def initial-token-map {:api-key api-key
                        :redirect-uri redirect-uri
                        :driver-factory #'driver-factory})
```

Now, initialize the token manager which will display a web browser
using Etaoin and allow the user to log in. After logging in, the token
manager will have a set of tokens that it will periodically refresh
behind the scenes.

```clojure
(tm/initialize initial-token-map)

(tm/access-token)
==>
... 1275 nearly random characters ...
```

The token manager actively refreshes the access token proactively, in
the background, to ensure that a valid access token is always
available. This ensures minimum latency when make an API call. Thus,
you should _always_ call `(tm/access-token)` to retrieve the current
token rather than storing the token in a variable and using it across
multiple calls.

Importantly, the token manager is optional functionality. Each of the
TDA API calls includes an `access-token` parameter and you can develop
your own access token management scheme if you prefer.

Regardless of whether you use the token manager or your own scheme,
once you have a valid token, you can start to make API calls.

Note that the API functions return a clj-http response map, with full
headers, HTTP status code, etc. Typically, you'll want to pull out the
`:body` parameter, but everything is there if you need it. In
particular, the `place-order` and `replace-order` APIs return a URL
that can be used to access the new order in the `Location:` header.

Since many of the calls return JSON, we'll define a
convenience function to help decode the responses.

```clojure
(def tda-object-mapper
  (json/object-mapper {:encode-key-fn csk/->camelCaseString
                       :decode-key-fn csk/->kebab-case-keyword}))

(defn decode-json-body
  [resp]
  (-> resp
      :body
      (json/read-value tda-object-mapper)))

(-> (tda/get-accounts (tm/access-token))
    decode-json-body)
==>
... a large Clojure map with account information ...

(-> (tda/get-quote (tm/access-token) "ibm") decode-json-body)
==>
{:ibm
 {:net-percent-change-in-double 1.1097,
  :description
  "International Business Machines Corporation Common Stock",
  :regular-market-net-change 1.43,
  :div-date "2023-08-09 00:00:00.000",
  :pe-ratio 71.1823,
  :regular-market-last-price 146.52,
  :asset-type "EQUITY",
  :net-change 1.61,
  :low-price 144.66,
  :realtime-entitled true,
  :bid-price 146.3,
  :delayed false,
  :regular-market-percent-change-in-double 0.9856,
  :52-wk-high 153.21,
  :high-price 146.72,
  :bid-id "P",
  :mark 146.52,
  :symbol "IBM",
  :regular-market-last-size 5080,
  :exchange-name "NYSE",
  :52-wk-low 115.545,
  :digits 2,
  :open-price 145.0,
  :volatility 0.0104,
  :security-status "Normal",
  :shortable true,
  :last-price 146.7,
  :ask-price 146.7,
  :bid-tick " ",
  :bid-size 500,
  :ask-size 500,
  :n-av 0.0,
  :mark-percent-change-in-double 0.9856,
  :asset-main-type "EQUITY",
  :regular-market-trade-time-in-long 1695154200002,
  :close-price 145.09,
  :total-volume 3945393,
  :div-amount 6.62,
  :cusip "459200101",
  :asset-sub-type "",
  :ask-id "P",
  :trade-time-in-long 1695160293760,
  :mark-change-in-double 1.43,
  :exchange "n",
  :quote-time-in-long 1695156139900,
  :last-size 0,
  :last-id "D",
  :div-yield 4.56,
  :marginable true}}
```

You can create orders suitable for `place-order` and `replace-order` using functions in the `clj-tda.orders` namespace.

```clojure
(orders/equity-market-order :buy 100 "ibm")
==>
{:session "NORMAL",
 :duration "DAY",
 :order-strategy-type "SINGLE",
 :order-leg-collection
 [{:instruction "BUY",
   :quantity 100,
   :instrument {:symbol "IBM", :asset-type "EQUITY"}}],
 :order-type "MARKET"}
 

(orders/oco-order (orders/equity-limit-order :sell 100 "ibm" 200.00)
                        (orders/equity-stop-market-order :sell 100 "ibm" 100.00))
==>
{:order-strategy-type "OCO",
 :child-order-strategies
 [{:session "NORMAL",
   :duration "DAY",
   :order-strategy-type "SINGLE",
   :order-leg-collection
   [{:instruction "SELL",
     :quantity 100,
     :instrument {:symbol "IBM", :asset-type "EQUITY"}}],
   :order-type "LIMIT",
   :price "200.00"}
  {:session "NORMAL",
   :duration "DAY",
   :order-strategy-type "SINGLE",
   :order-leg-collection
   [{:instruction "SELL",
     :quantity 100,
     :instrument {:symbol "IBM", :asset-type "EQUITY"}}],
   :stop-price "100.00",
   :order-type "STOP"}]}
```

## Documentation

More coming. Right now, most functions have pretty thorough doc strings.


## Limitations

The full TD Ameritrade API has not yet been implemented. I've mostly
focused on equitites right now and implemented those APIs that I need
myself. If you want to implement others, please submit a PR.

## License

Copyright © 2022-2023 David Roberts

Distributed under the MIT License. See LICENSE for more information.
