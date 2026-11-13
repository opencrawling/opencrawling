/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.cli.commands;

import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.config.CredentialStore;
import org.opencrawling.cli.util.AnsiColors;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.auth.DeviceLogin;
import org.opencrawling.sdk.auth.OAuth2Client;
import org.opencrawling.sdk.auth.OAuth2Exception;
import org.opencrawling.sdk.auth.RefreshTokenProvider;
import org.opencrawling.sdk.models.CallerInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Clock;
import java.util.concurrent.Callable;

/**
 * {@code oc auth}: sign in to a runtime that requires tokens.
 *
 * <p>{@code login} uses the OAuth 2.0 device authorization grant: it shows a URL and a code, the user confirms them in
 * any browser, and the CLI receives a token. The CLI never sees the password. The identity provider comes from the
 * runtime ({@code GET /api/auth/config}) unless {@code --issuer} names one.
 */
@Command(
    name = "auth",
    mixinStandardHelpOptions = true,
    description = "Sign in to a runtime that requires tokens (login, status, logout)",
    subcommands = {
        AuthCommand.LoginCommand.class,
        AuthCommand.StatusCommand.class,
        AuthCommand.LogoutCommand.class
    }
)
public class AuthCommand implements Runnable {

    static final String DEFAULT_CLIENT_ID = "opencrawling-cli";

    private static volatile DeviceLogin.Sleeper sleeper = null;
    private static volatile Clock clock = Clock.systemUTC();

    /** Replaces the wait between polls (null: real time); for tests. */
    public static void setSleeper(DeviceLogin.Sleeper replacement) {
        sleeper = replacement;
    }

    @Override
    public void run() {
        System.out.println(AnsiColors.yellow("Please specify a subcommand: login, status, logout"));
    }

    @Command(name = "login", description = "Sign in with your organization's account (device code in a browser)")
    public static class LoginCommand implements Callable<Integer> {

        @Option(names = {"--server-url"}, description = "Runtime to sign in to (default: the configured one)")
        String serverUrl;

        @Option(names = {"--issuer"}, description = "Identity provider issuer URL (default: the one the runtime names)")
        String issuer;

        @Option(names = {"--client-id"}, defaultValue = DEFAULT_CLIENT_ID,
                description = "Public client with the device authorization grant (default: ${DEFAULT-VALUE})")
        String clientId;

        @Option(names = {"--scope"}, defaultValue = "openid profile", description = "Scopes to ask for (default: ${DEFAULT-VALUE})")
        String scope;

        @Override
        public Integer call() {
            String server = CliConfigService.serverUrl(serverUrl);
            try {
                String issuerUrl = issuer != null && !issuer.isBlank() ? issuer : CliConfigService.issuerOf(server);
                if (issuerUrl == null) {
                    System.err.println(AnsiColors.red("The runtime at " + server + " names no identity provider. "
                            + "Pass --issuer, or sign-in is not needed there."));
                    return 1;
                }
                OAuth2Client oauth = new OAuth2Client();
                OAuth2Client.Endpoints endpoints = oauth.discover(issuerUrl);
                DeviceLogin login = new DeviceLogin(oauth, clock, sleeper);
                OAuth2Client.DeviceAuthorization authorization = login.start(endpoints, clientId, scope);

                System.out.println(AnsiColors.bold("To sign in, open this page in a browser:"));
                System.out.println("  " + AnsiColors.cyan(authorization.verificationUriComplete() != null
                        ? authorization.verificationUriComplete() : authorization.verificationUri()));
                System.out.println("and check that it shows the code " + AnsiColors.bold(authorization.userCode()) + ".");
                System.out.println(AnsiColors.yellow("Waiting for you to confirm..."));

                RefreshTokenProvider.Tokens tokens = login.await(endpoints, clientId, authorization);
                CredentialStore.Login saved = new CredentialStore.Login(server, endpoints.issuer(), clientId,
                        endpoints.tokenEndpoint(), endpoints.revocationEndpoint(), tokens.accessToken(),
                        tokens.refreshToken(), tokens.expiresAt());
                CliConfigService.credentials().save(saved);

                String who = whoAmI(server);
                System.out.println(AnsiColors.green("✔ Signed in to " + server + (who != null ? " as " + who : "") + "."));
                if (tokens.refreshToken() == null) {
                    System.out.println(AnsiColors.yellow("The identity provider issued no refresh token: sign in again when the token expires."));
                }
                return 0;
            } catch (OAuth2Exception e) {
                System.err.println(AnsiColors.red("Sign-in failed: " + e.getMessage()));
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println(AnsiColors.red("Sign-in cancelled."));
                return 1;
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Sign-in failed: " + e.getMessage()));
                return 1;
            }
        }
    }

