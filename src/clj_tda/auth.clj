(ns clj-tda.auth
  (:require
   [clj-http.client :as client]
   [cljc.java-time.zoned-date-time :as zdt]
   [clojure.string :as str]
   [etaoin.api :as etaoin]
   [jsonista.core :as json]
   [org.bovinegenius.exploding-fish :as uri]))


;;; Common utility functions ----------------------------------------------

(defn- post-access-token
  "Generates access and refresh tokens based on the `type` of request
  being made (`:auth-code`, `:refresh-refresh`, or
  `:refresh-access`. User code will typically not call this function
  directly, but rather `login`, `refresh-refresh-token`, or
  `refresh-access-token`."
  [type & {:keys [oauth-consumer-key redirect-uri auth-code refresh-token]}]
  (let [params (case type
                 :auth-code (do (assert auth-code)
                                (assert oauth-consumer-key)
                                (assert redirect-uri)
                                {"grant_type" "authorization_code"
                                 "refresh_token" ""
                                 "access_type" "offline"
                                 "code" auth-code
                                 "client_id" oauth-consumer-key
                                 "redirect_uri" redirect-uri})
                 :refresh-refresh (do (assert refresh-token)
                                      (assert oauth-consumer-key)
                                      {"grant_type" "refresh_token"
                                       "refresh_token" refresh-token
                                       "access_type" "offline"
                                       "code" ""
                                       "client_id" oauth-consumer-key
                                       "redirect_uri" ""})
                 :refresh-access (do (assert refresh-token)
                                     (assert oauth-consumer-key)
                                     {"grant_type" "refresh_token"
                                      "refresh_token" refresh-token
                                      "access_type" ""
                                      "code" ""
                                      "client_id" oauth-consumer-key
                                      "redirect_uri" ""}))]
    (client/post "https://api.tdameritrade.com/v1/oauth2/token"
                 {:form-params params})))


(defn- wait-for-redirect
  [driver redirect-uri]
  (while (not (-> (etaoin/get-url driver)
                  (str/starts-with? redirect-uri)))
    (etaoin/wait driver 1)))

(defn- make-oauth-consumer-key
  [api-key]
  (str api-key "@AMER.OAUTHAP"))


(defn- make-auth-uri
  [redirect-uri oauth-consumer-key]
  (-> (uri/uri "https://auth.tdameritrade.com/auth")
      (uri/param "response_type" "code")
      (uri/param "redirect_uri" redirect-uri)
      (uri/param "client_id" oauth-consumer-key)
      str))

;;; Begin public API ------------------------------------------------------

(defn generate-access-token
  "Given a map containing the `:oauth-consumer-key`, `:redirect-uri`,
  and `:auth-code`, returns an updated map with access and refresh
  token information. It's unlikely you want to call this function
  directly. You probably want `login` instead, unless you have
  generated the `:auth-code` in some other fashion."
  [{:keys [oauth-consumer-key redirect-uri auth-code] :as tokens}]
  (let [now (zdt/now)
        http-response (post-access-token :auth-code
                                         :oauth-consumer-key oauth-consumer-key
                                         :redirect-uri redirect-uri
                                         :auth-code auth-code)
        response (-> http-response
                     :body
                     json/read-value)
        access-token (get response "access_token")
        access-token-expiry (->> (get response "expires_in")
                                 (zdt/plus-seconds now))
        access-token-refresh-time (->> (get response "expires_in")
                                       (* 3/4)
                                       long
                                       (zdt/plus-seconds now))
        refresh-token (get response "refresh_token")
        refresh-token-expiry (->> (get response "refresh_token_expires_in")
                                  (zdt/plus-seconds now))
        refresh-token-refresh-time (->> (get response "refresh_token_expires_in")
                                        (* 3/4)
                                        long
                                        (zdt/plus-seconds now))]
    (-> tokens
        (assoc :oauth-consumer-key oauth-consumer-key
               :redirect-uri redirect-uri
               :access-token access-token
               :access-token-expiry access-token-expiry
               :access-token-refresh-time access-token-refresh-time
               :refresh-token refresh-token
               :refresh-token-expiry refresh-token-expiry
               :refresh-token-refresh-time refresh-token-refresh-time)
        ;; auth code is single-use only, so remove it
        (dissoc :auth-code))))


(defn login
  "Logs the user in with a browser interface and returns tokens that
  must be supplied to other API calls. The `driver` parameter
  specifies an Etaoin driver that must be created beforehand, allowing
  callers the choice of web driver (e.g., Geckodriver, Chrome, etc.)."
  [{:keys [driver-factory api-key redirect-uri]}]
  (let [oauth-consumer-key (make-oauth-consumer-key api-key)
        auth-uri (make-auth-uri redirect-uri oauth-consumer-key)
        tokens {:driver-factory driver-factory
                :api-key api-key
                :redirect-uri redirect-uri
                :oauth-consumer-key oauth-consumer-key
                :auth-uri auth-uri}
        driver (driver-factory)]
    (try
      (etaoin/go driver auth-uri)
      ;; Note: the following works, for a fully-automated login with a
      ;; headless browser, but sometimes there is 2-factor auth, so
      ;; skip it:
      ;; (etaoin/wait-visible driver :username0)
      ;; (etaoin/wait-visible driver :password1)
      ;; (etaoin/fill driver :username0 username)
      ;; (etaoin/fill driver :password1 password)
      ;; (etaoin/fill driver :password1 etaoink/enter)
      ;; (etaoin/wait-visible driver :decline)
      ;; (etaoin/wait 1)
      ;; (try
      ;;   (etaoin/click driver :accept)
      ;;   (catch clojure.lang.ExceptionInfo e))
      ;; (etaoin/wait 1)
      (wait-for-redirect driver redirect-uri)
      ;; Now that we have the redirect, even if the browser is showing
      ;; an error, grab the code and decode it.
      (let [auth-code (-> (etaoin/get-url driver)
                          uri/uri
                          (uri/params "code")
                          first)
            tokens (generate-access-token (assoc tokens :auth-code auth-code))]
        tokens)
      (finally (etaoin/quit driver)))))


(defn refresh-refresh-token
  "Takes a map of tokens and returns an updated map with the refresh
  token refreshed. Note that if the refresh token is completely
  expired, this function calls `login`, which presents the user with an
  interactive UI. Thus, this function may not execute quickly or
  behind the scenes."
  [{:keys [refresh-token refresh-token-expiry
           oauth-consumer-key] :as tokens}]
  (let [now (zdt/now)]
    (if (zdt/is-before now refresh-token-expiry)
      (let [now (zdt/now)
            http-response (post-access-token :refresh-refresh
                                             :refresh-token refresh-token
                                             :oauth-consumer-key oauth-consumer-key) 
            response (-> http-response
                         :body
                         json/read-value)
            ;; When you refresh the refresh token, it gives you a new access token, too.
            access-token (get response "access_token")
            access-token-expiry (->> (get response "expires_in")
                                     (zdt/plus-seconds now))
            access-token-refresh-time (->> (get response "expires_in")
                                           (* 3/4)
                                           long
                                           (zdt/plus-seconds now))
            refresh-token (get response "refresh_token")
            refresh-token-expiry (->> (get response "refresh_token_expires_in")
                                      (zdt/plus-seconds now))
            refresh-token-refresh-time (->> (get response "refresh_token_expires_in")
                                            (* 1/2)
                                            long
                                            (zdt/plus-seconds now))]
        (assoc tokens
               :access-token access-token
               :access-token-expiry access-token-expiry
               :access-token-refresh-time access-token-refresh-time
               :refresh-token refresh-token
               :refresh-token-expiry refresh-token-expiry
               :refresh-token-refresh-time refresh-token-refresh-time))
      (login tokens))))


(defn refresh-access-token
  "Takes a map of tokens and returns an updated map with the access
  token refreshed. Note that if the refresh token is already expired,
  this calls `refresh-refresh-token`, which might call `login` and
  present an interactive UI to the user. Thus, don't count on it to be
  fast."
  [{:keys [refresh-token refresh-token-refresh-time
           oauth-consumer-key] :as tokens}]
  (let [now (zdt/now)]
    (if (zdt/is-before now refresh-token-refresh-time)
      (let [http-response (post-access-token :refresh-access
                                             :refresh-token refresh-token
                                             :oauth-consumer-key oauth-consumer-key) 
            response (-> http-response
                         :body
                         json/read-value)
            access-token (get response "access_token")
            access-token-expiry (->> (get response "expires_in")
                                     (zdt/plus-seconds now))
            access-token-refresh-time (->> (get response "expires_in")
                                           (* 3/4)
                                           long
                                           (zdt/plus-seconds now))]
        (assoc tokens
               :access-token access-token
               :access-token-expiry access-token-expiry
               :access-token-refresh-time access-token-refresh-time))
      (refresh-refresh-token tokens))))
