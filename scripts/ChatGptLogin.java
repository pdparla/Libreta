import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sign in with ChatGPT for Libreta (docs/setup/chatgpt.md, docs/design/ai.md), following OpenAI's flow for open-source apps
 * (developers.openai.com/siwc/token-sharing-open-source): browser sign-in with a loopback callback, plan-usage
 * scope, ID token checked against OpenAI's keys. Writes the session for the tablet to the file given as argument;
 * scripts/chatgpt-login.sh hands it over with adb.
 *
 * The tablet's host id and the issued client id are kept in ~/.libreta/chatgpt-client.json (not secrets), so that
 * signing in again reuses the same registration. Tokens are never printed.
 *
 *   java scripts/ChatGptLogin.java <session-file>
 */
public class ChatGptLogin {

    static final String ISSUER = "https://auth.openai.com";
    static final String AUTHORIZE = ISSUER + "/api/accounts/authorize";
    static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    static final String JWKS = ISSUER + "/.well-known/jwks.json";
    static final String RESOURCE = "https://api.openai.com/v1";
    static final String PLAN_USAGE = "chatgpt.tokens.use.direct";
    static final String SCOPE = "openid profile email offline_access resource.invoke " + PLAN_USAGE;
    static final int PORT = 1455;
    static final String REDIRECT = "http://127.0.0.1:" + PORT + "/auth/callback";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java ChatGptLogin.java <session-file>");
            System.exit(2);
        }
        Path out = Paths.get(args[0]);
        Path clientFile = Paths.get(System.getProperty("user.home"), ".libreta", "chatgpt-client.json");
        Map<String, Object> client = Files.exists(clientFile) ? Json.object(Files.readString(clientFile)) : new LinkedHashMap<>();
        String hostId = (String) client.get("ext_agent_host_id");
        if (hostId == null) hostId = "urn:uuid:" + UUID.randomUUID();
        String savedClientId = (String) client.get("client_id");

        String verifier = base64Url(random(32));
        String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = base64Url(random(16));
        String nonce = base64Url(random(16));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", savedClientId != null ? savedClientId : "dynamic_agent_client");
        if (savedClientId == null) query.put("agent_name_hint", "Libreta");
        query.put("ext_agent_host_id", hostId);
        query.put("response_type", "code");
        query.put("redirect_uri", REDIRECT);
        query.put("scope", SCOPE);
        query.put("resource", RESOURCE);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge_method", "S256");
        query.put("code_challenge", challenge);
        String url = AUTHORIZE + "?" + form(query);

        Map<String, String> callback = awaitCallback(url);
        if (!state.equals(callback.get("state"))) fail("the answer does not belong to this sign-in (state)");
        if (callback.containsKey("error")) fail("sign-in refused: " + callback.get("error"));
        String clientId = callback.get("client_id");
        if (savedClientId == null && clientId == null) fail("OpenAI did not issue a client id");
        if (savedClientId != null && clientId != null && !clientId.equals(savedClientId)) fail("OpenAI answered for another client id");
        if (clientId == null) clientId = savedClientId;

        Map<String, String> exchange = new LinkedHashMap<>();
        exchange.put("grant_type", "authorization_code");
        exchange.put("client_id", clientId);
        exchange.put("code", callback.get("code"));
        exchange.put("code_verifier", verifier);
        exchange.put("redirect_uri", REDIRECT);
        exchange.put("resource", RESOURCE);
        Map<String, Object> tokens = postForm(TOKEN, exchange);

        String scope = (String) tokens.getOrDefault("scope", callback.getOrDefault("scope", ""));
        if (!List.of(scope.split(" ")).contains(PLAN_USAGE)) fail("ChatGPT plan usage was not granted (" + PLAN_USAGE + ")");
        String idToken = (String) tokens.get("id_token");
        Map<String, Object> claims = verifiedClaims(idToken, clientId, nonce);
        String subject = (String) claims.get("sub");
        String email = (String) claims.getOrDefault("email", "");

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("client_id", clientId);
        session.put("ext_agent_host_id", hostId);
        session.put("subject", subject);
        session.put("email", email);
        session.put("access_token", tokens.get("access_token"));
        session.put("refresh_token", tokens.get("refresh_token"));
        session.put("id_token", idToken);
        session.put("scope", scope);
        session.put("expires_at", System.currentTimeMillis() / 1000 + ((Number) tokens.get("expires_in")).longValue());
        Files.writeString(out, Json.write(session));

        client.put("client_id", clientId);
        client.put("ext_agent_host_id", hostId);
        client.put("email", email);
        Files.createDirectories(clientFile.getParent());
        Files.writeString(clientFile, Json.write(client));
        System.out.println("Signed in as " + email + " (client " + clientId + ")");
    }

    /** Opens the browser and waits for the redirect to 127.0.0.1, or for its address pasted in the terminal. */
    static Map<String, String> awaitCallback(String url) throws Exception {
        CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 0);
        server.createContext("/auth/callback", exchange -> {
            byte[] page = "<html><body><h2>Libreta: ya puedes cerrar esta ventana.</h2></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
            result.complete(parseQuery(exchange.getRequestURI().getRawQuery()));
        });
        server.start();
        Thread paste = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                for (String line; (line = in.readLine()) != null; ) {
                    int query = line.indexOf('?');
                    if (line.trim().startsWith(REDIRECT) && query > 0) {
                        result.complete(parseQuery(line.trim().substring(query + 1)));
                        return;
                    }
                    if (!line.isBlank()) System.out.println("Paste the whole address that starts with " + REDIRECT);
                }
            } catch (IOException ignored) {
                // No terminal: only the browser's redirect can finish.
            }
        });
        paste.setDaemon(true);
        paste.start();

        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                opened = true;
            }
        } catch (Exception ignored) {
            // Headless or no browser registered: the address is printed below.
        }
        System.out.println(opened ? "The browser has opened to sign in with ChatGPT." : "Open this address in the browser:\n" + url);
        System.out.println("If the browser ends on a page that does not load (" + REDIRECT + "…), paste that address here.");
        try {
            return result.get(15, TimeUnit.MINUTES);
        } finally {
            server.stop(0);
        }
    }

    /** The ID token's claims, after checking its RS256 signature against OpenAI's keys, issuer, audience, expiry and nonce. */
    static Map<String, Object> verifiedClaims(String idToken, String clientId, String nonce) throws Exception {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) fail("malformed ID token");
        Map<String, Object> header = Json.object(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.get("alg"))) fail("unexpected ID token algorithm " + header.get("alg"));
        PublicKey key = null;
        for (Object k : (List<?>) getJson(JWKS).get("keys")) {
            Map<?, ?> jwk = (Map<?, ?>) k;
            if (jwk.get("kid").equals(header.get("kid")) && "RSA".equals(jwk.get("kty"))) {
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("n")));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e")));
                key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            }
        }
        if (key == null) fail("ID token signed with an unknown key");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) fail("ID token signature does not verify");

        Map<String, Object> claims = Json.object(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        if (!ISSUER.equals(claims.get("iss"))) fail("ID token from another issuer");
        Object aud = claims.get("aud");
        if (!(clientId.equals(aud) || (aud instanceof List && ((List<?>) aud).contains(clientId)))) fail("ID token for another client");
        if (((Number) claims.get("exp")).longValue() < System.currentTimeMillis() / 1000) fail("ID token expired");
        if (!nonce.equals(claims.get("nonce"))) fail("ID token for another sign-in (nonce)");
        if (claims.get("sub") == null) fail("ID token without subject");
        return claims;
    }

    static Map<String, Object> postForm(String url, Map<String, String> fields) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) fail("token exchange refused (HTTP " + response.statusCode() + "): " + errorOf(response.body()));
        return Json.object(response.body());
    }

    static Map<String, Object> getJson(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) fail(url + " answered HTTP " + response.statusCode());
        return Json.object(response.body());
    }

    /** The error code of an OAuth error body, which carries no token. */
    static String errorOf(String body) {
        try {
            Object error = Json.object(body).get("error");
            return String.valueOf(error instanceof Map ? ((Map<?, ?>) error).get("code") : error);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    static void fail(String message) {
        System.err.println("Could not sign in with ChatGPT: " + message);
        System.exit(1);
    }

    static byte[] random(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String form(Map<String, String> fields) {
        StringJoiner joined = new StringJoiner("&");
        fields.forEach((k, v) -> joined.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return joined.toString();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (raw == null) return fields;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** Just enough JSON for OAuth answers and the session file. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(String text) {
            Json parser = new Json(text);
            Object value = parser.value();
            parser.space();
            if (!(value instanceof Map) || parser.i != text.length()) throw new IllegalArgumentException("not a JSON object");
            return (Map<String, Object>) value;
        }

        static String write(Map<String, Object> object) {
            StringJoiner fields = new StringJoiner(",\n  ", "{\n  ", "\n}\n");
            object.forEach((k, v) -> fields.add(quote(k) + ": " + (v instanceof Number || v instanceof Boolean ? v.toString() : quote(String.valueOf(v)))));
            return fields.toString();
        }

        static String quote(String text) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }

        private Object value() {
            space();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                i++;
                space();
                if (s.charAt(i) == '}') { i++; return map; }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    map.put(key, value());
                    space();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return map;
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                i++;
                space();
                if (s.charAt(i) == ']') { i++; return list; }
                while (true) {
                    list.add(value());
                    space();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return list;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad JSON at " + start);
            String number = s.substring(start, i);
            return number.matches("-?\\d+") ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u': out.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: out.append(e);
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        private void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