    @Command(name = "status", description = "Show who you are signed in as")
    public static class StatusCommand implements Callable<Integer> {

        @Option(names = {"--server-url"}, description = "Runtime to check (default: the configured one)")
        String serverUrl;

        @Override
        public Integer call() {
            String server = CliConfigService.serverUrl(serverUrl);
            CredentialStore.Login login = CliConfigService.credentials().find(server);
            try (OpenCrawlingClient client = CliConfigService.createClient(server, null, null)) {
                CallerInfo me = client.auth().me();
                if (!me.authenticated()) {
                    System.out.println("Not signed in to " + server + " (it accepts requests without a token).");
                    return login == null ? 0 : 1;
                }
                System.out.println(AnsiColors.bold("Signed in to " + server));
                System.out.println("  User:   " + me.principal());
                System.out.println("  Groups: " + (me.groups().isEmpty() ? "(none)" : String.join(", ", me.groups())));
                if (me.tenant() != null) {
                    System.out.println("  Tenant: " + me.tenant());
                }
                if (me.managementAuthorization()) {
                    System.out.println("  Admin:  " + (me.admin() ? "yes" : "no") + ", may create jobs: "
                            + (me.canCreateJobs() ? "yes" : "no"));
                }
                if (login != null) {
                    System.out.println("  Issuer: " + login.issuer());
                }
                return 0;
            } catch (Exception e) {
                if (login == null) {
                    System.out.println("Not signed in to " + server + ". Run: oc auth login");
                } else {
                    System.err.println(AnsiColors.red("The sign-in for " + server + " no longer works: " + e.getMessage()));
                    System.err.println("Run: oc auth login");
                }
                return 1;
            }
        }
    }

    @Command(name = "logout", description = "Sign out: revoke and forget the stored sign-in")
    public static class LogoutCommand implements Callable<Integer> {

        @Option(names = {"--server-url"}, description = "Runtime to sign out of (default: the configured one)")
        String serverUrl;

        @Override
        public Integer call() {
            String server = CliConfigService.serverUrl(serverUrl);
            CredentialStore store = CliConfigService.credentials();
            CredentialStore.Login login = store.find(server);
            if (login == null) {
                System.out.println("Not signed in to " + server + ".");
                return 0;
            }
            if (login.revocationEndpoint() != null && login.refreshToken() != null) {
                try {
                    new OAuth2Client().revoke(login.revocationEndpoint(), login.clientId(), login.refreshToken(), "refresh_token");
                } catch (OAuth2Exception e) {
                    System.err.println(AnsiColors.yellow("Could not revoke the session at the identity provider ("
                            + e.getMessage() + "); forgetting it locally."));
                }
            }
            try {
                store.remove(server);
            } catch (Exception e) {
                System.err.println(AnsiColors.red("Could not remove " + store.file() + ": " + e.getMessage()));
                return 1;
            }
            System.out.println(AnsiColors.green("✔ Signed out of " + server + "."));
            return 0;
        }
    }

    private static String whoAmI(String server) {
        try (OpenCrawlingClient client = CliConfigService.createClient(server, null, null)) {
            CallerInfo me = client.auth().me();
            return me != null && me.authenticated() ? me.principal() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
