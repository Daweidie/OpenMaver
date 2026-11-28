import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft public-client OAuth + PKCE, Xbox Live, XSTS and Minecraft Services.
 * Protocol reference: HMCL OAuth / MicrosoftService; implementation uses only Java 8 APIs.
 * Credentials and server response bodies must never be included in diagnostics.
 */
final class MicrosoftAuth {
    static final String AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
    static final String TOKEN = "https://login.live.com/oauth20_token.srf";
    static final String REFRESH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String XBOX = "https://user.auth.xboxlive.com/user/authenticate";
    static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    static final String MINECRAFT = "https://api.minecraftservices.com/authentication/login_with_xbox";
    static final String ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore";
    static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    static final String SCOPE = "XboxLive.signin offline_access";
    private static final SecureRandom RANDOM = new SecureRandom();

    private MicrosoftAuth() { }

    static final class Result {
        final String id, name, accessToken, refreshToken, clientId;

        Result(String id, String name, String accessToken, String refreshToken, String clientId) {
            this.id = id;
            this.name = name;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.clientId = clientId;
        }
    }

    /** A known, safe message suitable for display to the player. */
    static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }

    interface Transport {
        Response request(String method, String url, String type, String body, String bearer,
                Attempt attempt) throws IOException;
    }

    interface Browser {
        void open(String url) throws Exception;
    }

    static final class Response {
        final int code;
        final Map<String, Object> json;

        Response(int code, Map<String, Object> json) { this.code = code; this.json = json; }
    }

    static Attempt login(String clientId) {
        return new Attempt(clientId, null, new HttpTransport(), new SystemBrowser());
    }

    static Attempt refresh(String clientId, String refreshToken) {
        return new Attempt(clientId, refreshToken, new HttpTransport(), new SystemBrowser());
    }

    /** Owns all resources belonging to one login; the GUI only polls immutable results. */
    static final class Attempt {
        private final String clientId, savedToken;
        private final Transport transport;
        private final Browser browser;
        private final Thread worker;
        private volatile boolean cancelled, done;
        private volatile String status = "正在准备微软登录…";
        private volatile String authorizationUrl;
        private volatile String error;
        private volatile Result result;
        private volatile HttpURLConnection connection;
        private volatile Callback callback;

        Attempt(String clientId, String refreshToken, Transport transport, Browser browser) {
            this.clientId = clientId == null ? "" : clientId.trim();
            this.savedToken = refreshToken;
            this.transport = transport;
            this.browser = browser;
            worker = new Thread(new Runnable() {
                @Override public void run() { authenticate(); }
            }, "OpenMaver-MicrosoftLogin");
            worker.setDaemon(true);
            worker.start();
        }

        boolean isDone() { return done; }
        boolean isCancelled() { return cancelled; }
        String status() { return status; }
        String error() { return error; }
        String authorizationUrl() { return authorizationUrl; }
        Result result() { return cancelled ? null : result; }

        void cancel() {
            cancelled = true;
            result = null;
            Callback server = callback;
            if (server != null) server.close();
            HttpURLConnection http = connection;
            if (http != null) http.disconnect();
            worker.interrupt();
        }

        void check() throws Failure {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new Failure("已取消登录。");
        }

        void openBrowser() {
            final String url = authorizationUrl;
            if (url == null || done || cancelled) return;
            Thread opener = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        if (!cancelled && !done) browser.open(url);
                    } catch (Exception ignored) {
                        if (!done && !cancelled) status = "无法自动打开浏览器，请复制登录链接后打开。";
                    }
                }
            }, "OpenMaver-LoginBrowser");
            opener.setDaemon(true);
            opener.start();
        }

        private void authenticate() {
            try {
                check();
                if (!clientId.matches("[A-Za-z0-9-]{1,128}")) {
                    throw new Failure("此 Maver 版本尚未配置微软登录，请按 README 的账号登录说明配置。");
                }
                Map<String, Object> microsoft;
                if (savedToken != null) {
                    status = "正在刷新微软账号…";
                    microsoft = oauth(request(REFRESH, form("client_id", clientId,
                            "refresh_token", savedToken, "grant_type", "refresh_token"), true));
                } else {
                    Callback server = new Callback(this);
                    callback = server;
                    try {
                        check();
                        authorizationUrl = AUTHORIZE + "?" + form("client_id", clientId,
                                "response_type", "code", "redirect_uri", server.redirectUri,
                                "scope", SCOPE, "prompt", "select_account", "state", server.state,
                                "code_challenge", challenge(server.verifier), "code_challenge_method", "S256");
                        status = "请在浏览器中登录微软账号，完成后将自动返回游戏。";
                        openBrowser();
                        String code = server.awaitCode();
                        authorizationUrl = null;
                        check();
                        status = "正在验证微软账号…";
                        microsoft = oauth(request(TOKEN, form("client_id", clientId, "code", code,
                                "grant_type", "authorization_code", "code_verifier", server.verifier,
                                "redirect_uri", server.redirectUri, "scope", SCOPE), true));
                    } finally {
                        server.close();
                        callback = null;
                        authorizationUrl = null;
                    }
                }
                String msToken = AuthJson.string(microsoft, "access_token");
                String refreshToken = microsoft.get("refresh_token") instanceof String
                        ? AuthJson.string(microsoft, "refresh_token") : savedToken;
                if (refreshToken == null || refreshToken.isEmpty()) throw new Failure("微软未返回账号续期凭据，请重新授权。");
                status = "正在登录 Xbox Live…";
                Map<String, Object> xbox = success(request(XBOX, AuthJson.write(AuthJson.object(
                        "Properties", AuthJson.object("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com",
                                "RpsTicket", "d=" + msToken),
                        "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT")), false), "Xbox Live");
                String userHash = userHash(xbox);
                status = "正在验证 Xbox 账号…";
                Map<String, Object> xsts = success(request(XSTS, AuthJson.write(AuthJson.object(
                        "Properties", AuthJson.object("SandboxId", "RETAIL", "UserTokens",
                                Arrays.asList(AuthJson.string(xbox, "Token"))),
                        "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT")), false), "Xbox XSTS");
                if (!userHash.equals(userHash(xsts))) throw new Failure("Xbox 账号验证不一致，请重新登录。");
                status = "正在登录 Minecraft…";
                Map<String, Object> game = success(request(MINECRAFT, AuthJson.write(AuthJson.object(
                        "identityToken", "XBL3.0 x=" + userHash + ";" + AuthJson.string(xsts, "Token"))), false), "Minecraft");
                String gameToken = AuthJson.string(game, "access_token");
                status = "正在验证正版游戏与角色…";
                success(get(ENTITLEMENTS, gameToken), "Minecraft 正版验证");
                // Game Pass can return an empty entitlements list; a valid Java profile is decisive.
                Response profileResponse = get(PROFILE, gameToken);
                if (profileResponse.code == 404) {
                    throw new Failure("此账号没有 Minecraft Java 版角色，请确认已购买游戏或拥有有效订阅，并在官网创建角色。");
                }
                Map<String, Object> profile = success(profileResponse, "Minecraft 角色");
                String id = AuthJson.string(profile, "id");
                String name = AuthJson.string(profile, "name");
                if (!id.matches("[0-9a-fA-F]{32}") || !name.matches("[A-Za-z0-9_]{1,16}")) {
                    throw new Failure("Minecraft 返回的角色信息无效，请稍后重试。");
                }
                check();
                result = new Result(id.toLowerCase(Locale.ROOT), name, gameToken, refreshToken, clientId);
                status = "登录成功";
            } catch (Failure e) {
                if (!cancelled) error = e.getMessage();
            } catch (IOException e) {
                if (!cancelled) error = "连接登录服务失败或响应无效，请检查网络后重试。";
            } catch (Exception e) {
                if (!cancelled) error = "无法完成微软登录，请稍后重试。";
            } finally {
                Callback server = callback;
                if (server != null) server.close();
                authorizationUrl = null;
                done = true;
            }
        }

        private Response request(String url, String body, boolean form) throws IOException {
            check();
            Response response = transport.request("POST", url, form ? "application/x-www-form-urlencoded"
                    : "application/json", body, null, this);
            check();
            return response;
        }

        private Response get(String url, String token) throws IOException {
            check();
            Response response = transport.request("GET", url, null, null, token, this);
            check();
            return response;
        }
    }

    private static Map<String, Object> oauth(Response response) throws Failure {
        Object error = response.json.get("error");
        if ("invalid_grant".equals(error) || "interaction_required".equals(error)) {
            throw new Failure("微软授权已过期或已撤销，请重新添加此账号。");
        }
        if ("invalid_client".equals(error) || "unauthorized_client".equals(error)
                || "invalid_scope".equals(error)) {
            throw new Failure("微软登录应用配置无效，请检查此 Maver 版本的登录配置。");
        }
        if (error != null) throw new Failure("微软未能完成授权，请重新登录。");
        return success(response, "微软授权");
    }

    private static Map<String, Object> success(Response response, String service) throws Failure {
        Object xerr = response.json.get("XErr");
        if (xerr instanceof Number) {
            long code = ((Number) xerr).longValue();
            if (code == 2148916233L) throw new Failure("此微软账号尚未创建 Xbox 档案，请先在 xbox.com 创建后重试。");
            if (code == 2148916235L) throw new Failure("此账号所在地区暂不支持 Xbox Live。");
            if (code == 2148916236L || code == 2148916237L || code == 2148916238L) {
                throw new Failure("Xbox 账号需要年龄验证或家庭管理员授权，请在 xbox.com 完成后重试。");
            }
            throw new Failure("Xbox 拒绝了账号授权，请检查账号状态后重试。");
        }
        if (response.code != 200) {
            if (response.code == 429) throw new Failure("登录请求过于频繁，请稍后重试。");
            throw new Failure(service + "失败（HTTP " + response.code + "），请稍后重试。");
        }
        return response.json;
    }

    private static String userHash(Map<String, Object> token) throws IOException {
        Object xui = AuthJson.map(token.get("DisplayClaims")).get("xui");
        if (!(xui instanceof List) || ((List<?>) xui).isEmpty()) throw new IOException("Missing Xbox claims");
        return AuthJson.string(AuthJson.map(((List<?>) xui).get(0)), "uhs");
    }

    static String form(String... pairs) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) out.append('&');
            out.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return out.toString();
    }

    static Map<String, String> query(String raw) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (raw == null) return result;
        try {
            for (String pair : raw.split("&")) {
                int split = pair.indexOf('=');
                if (split < 1) throw new IOException("Invalid OAuth query");
                String key = URLDecoder.decode(pair.substring(0, split), "UTF-8");
                String value = URLDecoder.decode(pair.substring(split + 1), "UTF-8");
                if (result.put(key, value) != null) throw new IOException("Duplicate OAuth query parameter");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid OAuth query encoding");
        }
        return result;
    }

    private static String random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) throws IOException {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable");
        }
    }

    /** Loopback-only, single-use OAuth callback. Invalid state never consumes the pending login. */
    private static final class Callback {
        final String state = random(32);
        final String verifier = random(64);
        final String redirectUri;
        final ServerSocket server;
        final Attempt attempt;
        volatile Socket activeSocket;

        Callback(Attempt attempt) throws IOException {
            this.attempt = attempt;
            server = new ServerSocket();
            try {
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
                server.setSoTimeout(1000);
            } catch (IOException e) {
                close();
                throw new Failure("无法启动本地登录回调，请检查本机网络或防火墙后重试。");
            }
            redirectUri = "http://localhost:" + server.getLocalPort() + "/auth-response";
        }

        String awaitCode() throws IOException {
            long deadline = System.nanoTime() + 5L * 60L * 1000000000L;
            while (System.nanoTime() < deadline) {
                attempt.check();
                try (Socket socket = server.accept()) {
                    activeSocket = socket;
                    attempt.check();
                    socket.setSoTimeout(2000);
                    InputStream input = socket.getInputStream();
                    String request = line(input);
                    int headerSize = request.length();
                    String host = null;
                    for (int count = 0; count < 50; count++) {
                        String header = line(input);
                        headerSize += header.length();
                        if (headerSize > 16384) throw new IOException("Oversized callback");
                        if (header.isEmpty()) break;
                        if (header.toLowerCase(Locale.ROOT).startsWith("host:")) host = header.substring(5).trim();
                        if (count == 49) throw new IOException("Too many headers");
                    }
                    String[] parts = request.split(" ");
                    Map<String, String> params = null;
                    if (parts.length == 3 && parts[0].equals("GET")
                            && (host != null && (host.equals("localhost:" + server.getLocalPort())
                            || host.equals("127.0.0.1:" + server.getLocalPort())))) {
                        try {
                            URI uri = URI.create(parts[1]);
                            if (!uri.isAbsolute() && uri.getRawPath().equals("/auth-response")) params = query(uri.getRawQuery());
                        } catch (IllegalArgumentException | IOException ignored) { }
                    }
                    if (params == null || !state.equals(params.get("state"))) {
                        reply(socket, 400, "Invalid login callback. Return to Maver to continue.");
                        continue;
                    }
                    String code = params.get("code");
                    if (params.containsKey("error")) {
                        reply(socket, 200, "Login was cancelled. You may return to Maver.");
                        throw new Failure("微软登录已取消或未获得授权。");
                    }
                    if (code == null || code.isEmpty()) {
                        reply(socket, 400, "Missing authorization code.");
                        continue;
                    }
                    reply(socket, 200, "Microsoft authorization received. You may close this tab and return to Maver.");
                    return code;
                } catch (SocketTimeoutException ignored) {
                    // Continue accepting until the overall login deadline.
                } catch (Failure e) {
                    throw e;
                } catch (IOException ignored) {
                    attempt.check();
                    // A malformed/abandoned localhost request does not cancel a valid login.
                } finally {
                    activeSocket = null;
                }
            }
            throw new Failure("微软登录已超时，请重试。");
        }

        void close() {
            try { server.close(); } catch (IOException ignored) { }
            Socket socket = activeSocket;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < 8192; i++) {
                int b = input.read();
                if (b == -1) throw new IOException("Incomplete callback");
                if (b == '\n') return new String(out.toByteArray(), StandardCharsets.US_ASCII).replace("\r", "");
                out.write(b);
            }
            throw new IOException("Oversized callback line");
        }

        private static void reply(Socket socket, int code, String message) throws IOException {
            byte[] body = ("<!doctype html><meta charset=\"utf-8\"><title>Maver</title>"
                    + "<h2>Maver</h2><p>" + message + "</p>").getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Bad Request")
                    + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store"
                    + "\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'"
                    + "\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }
    }

    private static final class HttpTransport implements Transport {
        @Override public Response request(String method, String url, String type, String body,
                String bearer, Attempt attempt) throws IOException {
            attempt.check();
            HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
            attempt.connection = http;
            try {
                http.setInstanceFollowRedirects(false);
                http.setConnectTimeout(12000);
                http.setReadTimeout(20000);
                http.setUseCaches(false);
                http.setRequestMethod(method);
                http.setRequestProperty("Accept", "application/json");
                http.setRequestProperty("User-Agent", "OpenMaver/2.2.0");
                if (bearer != null) http.setRequestProperty("Authorization", "Bearer " + bearer);
                if (body != null) {
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    http.setDoOutput(true);
                    http.setRequestProperty("Content-Type", type + "; charset=utf-8");
                    http.setFixedLengthStreamingMode(data.length);
                    attempt.check();
                    try (OutputStream output = http.getOutputStream()) { output.write(data); }
                }
                attempt.check();
                int code = http.getResponseCode();
                InputStream stream = code < 400 ? http.getInputStream() : http.getErrorStream();
                Map<String, Object> json = AuthJson.object();
                if (stream != null) {
                    try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096];
                        int length;
                        while ((length = input.read(buffer)) != -1) {
                            attempt.check();
                            if (output.size() + length > 1024 * 1024) throw new IOException("Oversized auth response");
                            output.write(buffer, 0, length);
                        }
                        if (output.size() > 0) {
                            try { json = AuthJson.read(new String(output.toByteArray(), StandardCharsets.UTF_8)); }
                            catch (IOException e) { if (code >= 200 && code < 300) throw e; }
                        }
                    }
                }
                return new Response(code, json);
            } finally {
                http.disconnect();
                attempt.connection = null;
            }
        }
    }

    private static final class SystemBrowser implements Browser {
        @Override public void open(String url) throws Exception {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try { Desktop.getDesktop().browse(new URI(url)); return; } catch (Exception ignored) { }
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder process;
            if (os.contains("win")) process = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            else if (os.contains("mac")) process = new ProcessBuilder("open", url);
            else process = new ProcessBuilder("xdg-open", url);
            Process opened = process.start();
            if (opened.waitFor(2, TimeUnit.SECONDS) && opened.exitValue() != 0) {
                throw new IOException("Browser opener failed");
            }
        }
    }
}
