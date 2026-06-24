package app.spellscroll.auth;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Performs OAuth2 Authorization Code + PKCE authentication against Google or Microsoft,
 * using a temporary localhost redirect server to capture the authorization code.
 *
 * <p>Only the refresh token is persisted (via {@link SpellscrollTokenStore}, which delegates to
 * the OS native credential store). Access tokens are obtained fresh each Gradle run by
 * exchanging the refresh token, so they never touch disk.
 */
public class SpellscrollOAuthClient
{
    /*
     *  NOTE: The Google Client ID and Secret are public ID/secret credentials for Desktop App/CLI authentication, and per
     *  RFC 8252, because of the native nature of this tool, cannot be considered "confidential".
     */
    private static final String GOOGLE_CLIENT_ID = "754087461473-tg46r85c5263oulfrcoe74u92c1a7465.apps.googleusercontent.com";
    private static final String GOOGLE_CLIENT_SECRET = "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";
    private static final String MICROSOFT_CLIENT_ID = "b95b4984-5e66-4f59-b094-cc208124a5a2";

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String MS_AUTH_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String MS_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    private static final int CALLBACK_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

    public enum Provider { GOOGLE, MICROSOFT }

    private final HttpClient http = HttpClient.newHttpClient();
    private final SpellscrollTokenStore tokenStore;

    public SpellscrollOAuthClient(SpellscrollTokenStore tokenStore)
    {
        this.tokenStore = tokenStore;
    }

    /**
     * Returns a fresh access token by silently exchanging the stored refresh token.
     *
     * <p>Because we don't record which provider issued the stored token, we try Google first
     * then Microsoft — only the correct provider will accept the token. Returns {@code null}
     * if no refresh token is stored or both providers reject it, in which case the caller
     * should invoke {@link #login()}.
     */
    public String getValidToken() throws IOException, InterruptedException
    {
        String refreshToken = tokenStore.load();
        if (refreshToken == null) return null;

        String token = tryRefresh(Provider.GOOGLE, refreshToken);
        if (token != null) return token;

        token = tryRefresh(Provider.MICROSOFT, refreshToken);
        if (token != null) return token;

        // Both providers rejected the stored token — clear it so the next run re-authenticates
        tokenStore.clear();
        return null;
    }

