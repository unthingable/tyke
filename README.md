Tyke the Perimeter Authenticator

# What it is

Tyke is a simple sidecar service that connects an HTTP proxy (like `nginx`) to authentication backends.
Nginx with Tyke together form a perimeter gateway that handles user and session management, 
so your app/service doesn't have to.

# What it does

A perimeter gateway is a boundary between external clients and internal services. It intercepts, validates, modifies
and forwards inbound requests from the outside. It is a natural choke point for Single Sign Out.

The client first authenticates
to Tyke (by providing credentials or a third party token) and is given a session token that allows successive requests
to pass through. Tyke enriches the inbound request with metadata about the user (role, group, etc.), internal service
can use it to make an authorization decision.

Tyke highlights:
* Completely decouples authentication from multiple services
* Simplifies service deployment and environment configuration, easy perimeter setup in prd/demo/testing environments
* Centralized session handling creates a choke point for Single Sign Out
* Session management API
* Configurable user/session storage backends
* Ability to combine multiple auth backends
* Performant and familiar: `nginx` does the heavy lifting

Currently supported backends are:

* User: internal in-memory, Django MySQL, LDAP
* Session: internal in-memory, redis

# TL;DR

### Mechanism

_Tyke_ works in combination with an HTTP proxy (like nginx). When we say "Tyke proxy" we shall mean this tandem servise.

The HTTP proxy:

* validates an incoming by performing a subrequest to Tyke (`auth_request` in nginx)
* enriches the request with user metadata
* enriches the response with renewed authentication metadata


Tyke:

* maintains internal list of active valid sessions
* maintains the mapping between authN metadata and internal sessions
* can validate the session identified by authN metadata
* can selectively modify/invalidate active sessions (by a power user)

Auth metadata will be a cookie with a bearer token. The purpose of the token is only to uniquely identify
a session, it should not contain any other meaningful data (user, expiration, etc.). The token may or may not contain the session ID.
An encrypted session ID is a valid choice, other options are available (e.g. opaque random token).

### Protocol

Authentication-related communication is split into:

* external _client_ <-> Tyke proxy
* Tyke proxy <-> internal _service_

Authentication information (credentials from outside, metadata from inside) is not passed through.

#### client <-> proxy

A request is authenticated when the client presents a valid bearer **token**. To obtain the token
the client must first present valid credentials to the "login" Tyke endpoint and receive the new token in the response.

From that point the inbound requests will be accepted until the token is invalidated by either:

* explicit logging out request by the user
* expiration
* a logout request by an admin user

#### proxy <-> Tyke

The proxy forwards the bodiless request to Tyke via a subrequest. Tyke responds with validation information:

* success + new auth token + user metadata -> proxy adds user metadata to inbound request
* failure -> proxy clears the auth token cookie and reject the inbound request

#### proxy <-> service

In addition to user metadata, proxy adds a unique request ID header (for tracking) and passes the request through.

### Backend

Tyke can use a number of configurable backends, for example:

* AD/LDAP
* Django
* sqlite/H2/etc. (for local dev / test deployments)

User and session storage backends can be the same or configured independently.

Benefits of using AD/LDAP in production:

* LDAP is fast
* MFA solutions (Okta, Auth0, etc.) can use the same AD/LDAP as their backend

## Acceptance criteria and assumptions

Tyke must be fast, lightweight and resilient.

### API forwarding

When an inbound API request arrives inside the perimeter, it:

1. Is guaranteed to come from an authenticated user
1. Contains enough information about the current user so that service can decide authorization

### API response

After logging in, the client will be provided with and later identified by an opaque and encrypted reference (bearer) token.

### Session handling

1. A new session will be created for every login
1. Sessions will be removed upon logout and periodically after a period of inactivity
1. Session is validated for every inbound request
1. Functionality to terminate all user's sessions at once (SingleSignOut), by a user with sufficient permissions (staff)


# PoC implementation and next steps

Tyke is:

* Scala
* http4s (cats, fs2, pureconfig)

HTTP proxy is:

* nginx or Envoy

User API:

* `login` (direct forward)
* `logout` (direct forward)
* `validate`, doubles as token refresh (subrequest)
* `session`: information about the session/current user (direct forward)

Session is validated and refreshed on every request.

Power user API:

* `escalate`
* `logout_user`

# Taking it for a spin yourself

You can run the whole Tyke/nginx/redis stack via docker-compose, or you can run Tyke separately.

Docker-compose will use `proxy/application.docker.conf`, running locally will use `proxy/application.run.conf`.
Make one from the provided examples.

1. Create `proxy/application.*.conf` (use examples and `src/main/resources/reference.conf` for reference)
1. Start Tyke: `./gradlew run` (fresh checkout may take a while)
1. Start proxy: `cd proxy; docker-compose up nginx` (Mac only)

Now you're ready to hit it with Postman.

What's happening:

* Tyke is running on port 9000
* Proxy is running on port 8081
* A fake internal service is also running on Tyke (`/api/echo`)

Tyke endpoints and how the proxy exposes some of them:

* POST `/login` <- `/auth/login`
* GET `/logout` <- `/auth/logout`
* GET `/session` <- `/auth/session`
* GET `/validate`
* POST `/escalate` ("staff" users only)
* POST `/logout_user` ("staff" users only)
* GET `/api/echo` <- `/api/echo`

## What can you do (right now)

You can find the list of predefined users in `reference.conf`.

* Login
    * POST to `localhost:8081/auth/login` a JSON payload like `{"username": "user1", "password": "pass1"}`.
    * Observe: an `tyke_auth` cookie is set.
* Call API
    * GET `localhost:8081/api/echo`
    * Observe:
        * The call succeeds — because you have a valid token
        * The call returns the headers the downstream service has received
        * One of those headers is `X-UserInfo` with your user info — this is sourced from Tyke and passed downstream
* Obtain your session info
    * GET `localhost:8081/session`
* Logout
    * GET `localhost:8081/auth/logout`
* Call the API or try your session info again
    * You can't.

As a user with "staff" role you can do more. The following POST commands take JSON in the form of `{username: "<username>"}`

* Forcibly log out a (nonstaff) user:
    * POST to `localhost:9000/logout_user`
    * Observe: if that user was logged in, all of his sessions are no longer valid
* Become any (nonstaff) user:
    * POST to `localhost:9000/escalate`
    * Observe:
        * You get a new session token, you are now that user
        * Your previous token is still valid (but you loose the cookie in the client where you escalated)


## Misc

### MySQL notes

If you get `Failed to create/setup connection: Table 'performance_schema.session_variables' doesn't exist`

    mysql> set @@global.show_compatibility_56=ON;
    Query OK, 0 rows affected (0.01 sec)

### IntelliJ stack overflows

If you get those and you're using Scala Compile Server, 
add `-Xss4m` JVM argument.
