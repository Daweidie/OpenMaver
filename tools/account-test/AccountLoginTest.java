import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.Session;
import net.minecraft.world.World;

/** Tests the full protocol and actual menu/GUI boundaries without external network or credentials. */
public final class AccountLoginTest {
    private static final String CLIENT = "maver-test-app";
    private static final String ID = "abcdef0123456789abcdef0123456789";
    private static final String CODE = "authorization+code/&=test";
    private static final String MS_TOKEN = "test-ms-access-never-log";
    private static final String GAME_TOKEN = "test-minecraft-access-never-save";
    private static final String REFRESH_TOKEN = "test-rotated-refresh-never-log";
    private static final MicrosoftAuth.Browser NO_BROWSER = new MicrosoftAuth.Browser() {
        @Override public void open(String url) { }
    };
    private static int checks;
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("openmaver-account-test-");
        System.setProperty("openmaver.accounts", root.resolve("gui-accounts.json").toString());
        System.setProperty("openmaver.microsoft.clientId", "invalid client id!");
        jsonResponses();
        MicrosoftAuth.Result authenticated = browserLogin();
        refreshAndFailures();
        cancellation();
        persistence(authenticated);
        sessionAndGui(authenticated);
        menuEntry();
        System.out.println("account-test: " + checks + " checks passed (fake services, local callbacks only)");
    }

    private static void jsonResponses() throws Exception {
        String token = "quotes\" slash\\ unicode登录\n\t";
        check(token.equals(AuthJson.string(AuthJson.read(AuthJson.write(AuthJson.object("token", token))), "token")),
                "JSON preserves escaped credentials");
        for (String bad : Arrays.asList("{", "{\"a\":1,\"a\":2}", "{\"token\":\"raw\ncontrol\"}",
                "{\"n\":01}", "{\"n\":1e}", "{\"n\":1e999}", "{\"a\":[]} trailing", "[]")) {
            try { AuthJson.read(bad); throw new AssertionError("Accepted malformed response"); }
            catch (IOException expected) { check(true, "Malformed auth response rejected"); }
        }
        try { MicrosoftAuth.query("state=one&state=two"); throw new AssertionError("Duplicate state"); }
        catch (IOException expected) { check(true, "Ambiguous callback parameters rejected"); }
    }

    private static MicrosoftAuth.Result browserLogin() throws Exception {
        FakeTransport transport = new FakeTransport();
        final AtomicInteger opened = new AtomicInteger();
        MicrosoftAuth.Attempt attempt = new MicrosoftAuth.Attempt(CLIENT, null, transport, new MicrosoftAuth.Browser() {
            @Override public void open(String url) { opened.incrementAndGet(); }
        });
        URI authorize = new URI(awaitUrl(attempt));
        check(authorize.getScheme().equals("https") && authorize.getHost().equals("login.live.com"), "Official authorization endpoint");
        Map<String, String> params = MicrosoftAuth.query(authorize.getRawQuery());
        check(CLIENT.equals(params.get("client_id")), "Uses Maver's configured application ID");
        check("code".equals(params.get("response_type")) && "select_account".equals(params.get("prompt")), "Browser selects a Microsoft account");
        check(MicrosoftAuth.SCOPE.equals(params.get("scope")), "Xbox and offline refresh scopes");
        check("S256".equals(params.get("code_challenge_method")), "PKCE uses SHA-256");
        check(params.get("state").length() >= 43 && params.get("code_challenge").length() == 43, "Random state and PKCE challenge");
        transport.challenge = params.get("code_challenge");
        transport.redirect = params.get("redirect_uri");
        URI redirect = new URI(transport.redirect);
        check(redirect.getHost().equals("localhost") && redirect.getPort() > 0, "Browser callback stays on localhost");
        check(callback(redirect, "state=wrong&code=attacker").startsWith("HTTP/1.1 400"), "Wrong-state callback rejected");
        check(callback(redirect, "error=access_denied").startsWith("HTTP/1.1 400"), "Errors without state cannot cancel login");
        check(callback(redirect, MicrosoftAuth.form("state", params.get("state"), "state", params.get("state"), "code", CODE))
                .startsWith("HTTP/1.1 400"), "Duplicate callback state rejected");
        check(!attempt.isDone() && transport.calls.isEmpty(), "Invalid callbacks leave valid attempt pending");
        check(callback(redirect, MicrosoftAuth.form("state", params.get("state"), "code", CODE))
                .startsWith("HTTP/1.1 200"), "Valid callback acknowledged");
        done(attempt);
        check(attempt.error() == null && attempt.result() != null, "Complete browser login succeeds");
        MicrosoftAuth.Result result = attempt.result();
        check(ID.equals(result.id) && "PremiumPlayer".equals(result.name), "Minecraft profile returned");
        check(GAME_TOKEN.equals(result.accessToken) && REFRESH_TOKEN.equals(result.refreshToken), "Distinct game and refresh tokens");
        check(transport.calls.size() == 6, "All six official exchanges run in order");
        check(opened.get() == 1, "System browser starts once");
        check(attempt.authorizationUrl() == null, "Completed browser authorization link cleared");
        checkClosed(redirect);
        return result;
    }

    private static void refreshAndFailures() throws Exception {
        FakeTransport transport = new FakeTransport();
        MicrosoftAuth.Attempt refreshed = refreshing(transport);
        done(refreshed);
        check(refreshed.result() != null && refreshed.error() == null, "Saved account refresh succeeds");
        check(refreshed.authorizationUrl() == null, "Refresh needs no browser");
        check(transport.calls.get(0).equals(MicrosoftAuth.REFRESH), "Refresh uses the Microsoft consumer token endpoint");

        for (String mode : new String[] {"expired", "child", "no-xbox", "wrong-user", "no-profile", "limited", "malformed", "network"}) {
            FakeTransport failed = new FakeTransport();
            failed.failure = mode;
            MicrosoftAuth.Attempt attempt = refreshing(failed);
            done(attempt);
            check(attempt.result() == null && attempt.error() != null, mode + " cannot yield a game session");
            check(!attempt.error().contains(MS_TOKEN) && !attempt.error().contains(REFRESH_TOKEN), "Failure messages contain no credentials");
            if (mode.equals("no-profile")) check(attempt.error().contains("Java"), "Missing Java license/profile explained");
            if (mode.equals("wrong-user")) check(!failed.calls.contains(MicrosoftAuth.MINECRAFT), "Mismatched Xbox identities rejected early");
        }
        MicrosoftAuth.Attempt missingId = new MicrosoftAuth.Attempt("", null, transport, NO_BROWSER);
        done(missingId);
        check(missingId.error().contains("配置") && missingId.result() == null, "Missing application ID is explicit");

        FakeTransport denied = new FakeTransport();
        MicrosoftAuth.Attempt attempt = new MicrosoftAuth.Attempt(CLIENT, null, denied, NO_BROWSER);
        Map<String, String> params = MicrosoftAuth.query(new URI(awaitUrl(attempt)).getRawQuery());
        callback(new URI(params.get("redirect_uri")), MicrosoftAuth.form("state", params.get("state"), "error", "access_denied"));
        done(attempt);
        check(attempt.result() == null && attempt.error().contains("取消") && denied.calls.isEmpty(), "User-denied authorization ends cleanly");
    }

    private static void cancellation() throws Exception {
        FakeTransport fake = new FakeTransport();
        MicrosoftAuth.Attempt waiting = new MicrosoftAuth.Attempt(CLIENT, null, fake, NO_BROWSER);
        Map<String, String> params = MicrosoftAuth.query(new URI(awaitUrl(waiting)).getRawQuery());
        waiting.cancel();
        done(waiting);
        check(waiting.isCancelled() && waiting.result() == null && fake.calls.isEmpty(), "Cancel closes pending authorization");
        checkClosed(new URI(params.get("redirect_uri")));

        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        final FakeTransport official = new FakeTransport();
        MicrosoftAuth.Transport blocked = new MicrosoftAuth.Transport() {
            @Override public MicrosoftAuth.Response request(String method, String url, String type, String body,
                    String token, MicrosoftAuth.Attempt attempt) throws IOException {
                reached.countDown();
                boolean released = false;
                while (!released) {
                    try { released = release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { }
                }
                return official.request(method, url, type, body, token, attempt);
            }
        };
        MicrosoftAuth.Attempt inFlight = new MicrosoftAuth.Attempt(CLIENT, "saved-refresh", blocked, NO_BROWSER);
        check(reached.await(3, TimeUnit.SECONDS), "Cancellation test reaches an in-flight request");
        inFlight.cancel();
        release.countDown();
        done(inFlight);
        check(inFlight.result() == null && official.calls.size() == 1, "Late HTTP response cannot resume a cancelled login");
    }

    private static void persistence(MicrosoftAuth.Result result) throws Exception {
        Path path = root.resolve("saved/accounts.json");
        MaverAccounts.Store store = new MaverAccounts.Store(path);
        store.put(result);
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        check(raw.contains(REFRESH_TOKEN) && !raw.contains(GAME_TOKEN) && !raw.contains(MS_TOKEN), "Only refresh credentials are persisted");
        MaverAccounts.Store loaded = new MaverAccounts.Store(path);
        check(loaded.list().size() == 1 && loaded.list().get(0).name.equals("PremiumPlayer"), "Saved account reloads");
        check(loaded.list().get(0).clientId.equals(CLIENT), "Refresh uses the original application registration");
        loaded.put(new MicrosoftAuth.Result(ID, "RenamedPlayer", GAME_TOKEN, "new-rotated-token", CLIENT));
        loaded = new MaverAccounts.Store(path);
        check(loaded.list().size() == 1 && loaded.list().get(0).refreshToken.equals("new-rotated-token"), "Refresh rotation and profile renames replace the same UUID");
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            check(PosixFilePermissions.toString(Files.getPosixFilePermissions(path)).equals("rw-------"), "Account credentials are owner-readable/writable only");
        }
        loaded.remove(ID);
        check(new MaverAccounts.Store(path).list().isEmpty(), "Removing an account persists");
        Path malformed = root.resolve("broken-accounts.json");
        Files.write(malformed, "broken".getBytes(StandardCharsets.UTF_8));
        try { new MaverAccounts.Store(malformed); throw new AssertionError("Corrupt store accepted"); }
        catch (IOException expected) { check(new String(Files.readAllBytes(malformed), StandardCharsets.UTF_8).equals("broken"), "Corrupt account file is preserved"); }
        MaverAccounts.Store failStore = new MaverAccounts.Store(root.resolve("write-test.json"));
        failStore.put(result);
        Files.delete(root.resolve("write-test.json"));
        Files.createDirectory(root.resolve("write-test.json"));
        Files.write(root.resolve("write-test.json/block"), new byte[] {1});
        try { failStore.remove(ID); throw new AssertionError("Expected failed replace"); }
        catch (IOException expected) { check(failStore.list().size() == 1, "Failed disk update preserves in-memory account records"); }
    }

    private static void sessionAndGui(MicrosoftAuth.Result result) throws Exception {
        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        Session original = mc.getSession();
        mc.theWorld = new World();
        try { MaverAccounts.applySession(mc, result); throw new AssertionError("Switched in world"); }
        catch (MicrosoftAuth.Failure expected) { check(mc.getSession() == original, "Switching inside a world preserves session"); }
        mc.theWorld = null;
        MaverAccounts.applySession(mc, result);
        check(mc.getSession().getUsername().equals("PremiumPlayer") && mc.getSession().getToken().equals(GAME_TOKEN), "Final session field accepts verified account");
        check(mc.getSession().getProfile().getId().toString().replace("-", "").equals(ID), "Session uses the premium UUID");
        check(mc.cacheCleared(), "Old profile/skin properties cleared");

        new MaverAccounts.Store(root.resolve("gui-accounts.json")).put(result);
        GuiScreen parent = new GuiScreen();
        FakeFactory factory = new FakeFactory();
        GuiMaverAccounts gui = new GuiMaverAccounts(parent, true, factory);
        mc.displayGuiScreen(gui);
        MicrosoftAuth.Attempt pending = (MicrosoftAuth.Attempt) get(gui, "attempt");
        awaitUrl(pending);
        gui.width = 640;
        gui.height = 360;
        gui.initGui();
        check(factory.logins == 1 && get(gui, "attempt") == pending, "Resizing does not restart browser login");
        click(gui, "Cancel");
        done(pending);
        check(mc.getSession().getUsername().equals("PremiumPlayer") && pending.isCancelled(), "Cancel keeps the current session");
        click(gui, "Login");
        MicrosoftAuth.Attempt refresh = (MicrosoftAuth.Attempt) get(gui, "attempt");
        done(refresh);
        gui.pollCompletion();
        check(factory.refreshes == 1 && mc.currentScreen == parent, "Saved-account Login refreshes and returns to menu");

        Minecraft failedMc = new Minecraft();
        Minecraft.instance = failedMc;
        Session before = failedMc.getSession();
        factory = new FakeFactory();
        factory.failure = "no-profile";
        gui = new GuiMaverAccounts(parent, false, factory);
        failedMc.displayGuiScreen(gui);
        click(gui, "Login");
        done((MicrosoftAuth.Attempt) get(gui, "attempt"));
        gui.pollCompletion();
        check(failedMc.getSession() == before && failedMc.currentScreen == gui, "Invalid premium profile preserves old session and shows error");
        check(((String) get(gui, "message")).contains("Java"), "GUI shows a useful ownership error");

        factory = new FakeFactory();
        gui = new GuiMaverAccounts(parent, false, factory);
        failedMc.displayGuiScreen(gui);
        click(gui, "Login");
        refresh = (MicrosoftAuth.Attempt) get(gui, "attempt");
        done(refresh);
        failedMc.displayGuiScreen(parent);
        gui.pollCompletion();
        check(failedMc.getSession() == before && refresh.result() == null, "Leaving page discards a late successful authentication");

        gui = new GuiMaverAccounts(parent, false, factory);
        failedMc.displayGuiScreen(gui);
        click(gui, "Remove");
        check(new MaverAccounts.Store(root.resolve("gui-accounts.json")).list().isEmpty(), "Remove button deletes local saved account");
        check(failedMc.getSession() == before, "Remove never changes active game session");
    }

    @SuppressWarnings("unchecked")
    private static void menuEntry() throws Exception {
        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        // Seed skin cache: menu initialization must never perform real HTTP in this test.
        Field cacheField = GuiMaverMainMenu.class.getDeclaredField("SKIN_CACHE");
        cacheField.setAccessible(true);
        Map<String, BufferedImage> cache = (Map<String, BufferedImage>) cacheField.get(null);
        cache.put("original", new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        cache.put("premiumplayer", new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        GuiMaverMainMenu menu = new GuiMaverMainMenu();
        mc.displayGuiScreen(menu);
        List<?> buttons = (List<?>) get(menu, "buttons");
        Object plus = buttons.get(buttons.size() - 1);
        check(get(plus, "label").equals("Add account"), "Main menu + is labeled Add account");
        menu.mouseClicked((Integer) get(plus, "x") + 2, (Integer) get(plus, "y") + 2, 0);
        check(mc.currentScreen instanceof GuiMaverAccounts, "+ opens the account screen");
        GuiMaverAccounts accounts = (GuiMaverAccounts) mc.currentScreen;
        MicrosoftAuth.Attempt attempt = (MicrosoftAuth.Attempt) get(accounts, "attempt");
        check(attempt != null, "+ automatically starts authorization");
        done(attempt); // Deliberately invalid configured ID: no browser/external network is allowed.
        check(attempt.error().contains("配置"), "Unconfigured build explains Microsoft application setup");
        mc.displayGuiScreen(menu);
        menu.mouseClicked(25, 25, 0);
        check(mc.currentScreen instanceof GuiMaverAccounts && get(mc.currentScreen, "attempt") == null,
                "Clicking avatar/name opens saved accounts without starting a new login");
        mc.displayGuiScreen(menu);
        MaverAccounts.applySession(mc, new MicrosoftAuth.Result(ID, "PremiumPlayer", GAME_TOKEN, REFRESH_TOKEN, CLIENT));
        menu.initGui();
        check(get(menu, "username").equals("PremiumPlayer"), "Returning to the same menu refreshes the account name");
        check(get(menu, "skinImage") == cache.get("premiumplayer"), "Switched account gets its own skin");
    }

    private static MicrosoftAuth.Attempt refreshing(FakeTransport fake) {
        return new MicrosoftAuth.Attempt(CLIENT, "saved-refresh", fake, NO_BROWSER);
    }

    private static final class FakeFactory implements GuiMaverAccounts.LoginFactory {
        int logins, refreshes;
        String failure = "";
        @Override public MicrosoftAuth.Attempt login(String clientId) {
            logins++;
            return new MicrosoftAuth.Attempt(CLIENT, null, new FakeTransport(), NO_BROWSER);
        }
        @Override public MicrosoftAuth.Attempt refresh(String clientId, String token) {
            refreshes++;
            FakeTransport fake = new FakeTransport();
            fake.expectedRefresh = token;
            fake.failure = failure;
            return new MicrosoftAuth.Attempt(clientId, token, fake, NO_BROWSER);
        }
    }

    private static final class FakeTransport implements MicrosoftAuth.Transport {
        final List<String> calls = new ArrayList<String>();
        String challenge, redirect;
        String failure = "", expectedRefresh = "saved-refresh";

        @Override public MicrosoftAuth.Response request(String method, String url, String type, String body,
                String bearer, MicrosoftAuth.Attempt attempt) throws IOException {
            calls.add(url);
            if (failure.equals("network")) throw new IOException("sensitive " + MS_TOKEN);
            if (url.equals(MicrosoftAuth.TOKEN) || url.equals(MicrosoftAuth.REFRESH)) {
                check(method.equals("POST") && type.equals("application/x-www-form-urlencoded") && bearer == null,
                        "Microsoft token exchange uses form encoding");
                Map<String, String> form = MicrosoftAuth.query(body);
                check(CLIENT.equals(form.get("client_id")), "Token exchange uses the correct client ID");
                if (url.equals(MicrosoftAuth.TOKEN)) {
                    check(form.get("grant_type").equals("authorization_code") && CODE.equals(form.get("code")), "Authorization code is encoded without corruption");
                    check(MicrosoftAuth.challenge(form.get("code_verifier")).equals(challenge), "PKCE verifier matches browser challenge");
                    check(redirect.equals(form.get("redirect_uri")) && MicrosoftAuth.SCOPE.equals(form.get("scope")), "Exchange reuses the exact callback URI/scope");
                } else {
                    check(form.get("grant_type").equals("refresh_token") && expectedRefresh.equals(form.get("refresh_token")), "Saved refresh token sent correctly");
                }
                if (failure.equals("expired")) return response(400, "error", "invalid_grant", "error_description", REFRESH_TOKEN);
                return response(200, "access_token", MS_TOKEN, "refresh_token", REFRESH_TOKEN);
            }
            if (url.equals(MicrosoftAuth.XBOX)) {
                check(method.equals("POST") && type.equals("application/json") && bearer == null, "Xbox JSON request has no accidental bearer");
                Map<String, Object> data = AuthJson.read(body);
                Map<String, Object> properties = AuthJson.map(data.get("Properties"));
                check(("d=" + MS_TOKEN).equals(properties.get("RpsTicket")) && "RPS".equals(properties.get("AuthMethod")), "Microsoft token exchanged as Xbox RPS ticket");
                check("http://auth.xboxlive.com".equals(data.get("RelyingParty")), "Xbox user-token relying party");
                return claims("test-xbox-token", "test-user-hash");
            }
            if (url.equals(MicrosoftAuth.XSTS)) {
                Map<String, Object> data = AuthJson.read(body);
                Map<String, Object> properties = AuthJson.map(data.get("Properties"));
                check("RETAIL".equals(properties.get("SandboxId")) && Arrays.asList("test-xbox-token").equals(properties.get("UserTokens")), "XSTS uses the Xbox user token");
                check("rp://api.minecraftservices.com/".equals(data.get("RelyingParty")), "XSTS scoped to Minecraft Services");
                if (failure.equals("child")) return response(401, "XErr", 2148916238L);
                if (failure.equals("no-xbox")) return response(401, "XErr", 2148916233L);
                return claims("test-xsts-token", failure.equals("wrong-user") ? "other-user" : "test-user-hash");
            }
            if (url.equals(MicrosoftAuth.MINECRAFT)) {
                check(AuthJson.read(body).get("identityToken").equals("XBL3.0 x=test-user-hash;test-xsts-token"), "Minecraft receives XSTS identity token");
                if (failure.equals("limited")) return response(429, "error", "TooManyRequests");
                return response(200, "access_token", GAME_TOKEN, "expires_in", 86400);
            }
            check(method.equals("GET") && GAME_TOKEN.equals(bearer) && body == null, "Only the game access token is used for Minecraft profile/ownership");
            if (url.equals(MicrosoftAuth.ENTITLEMENTS)) return response(200, "items", new ArrayList<Object>());
            if (url.equals(MicrosoftAuth.PROFILE)) {
                if (failure.equals("no-profile")) return response(404, "error", "NOT_FOUND");
                if (failure.equals("malformed")) return response(200, "id", "bad-uuid", "name", "PremiumPlayer");
                return response(200, "id", ID, "name", "PremiumPlayer");
            }
            throw new AssertionError("Unexpected authentication endpoint");
        }
    }

    private static MicrosoftAuth.Response claims(String token, String hash) {
        return response(200, "Token", token, "DisplayClaims", AuthJson.object("xui", Arrays.asList(AuthJson.object("uhs", hash))));
    }
    private static MicrosoftAuth.Response response(int code, Object... pairs) {
        return new MicrosoftAuth.Response(code, AuthJson.object(pairs));
    }
    private static String awaitUrl(MicrosoftAuth.Attempt attempt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (attempt.authorizationUrl() == null && !attempt.isDone() && System.nanoTime() < deadline) Thread.sleep(5);
        check(attempt.authorizationUrl() != null, "Authorization URL published: " + attempt.error());
        return attempt.authorizationUrl();
    }
    private static void done(MicrosoftAuth.Attempt attempt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!attempt.isDone() && System.nanoTime() < deadline) Thread.sleep(5);
        check(attempt.isDone(), "Authentication worker completes promptly");
    }
    private static String callback(URI redirect, String query) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", redirect.getPort())) {
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + redirect.getRawPath() + "?" + query + " HTTP/1.1\r\nHost: localhost:"
                    + redirect.getPort() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) received.write(buffer, 0, length);
            return new String(received.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void checkClosed(URI redirect) throws Exception {
        try (Socket ignored = new Socket("127.0.0.1", redirect.getPort())) {
            throw new AssertionError("Callback listener left open");
        } catch (IOException expected) { check(true, "Callback listener released"); }
    }
    private static void click(GuiMaverAccounts gui, String label) throws Exception {
        gui.drawScreen(-1, -1, 0);
        for (Object control : (List<?>) get(gui, "controls")) {
            if (label.equals(get(control, "label"))) {
                gui.mouseClicked((Integer) get(control, "x") + 1, (Integer) get(control, "y") + 1, 0);
                return;
            }
        }
        throw new AssertionError("Missing account control: " + label);
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static synchronized void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
