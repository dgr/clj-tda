(ns clj-tda.token-manager
  (:require
   [clj-tda.auth :as auth]
   [clojure.tools.logging :as log :refer [debug error fatal info log trace warn]]
   [cljc.java-time.zoned-date-time :as zdt]))


(def ^:private tokens
  "Stores the current set of tokens being managed by the token manager."
  (agent {}))


(defn- refresh-tokens
  "Designed to be sent to `tokens` agent periodically to check on the
  current refresh status of the refresh and access tokens. If a token
  needs to be refreshed, this function will call the suitable function
  in the `clj-tda.auth` namespace to perform the refresh."
  [a]
  (let [refresh-token-refresh-time (:refresh-token-refresh-time a)
        access-token-refresh-time (:access-token-refresh-time a)
        now (zdt/now)]
    (if (zdt/is-after now refresh-token-refresh-time)
      (do (debug "Refresh token needs refresh")
          (send tokens #'refresh-tokens)
          (auth/refresh-refresh-token a))
      (if (zdt/is-after now access-token-refresh-time)
        (do (debug "Refreshing access token")
            (auth/refresh-access-token a))
        (do (debug "No token refreshes")
            a)))))


(def ^:private refresh-thread
  "Private background thread that wakes up every 60 seconds and sends
  `refresh-tokens` to the token manager agent. This thread is started
  by `initialize` and runs forever."
  (Thread. (fn []
             (while true
               (debug "Token refresh thread sleeping 60 seconds")
               (Thread/sleep 60000)
               (send tokens #'refresh-tokens)))
           "clj-tda token manager refresh thread"))


(defn initialize
  "Initializes the token manager. The `token-map` must be an initial map
  that contains three keys: `:api-key` (TDA API key),
  `:redirect-uri` (the redirect URI provided in your TDA application
  console corresponding with the API key), and `:driver-factory` (a
  no-argument function that returns an Etaoin driver; see
  https://github.com/clj-commons/etaoin). This function calls
  clj-tda.auth/login which typically will create a web browser so the
  user can provide login credentials to the user's TDA account."
  [token-map]
  (assert (:api-key token-map))
  (assert (:driver-factory token-map))
  (assert (:redirect-uri token-map))
  (debug "Logging into TDA")
  ;; Note that we use send-off here because login may involve the user
  ;; interacting with a browser and might take several minutes
  (send-off tokens (fn [_agent]
                     (auth/login token-map)))
  (await tokens)
  ;; The refresh and access tokens aren't valid until the call to
  ;; auth/login returns, so don't start the refresh thread until the
  ;; previous send-off completes.
  (.start refresh-thread))


(defn access-token
  "Returns the current access token being managed by the token
  manager. Note that `initialize` must have been called and returned
  successfully before this function is called."
  []
  (:access-token @tokens))
