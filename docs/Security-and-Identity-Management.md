# Security and Identity Management

OpenCrawling does not store users or passwords. It trusts an OpenID Connect identity provider (Keycloak, Microsoft Entra ID, Okta, or any other) and checks the bearer token on every request:

- **The REST API and the MCP endpoints** validate the token's signature, expiry, issuer and audience (OAuth2 resource server).
- **The admin UI** signs users in with the authorization code flow with PKCE, as a public client without a secret, and sends their access token with every API call.
- **Management authorization** decides on the verified caller who may see, run and change which jobs, and who may use the admin-only pages. See [Configuration Guide, Management Authorization](Configuration-Guide#-management-authorization).
- **Document authorization** filters search results by the same caller. See [Document Authorization](Document-Authorization).

This guide sets up the identity provider. The full list of settings is in [Configuration Guide, Caller Identity](Configuration-Guide#-caller-identity).

---

## The settings in one place

```yaml
opencrawling:
  security:
    identity:
      mode: jwt                 # validate bearer tokens
      protect-api: true         # on /api/** too, not only the MCP endpoints
      admin-ui:
        client-id: opencrawling-admin-ui   # the UI's public client; blank: the UI does not sign in
        scope: openid profile
    management:
      enabled: true
      admin-groups: opencrawling-admin
      job-creator-groups: hr-team,finance-team

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/realms/opencrawling
          audiences: opencrawling-api
```

- **Audience:** always set `audiences`. Without it, any valid token from the issuer is accepted, including tokens issued for other applications.
- **Issuer as the browser sees it:** tokens carry the issuer URL the user signed in at. When the runtime reaches the identity provider at another address (inside a Docker network, for example), keep `issuer-uri` at the browser's URL and point `jwk-set-uri` at the internal one. The runtime then fetches the signing keys there and still checks the issuer. If the admin UI must sign in at yet another URL, set `admin-ui.issuer`.
- **Groups:** the caller's groups come from the claims in `opencrawling.security.identity.jwt.group-claims` (default `realm_access.roles,groups`). Group names in `admin-groups`, `job-creator-groups` and job `access` blocks must match those values.
- **Who the user is:** the first non-blank claim of `principal-claims` (default `preferred_username,sub`).

The admin UI asks the runtime for its sign-in settings (`GET /api/auth/config`, public), signs the user in, then reads `GET /api/auth/me` (the caller, `admin`, `canCreateJobs`) and `GET /api/jobs/permissions` (what the caller may do with each job). It hides pages and buttons the user may not use. The runtime enforces the same rules either way.

---

## Keycloak (reference setup)

The repository has a ready Keycloak 26 for local evaluation: `docker/docker-compose-with-keycloak.yml` with the realm `docker/keycloak/opencrawling-realm.json`. It runs in development mode over plain HTTP. Do not use it in production.

### What the realm contains

- **Client `opencrawling-cli`:** public, device authorization grant only, for `oc auth login`.
- **Client `opencrawling-automation`:** confidential, client credentials grant only, for the SDK and CI. Its service account has the realm role `hr-team`. The realm ships without its secret: Keycloak generates one on import, which you read in the admin console (Clients, `opencrawling-automation`, Credentials).
- **Client `opencrawling-admin-ui`:** public, standard flow only, PKCE `S256` required, redirect URIs `http://localhost:3000/auth/callback` (the UI container), `http://localhost:8080/auth/callback` (the UI served by the runtime) and `http://localhost:5173/auth/callback` (`npm run dev`). An audience mapper adds `opencrawling-api` to its access tokens.
- **Realm roles:** `opencrawling-admin`, `hr-team`, `finance-team`. Keycloak puts them in `realm_access.roles`, which the runtime reads by default.
- **Demo users without passwords:** `hr_admin` (`hr-team`), `finance_admin` (`finance-team`), `global_admin` (`opencrawling-admin`).

### Start it

Start the infrastructure first (`docker compose up -d`, as in the README), then:

```bash
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
export KC_BOOTSTRAP_ADMIN_PASSWORD='<choose a password>'
docker compose -f docker-compose-apps.yml -f docker/docker-compose-with-keycloak.yml up -d
```

The overlay adds Keycloak on `http://localhost:8180` and configures the runtime: `jwt` mode with `protect-api`, the UI client, management authorization with `opencrawling-admin` as admins and `hr-team`/`finance-team` as job creators, the issuer `http://localhost:8180/realms/opencrawling`, keys fetched from `keycloak:8080` inside the network, and the audience `opencrawling-api`.

### Give the demo users passwords

The realm ships without passwords. Set one per user; `kcadm.sh` prompts for each password, so none ends up in your shell history:

```bash
docker exec -it opencrawling-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME"
for user in hr_admin finance_admin global_admin; do
  docker exec -it opencrawling-keycloak /opt/keycloak/bin/kcadm.sh set-password -r opencrawling --username "$user"
done
```

You can also set them in the admin console (`http://localhost:8180`, realm `opencrawling`, Users).

### Try it

Open `http://localhost:3000` and sign in:

| User | Sees | May do |
|---|---|---|
| `global_admin` | every job and every page | everything |
| `hr_admin` | the jobs they own or are listed on; Connectors (without configuration) and Narrativization | create jobs, run and edit their own |
| `finance_admin` | the same for their own jobs | the same; never sees HR's jobs |

Jobs that existed before authorization was turned on have no owner. Only `global_admin` sees them, until it assigns them by editing their `access` block.

---

## The CLI and the Java SDK

### Signing in with the CLI

```bash
oc auth login            # the runtime from `oc config set`, or --server-url
oc auth status           # who you are to the runtime: user, groups, admin, may create jobs
oc auth logout           # revokes the session at the identity provider and forgets it
```

`oc auth login` uses the OAuth 2.0 device authorization grant: it shows a URL and a code, you confirm them in any browser, and the CLI receives a token. The CLI never sees your password. It finds the identity provider through the runtime (`GET /api/auth/config`); `--issuer` names another, and `--client-id` (default `opencrawling-cli`) the public client, which must allow the device grant.

The sign-in is kept in `~/.oc/credentials.json` (or `$OC_CONFIG_DIR`), readable only by you, and renewed with the refresh token as needed. It is sent only to the server it was made for. Commands authenticate with, first match wins: `--token`, client credentials from the environment, the stored sign-in, the bearer token from `oc config set`.

### Automation (client credentials)

In CI or scripts, give the CLI a confidential client through the environment, never on the command line:

```bash
export OPENCRAWLING_CLIENT_ID=opencrawling-automation
export OPENCRAWLING_CLIENT_SECRET=...           # from your CI's secret store
export OPENCRAWLING_TOKEN_URL=...               # optional: found through the runtime's issuer otherwise
export OPENCRAWLING_CLIENT_SCOPE=...            # optional
oc job list
```

The Java SDK does the same with `OpenCrawlingClient.builder().clientCredentials(tokenUrl, clientId, clientSecret, scope)`, or with the Spring Boot properties `opencrawling.client.oauth2.token-uri`, `client-id`, `client-secret` and `scope`. The token is cached and renewed before it expires. A service account acts as itself: give it the groups it needs (`job-creator-groups`, or a job's `allowedGroups`), like any user.

---

## Microsoft Entra ID

Register two applications, or one that plays both roles:

1. **The API:** under *Expose an API*, set the Application ID URI (for example `api://<api-client-id>`) and add a scope such as `access_as_user`. In its manifest, set `"accessTokenAcceptedVersion": 2` so access tokens carry the v2 issuer. For groups, either add the `groups` claim under *Token configuration* (values are group object IDs) or define *App roles* and assign them (they arrive in `roles`).
2. **The admin UI:** add a *Single-page application* platform with the redirect URI `https://<ui-host>/auth/callback`. Single-page clients use PKCE and have no secret. Grant it the API's `access_as_user` permission.

Then configure the runtime:

```yaml
opencrawling.security.identity:
  admin-ui:
    client-id: <ui-client-id>
    scope: openid profile api://<api-client-id>/access_as_user   # so the access token is for your API
  jwt:
    group-claims: roles,groups
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
  audiences: <api-client-id>
```

The UI must ask for your API's scope: without it Entra ID issues an access token for Microsoft Graph, which the runtime rejects. With group object IDs in `groups`, use those IDs in `admin-groups`, `job-creator-groups` and job `access` blocks, or use app roles with readable names.

---

## Okta

1. Use a **custom authorization server** (for example `default`, issuer `https://<your-org>.okta.com/oauth2/default`). Tokens from the org authorization server are meant for Okta itself and cannot be validated by other applications.
2. Create an **OIDC single-page application** with grant type *Authorization Code* (PKCE is required for single-page apps), sign-in redirect URI `https://<ui-host>/auth/callback`, and sign-out redirect URI `https://<ui-host>/`.
3. On the authorization server, add a **`groups` claim** to access tokens (for example, filter "Matches regex `.*`", or a narrower one).

```yaml
opencrawling.security.identity:
  admin-ui:
    client-id: <spa-client-id>
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: https://<your-org>.okta.com/oauth2/default
  audiences: api://default          # the authorization server's audience
```

Okta access tokens name the user in `sub` (the login), which the default `principal-claims` fall back to.

---

## Checklist for production

- **HTTPS everywhere:** the identity provider, the admin UI and the runtime. Redirect URIs must use the UI's real HTTPS address.
- **Audience set:** `spring.security.oauth2.resourceserver.jwt.audiences`.
- **Short-lived access tokens:** the UI renews them with the refresh token before they expire; a failed renewal sends the user back to sign in.
- **Group names agreed:** `admin-groups` and `job-creator-groups` exist in the provider, and job `access` blocks use the same names.
- **Automation secrets:** client secrets come from a secret store or the environment, never from committed files or command lines.