    /**
     * Runs the interactive browser-based OAuth2 flow, presenting a provider selection page.
     *
     * <p>Opens a local HTTP server and directs the browser to a sign-in page where the user
     * can choose between Google and Microsoft. Once a provider is chosen the standard
     * Authorization Code + PKCE flow completes in the same browser tab. Blocks until the
     * provider redirects back (up to 5 minutes). The resulting refresh token is persisted
     * to the OS credential store.
     *
     * @return the access token for the current session
     * @throws IOException          if network I/O fails
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public String login() throws IOException, InterruptedException
    {
        int port = findFreePort();
        String redirectUri = "http://localhost:" + port + "/callback";
        String[] pkce        = generatePkce();
        String codeVerifier  = pkce[0];
        String codeChallenge = pkce[1];

        // ServerSocket must be open BEFORE the browser is launched; otherwise the OAuth2
        // redirect could arrive and be refused before we start accepting connections.
        try (ServerSocket serverSocket = new ServerSocket(port))
        {
            serverSocket.setSoTimeout(CALLBACK_TIMEOUT_MS);
            openBrowser("http://localhost:" + port + "/");

            Provider chosenProvider = null;

            while (true)
            {
                try (Socket conn = serverSocket.accept())
                {
                    conn.setSoTimeout(10_000);
                    BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter    out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8), true);

                    String requestLine = in.readLine();
                    // Drain remaining headers to prevent a broken-pipe error in the browser
                    String headerLine;
                    while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) { /* drain */ }

                    if (requestLine == null || !requestLine.startsWith("GET ")) continue;

                    String fullPath = requestLine.split(" ")[1];
                    String pathOnly = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
                    String query    = fullPath.contains("?") ? fullPath.substring(fullPath.indexOf('?') + 1) : "";

                    if ("/".equals(pathOnly))
                    {
                        sendHtml(out, buildSelectionHtml());
                    }
                    else if ("/choose".equals(pathOnly))
                    {
                        String p = parseQueryParam(query, "provider");
                        chosenProvider = "microsoft".equalsIgnoreCase(p) ? Provider.MICROSOFT : Provider.GOOGLE;
                        sendRedirect(out, buildAuthUrl(chosenProvider, redirectUri, codeChallenge));
                    }
                    else if ("/callback".equals(pathOnly))
                    {
                        String code  = parseQueryParam(query, "code");
                        String error = parseQueryParam(query, "error");

                        sendHtml(out, error != null
                            ? buildCallbackHtml(false, error)
                            : buildCallbackHtml(true, null));

                        if (error != null) throw new IOException("OAuth2 provider returned error: " + error);
                        if (code == null) throw new IOException("No authorization code in OAuth2 callback");
                        if (chosenProvider == null) throw new IOException("OAuth2 callback received before provider selection");

                        return exchangeCode(chosenProvider, code, codeVerifier, redirectUri);
                    }
                    // Any other path (e.g. /favicon.ico) is silently ignored — the loop continues
                }
            }
        }
    }

    /**
     * Runs the interactive browser-based OAuth2 flow for {@code provider}.
     *
     * <p>The localhost redirect server is started <em>before</em> the browser is opened to
     * ensure the authorization code is never missed. Blocks until the provider redirects back
     * (up to 5 minutes). The resulting refresh token is persisted to the OS credential store.
     *
     * @return the access token for the current session
     * @throws IOException          if network I/O fails
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public String login(Provider provider) throws IOException, InterruptedException
    {
        int port = findFreePort();
        String redirectUri = "http://localhost:" + port + "/callback";
        String[] pkce        = generatePkce();
        String codeVerifier  = pkce[0];
        String codeChallenge = pkce[1];

        String authUrl = buildAuthUrl(provider, redirectUri, codeChallenge);

        // ServerSocket must be open BEFORE the browser is launched; otherwise the OAuth2
        // redirect could arrive and be refused before we start accepting connections.
        try (ServerSocket serverSocket = new ServerSocket(port))
        {
            serverSocket.setSoTimeout(CALLBACK_TIMEOUT_MS);
            openBrowser(authUrl);
            String code = acceptCallbackCode(serverSocket);
            return exchangeCode(provider, code, codeVerifier, redirectUri);
        }
    }

    /** Accepts one HTTP connection and extracts the {@code code} query parameter from the path. */
    private static String acceptCallbackCode(ServerSocket serverSocket) throws IOException
    {
        try (Socket client = serverSocket.accept())
        {
            client.setSoTimeout(10_000);
            BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true);

            // Read the first line: "GET /callback?code=xxx&state=yyy HTTP/1.1"
            String requestLine = in.readLine();
            if (requestLine == null || !requestLine.startsWith("GET ")) throw new IOException("Unexpected request received on OAuth2 callback port");

            // Drain remaining headers to prevent a broken-pipe error in the browser
            String headerLine;
            while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) { /* drain */ }

            String path = requestLine.split(" ")[1];
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            String code = parseQueryParam(query, "code");
            String error = parseQueryParam(query, "error");

            String body = error != null
                ? buildCallbackHtml(false, error)
                : buildCallbackHtml(true, null);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            out.print("HTTP/1.1 200 OK\r\n");
            out.print("Content-Type: text/html; charset=utf-8\r\n");
            out.print("Content-Length: " + bodyBytes.length + "\r\n");
            out.print("Connection: close\r\n");
            out.print("\r\n");
            out.print(body);
            out.flush();

            if (error != null) throw new IOException("OAuth2 provider returned error: " + error);
            if (code  == null) throw new IOException("No authorization code in OAuth2 callback");
            return code;
        }
    }

    /** Attempts a token refresh against {@code provider}. Returns the access token or {@code null} on failure. */
    private String tryRefresh(Provider provider, String refreshToken)
    {
        try
        {
            String tokenUrl = provider == Provider.GOOGLE ? GOOGLE_TOKEN_URL : MS_TOKEN_URL;
            String clientId = provider == Provider.GOOGLE ? GOOGLE_CLIENT_ID : MICROSOFT_CLIENT_ID;

            StringBuilder body = new StringBuilder()
                .append("grant_type=refresh_token")
                .append("&refresh_token=").append(encode(refreshToken))
                .append("&client_id=").append(encode(clientId));

            if (provider == Provider.GOOGLE) body.append("&client_secret=").append(encode(GOOGLE_CLIENT_SECRET));

            HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) return extractJsonValue(response.body(), "id_token");
        }
        catch (Exception ignored) {}
        return null;
    }

    /** Exchanges an authorization code for tokens, persists the refresh token, and returns the access token. */
    private String exchangeCode(Provider provider, String code, String codeVerifier, String redirectUri) throws IOException, InterruptedException
    {
        String tokenUrl = provider == Provider.GOOGLE ? GOOGLE_TOKEN_URL : MS_TOKEN_URL;
        String clientId = provider == Provider.GOOGLE ? GOOGLE_CLIENT_ID : MICROSOFT_CLIENT_ID;

        StringBuilder body = new StringBuilder()
            .append("grant_type=authorization_code")
            .append("&code=").append(encode(code))
            .append("&redirect_uri=").append(encode(redirectUri))
            .append("&client_id=").append(encode(clientId))
            .append("&code_verifier=").append(encode(codeVerifier));

        if (provider == Provider.GOOGLE) body.append("&client_secret=").append(encode(GOOGLE_CLIENT_SECRET));

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) throw new IOException("Token exchange failed (HTTP " + response.statusCode() + "): " + response.body());

        String idToken      = extractJsonValue(response.body(), "id_token");
        String refreshToken = extractJsonValue(response.body(), "refresh_token");

        if (refreshToken != null) tokenStore.save(refreshToken);
        if (idToken == null) throw new IOException("No id_token in token response: " + response.body());

        return idToken;
    }

    private static String buildAuthUrl(Provider provider, String redirectUri, String codeChallenge)
    {
        String baseUrl  = provider == Provider.GOOGLE ? GOOGLE_AUTH_URL : MS_AUTH_URL;
        String clientId = provider == Provider.GOOGLE ? GOOGLE_CLIENT_ID : MICROSOFT_CLIENT_ID;
        String scope    = provider == Provider.GOOGLE ? "openid email" : "openid email offline_access";

        StringBuilder url = new StringBuilder(baseUrl)
            .append("?response_type=code")
            .append("&client_id=").append(encode(clientId))
            .append("&redirect_uri=").append(encode(redirectUri))
            .append("&scope=").append(encode(scope))
            .append("&code_challenge=").append(codeChallenge)
            .append("&code_challenge_method=S256");

        // Google requires access_type=offline + prompt=consent to receive a refresh token
        if (provider == Provider.GOOGLE) url.append("&access_type=offline&prompt=consent");

        return url.toString();
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────────

    private static void sendHtml(PrintWriter out, String body)
    {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        out.print("HTTP/1.1 200 OK\r\n");
        out.print("Content-Type: text/html; charset=utf-8\r\n");
        out.print("Content-Length: " + bodyBytes.length + "\r\n");
        out.print("Connection: close\r\n");
        out.print("\r\n");
        out.print(body);
        out.flush();
    }

    private static void sendRedirect(PrintWriter out, String location)
    {
        out.print("HTTP/1.1 302 Found\r\n");
        out.print("Location: " + location + "\r\n");
        out.print("Content-Length: 0\r\n");
        out.print("Connection: close\r\n");
        out.print("\r\n");
        out.flush();
    }

    private static String buildSelectionHtml()
    {
        // Google G logo — same base64 source used by the Spellscroll web frontend
        String googleLogoB64 =
            "PHN2ZyB3aWR0aD0iMTgiIGhlaWdodD0iMTgiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+" +
            "PGcgZmlsbD0ibm9uZSIgZmlsbC1ydWxlPSJldmVub2RkIj48cGF0aCBkPSJNMTcuNiA5LjJsLS4xLTEuOEg5" +
            "djMuNGg0LjhDMTMuNiAxMiAxMyAxMyAxMiAxMy42djIuMmgzYTguOCA4LjggMCAwIDAgMi42LTYuNnoiIGZp" +
            "bGw9IiM0Mjg1RjQiIGZpbGwtcnVsZT0ibm9uemVybyIvPjxwYXRoIGQ9Ik05IDE4YzIuNCAwIDQuNS0uOCA2" +
            "LTIuMmwtMy0yLjJhNS40IDUuNCAwIDAgMS04LTIuOUgxVjEzYTkgOSAwIDAgMCA4IDV6IiBmaWxsPSIjMzRB" +
            "ODUzIiBmaWxsLXJ1bGU9Im5vbnplcm8iLz48cGF0aCBkPSJNNCAxMC43YTUuNCA1LjQgMCAwIDEgMC0zLjRW" +
            "NUgxYTkgOSAwIDAgMCAwIDhsMy0yLjN6IiBmaWxsPSIjRkJCQzA1IiBmaWxsLXJ1bGU9Im5vbnplcm8iLz48" +
            "cGF0aCBkPSJNOSAzLjZjMS4zIDAgMi41LjQgMy40IDEuM0wxNSAyLjNBOSA5IDAgMCAwIDEgNWwzIDIuNGE1" +
            "LjQgNS40IDAgMCAxIDUtMy43eiIgZmlsbD0iI0VBNDMzNSIgZmlsbC1ydWxlPSJub256ZXJvIi8+PHBhdGgg" +
            "ZD0iTTAgMGgxOHYxOEgweiIvPjwvZz48L3N2Zz4=";

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<title>Sign in to Spellscroll</title>\n");
        sb.append("<style>\n");
        sb.append("* { box-sizing: border-box; margin: 0; padding: 0; }\n");
        sb.append("body {\n");
        sb.append("  background: #232323;\n");
        sb.append("  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif;\n");
        sb.append("  display: flex;\n");
        sb.append("  align-items: center;\n");
        sb.append("  justify-content: center;\n");
        sb.append("  min-height: 100vh;\n");
        sb.append("}\n");
        sb.append(".card {\n");
        sb.append("  background: #333;\n");
        sb.append("  border-radius: 16px;\n");
        sb.append("  padding: 2rem;\n");
        sb.append("  width: min(360px, 90vw);\n");
        sb.append("  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);\n");
        sb.append("  display: flex;\n");
        sb.append("  flex-direction: column;\n");
        sb.append("  align-items: center;\n");
        sb.append("  gap: 1.5rem;\n");
        sb.append("}\n");
        sb.append("h2 {\n");
        sb.append("  color: #cccccc;\n");
        sb.append("  font-size: 1.4rem;\n");
        sb.append("  font-weight: 600;\n");
        sb.append("}\n");
        sb.append(".buttons {\n");
        sb.append("  display: flex;\n");
        sb.append("  flex-direction: column;\n");
        sb.append("  gap: 0.75rem;\n");
        sb.append("  width: 100%;\n");
        sb.append("}\n");
        sb.append(".btn-google {\n");
        sb.append("  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif;\n");
        sb.append("  background-color: white;\n");
        sb.append("  background-image: url(\"data:image/svg+xml;base64,").append(googleLogoB64).append("\");\n");
        sb.append("  background-repeat: no-repeat;\n");
        sb.append("  background-position: 12px 50%;\n");
        sb.append("  padding: 12px 16px 12px 42px;\n");
        sb.append("  border: none;\n");
        sb.append("  border-radius: 3px;\n");
        sb.append("  box-shadow: 0 -1px 0 rgba(0, 0, 0, 0.04), 0 1px 1px rgba(0, 0, 0, 0.25);\n");
        sb.append("  color: #444;\n");
        sb.append("  font-size: 14px;\n");
        sb.append("  font-weight: 500;\n");
        sb.append("  cursor: pointer;\n");
        sb.append("  display: block;\n");
        sb.append("  width: 100%;\n");
        sb.append("  text-align: center;\n");
        sb.append("  text-decoration: none;\n");
        sb.append("  transition: box-shadow 0.15s ease;\n");
        sb.append("}\n");
        sb.append(".btn-google:hover {\n");
        sb.append("  box-shadow: 0 -1px 0 rgba(0, 0, 0, 0.04), 0 2px 4px rgba(0, 0, 0, 0.3);\n");
        sb.append("}\n");
        sb.append(".btn-microsoft {\n");
        sb.append("  font-family: \"Open Sans\", \"Helvetica Neue\", Helvetica, Arial, sans-serif;\n");
        sb.append("  font-size: 16px;\n");
        sb.append("  font-weight: normal;\n");
        sb.append("  background: white;\n");
        sb.append("  border: none;\n");
        sb.append("  border-radius: 3px;\n");
        sb.append("  box-shadow: 0 -1px 0 rgba(0, 0, 0, 0.04), 0 1px 1px rgba(0, 0, 0, 0.25);\n");
        sb.append("  color: #000;\n");
        sb.append("  padding: 6px 12px;\n");
        sb.append("  cursor: pointer;\n");
        sb.append("  display: flex;\n");
        sb.append("  align-items: center;\n");
        sb.append("  justify-content: center;\n");
        sb.append("  gap: 10px;\n");
        sb.append("  width: 100%;\n");
        sb.append("  text-decoration: none;\n");
        sb.append("  transition: box-shadow 0.15s ease;\n");
        sb.append("  line-height: 1.5;\n");
        sb.append("}\n");
        sb.append(".btn-microsoft:hover {\n");
        sb.append("  box-shadow: 0 -1px 0 rgba(0, 0, 0, 0.04), 0 2px 4px rgba(0, 0, 0, 0.3);\n");
        sb.append("}\n");
        sb.append(".ms-logo { width: 21px; height: 21px; flex-shrink: 0; }\n");
        sb.append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<div class=\"card\">\n");
        sb.append("  <h2>Sign in to Spellscroll</h2>\n");
        sb.append("  <div class=\"buttons\">\n");
        sb.append("    <a class=\"btn-google\" href=\"/choose?provider=google\">Sign in with Google</a>\n");
        sb.append("    <a class=\"btn-microsoft\" href=\"/choose?provider=microsoft\">\n");
        sb.append("      <svg class=\"ms-logo\" viewBox=\"0 0 21 21\" xmlns=\"http://www.w3.org/2000/svg\">\n");
        sb.append("        <rect x=\"1\" y=\"1\" width=\"9\" height=\"9\" fill=\"#F25022\"/>\n");
        sb.append("        <rect x=\"11\" y=\"1\" width=\"9\" height=\"9\" fill=\"#7FBA00\"/>\n");
        sb.append("        <rect x=\"1\" y=\"11\" width=\"9\" height=\"9\" fill=\"#00A4EF\"/>\n");
        sb.append("        <rect x=\"11\" y=\"11\" width=\"9\" height=\"9\" fill=\"#FFB900\"/>\n");
        sb.append("      </svg>\n");
        sb.append("      <span>Sign in with Microsoft</span>\n");
        sb.append("    </a>\n");
        sb.append("  </div>\n");
        sb.append("</div>\n");
        sb.append("</body>\n</html>");
        return sb.toString();
    }

    private static String buildCallbackHtml(boolean success, String error)
    {
        String heading = success ? "Authentication successful!" : "Authentication failed";
        String message = success ? "You may close this tab." : htmlEscape(error != null ? error : "An unknown error occurred.");
        String headingColor = success ? "#cccccc" : "#cc4444";

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<title>").append(success ? "Signed in" : "Sign-in failed").append("</title>\n");
        sb.append("<style>\n");
        sb.append("* { box-sizing: border-box; margin: 0; padding: 0; }\n");
        sb.append("body {\n");
        sb.append("  background: #232323;\n");
        sb.append("  font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif;\n");
        sb.append("  display: flex;\n");
        sb.append("  align-items: center;\n");
        sb.append("  justify-content: center;\n");
        sb.append("  min-height: 100vh;\n");
        sb.append("}\n");
        sb.append(".card {\n");
        sb.append("  background: #333;\n");
        sb.append("  border-radius: 16px;\n");
        sb.append("  padding: 2rem;\n");
        sb.append("  width: min(360px, 90vw);\n");
        sb.append("  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);\n");
        sb.append("  display: flex;\n");
        sb.append("  flex-direction: column;\n");
        sb.append("  align-items: center;\n");
        sb.append("  gap: 0.75rem;\n");
        sb.append("  text-align: center;\n");
        sb.append("}\n");
        sb.append("h2 { color: ").append(headingColor).append("; font-size: 1.4rem; font-weight: 600; }\n");
        sb.append("p { color: #888; font-size: 0.9rem; }\n");
        sb.append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<div class=\"card\">\n");
        sb.append("  <h2>").append(heading).append("</h2>\n");
        sb.append("  <p>").append(message).append("</p>\n");
        sb.append("</div>\n");
        sb.append("</body>\n</html>");
        return sb.toString();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────

    private static String[] generatePkce()
    {
        byte[] verifierBytes = new byte[32];
        new SecureRandom().nextBytes(verifierBytes);
        String codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return new String[]{codeVerifier, codeChallenge};
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is guaranteed by the JDK specification; this branch is unreachable
            throw new RuntimeException(e);
        }
    }

    private static int findFreePort() throws IOException
    {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static void openBrowser(String url) throws IOException
    {
        String os = System.getProperty("os.name").toLowerCase();
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(URI.create(url));
        else if (os.contains("win")) Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
        else if (os.contains("mac")) Runtime.getRuntime().exec(new String[]{"open", url});
        else Runtime.getRuntime().exec(new String[]{"xdg-open", url});
    }

    private static String parseQueryParam(String query, String name)
    {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&"))
        {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (!pair.substring(0, eq).equals(name)) continue;
            try { return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8); }
            catch (Exception e) { return pair.substring(eq + 1); }
        }
        return null;
    }

    /**
     * Extracts a string or numeric JSON value by key from a flat JSON object.
     * Sufficient for the well-known, flat OAuth2 token response format — avoids the need
     * for an external JSON parsing dependency.
     */
    static String extractJsonValue(String json, String key)
    {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        while (idx < json.length() && " \t\r\n:".indexOf(json.charAt(idx)) >= 0) idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"')
        {
            // String value — scan for closing quote, honouring backslash escapes
            int start = idx + 1;
            int end   = start;
            while (end < json.length())
            {
                if (json.charAt(end) == '\\') { end += 2; continue; }
                if (json.charAt(end) == '"')  break;
                end++;
            }
            return json.substring(start, end);
        }
        else
        {
            // Number or boolean — terminated by comma, brace, or whitespace
            int start = idx;
            while (idx < json.length() && ",}\n\r\t ".indexOf(json.charAt(idx)) < 0) idx++;
            return json.substring(start, idx).trim();
        }
    }

    private static String encode(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String htmlEscape(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
