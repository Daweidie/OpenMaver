import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Account persistence is deliberately independent of shareable module/config profiles. */
final class MaverAccounts {
    private MaverAccounts() { }

    static String clientId() {
        String id = System.getProperty("openmaver.microsoft.clientId", "").trim();
        if (!id.isEmpty()) return id;
        id = System.getenv("OPENMAVER_MICROSOFT_CLIENT_ID");
        if (id != null && !id.trim().isEmpty()) return id.trim();
        Properties properties = new Properties();
        Path path = Paths.get("config", "openmaver-auth.properties");
        try {
            if (Files.isRegularFile(path)) {
                try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
                id = properties.getProperty("clientId", "").trim();
                if (!id.isEmpty()) return id;
            }
            try (InputStream input = MaverAccounts.class.getResourceAsStream("/openmaver/microsoft-auth.properties")) {
                if (input != null) {
                    properties.clear();
                    properties.load(input);
                    return properties.getProperty("clientId", "").trim();
                }
            }
        } catch (IOException ignored) { }
        return "";
    }

    static Store openStore() throws IOException {
        return new Store(Paths.get(System.getProperty("openmaver.accounts", "config/openmaver-accounts.json")));
    }

    static final class Account {
        final String id, name, clientId, refreshToken;

        Account(String id, String name, String clientId, String refreshToken) throws IOException {
            if (!id.matches("[0-9a-f]{32}") || !name.matches("[A-Za-z0-9_]{1,16}")
                    || !clientId.matches("[A-Za-z0-9-]{1,128}")
                    || refreshToken.isEmpty() || refreshToken.length() > 131072) {
                throw new IOException("Invalid saved account");
            }
            this.id = id;
            this.name = name;
            this.clientId = clientId;
            this.refreshToken = refreshToken;
        }
    }

    static final class Store {
        private final Path path;
        private List<Account> accounts = new ArrayList<Account>();

        Store(Path path) throws IOException {
            this.path = path.toAbsolutePath();
            if (!Files.exists(this.path)) return;
            if (Files.size(this.path) > 1024 * 1024) throw new IOException("Account file too large");
            Map<String, Object> json = AuthJson.read(new String(Files.readAllBytes(this.path), StandardCharsets.UTF_8));
            if (!Long.valueOf(1).equals(json.get("version")) || !(json.get("accounts") instanceof List)) {
                throw new IOException("Unknown account file format");
            }
            List<?> saved = (List<?>) json.get("accounts");
            if (saved.size() > 100) throw new IOException("Too many saved accounts");
            for (Object value : saved) {
                Map<String, Object> item = AuthJson.map(value);
                Account account = new Account(AuthJson.string(item, "id"), AuthJson.string(item, "name"),
                        AuthJson.string(item, "clientId"), AuthJson.string(item, "refreshToken"));
                for (Account existing : accounts) {
                    if (existing.id.equals(account.id)) throw new IOException("Duplicate saved account");
                }
                accounts.add(account);
            }
            restrict(this.path);
        }

        List<Account> list() { return Collections.unmodifiableList(new ArrayList<Account>(accounts)); }

        void put(MicrosoftAuth.Result result) throws IOException {
            Account account = new Account(result.id, result.name, result.clientId, result.refreshToken);
            List<Account> updated = new ArrayList<Account>(accounts);
            for (int i = updated.size() - 1; i >= 0; i--) {
                if (updated.get(i).id.equals(account.id)) updated.remove(i);
            }
            updated.add(0, account);
            if (updated.size() > 100) throw new IOException("Too many saved accounts");
            save(updated);
        }

        void remove(String id) throws IOException {
            List<Account> updated = new ArrayList<Account>(accounts);
            for (int i = updated.size() - 1; i >= 0; i--) {
                if (updated.get(i).id.equals(id)) updated.remove(i);
            }
            save(updated);
        }

        private void save(List<Account> updated) throws IOException {
            List<Object> saved = new ArrayList<Object>();
            for (Account account : updated) {
                saved.add(AuthJson.object("id", account.id, "name", account.name,
                        "clientId", account.clientId, "refreshToken", account.refreshToken));
            }
            byte[] data = AuthJson.write(AuthJson.object("version", 1, "accounts", saved)).getBytes(StandardCharsets.UTF_8);
            if (data.length > 1024 * 1024) throw new IOException("Account file too large");
            Path directory = path.getParent();
            Files.createDirectories(directory);
            boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
            Path temporary = posix
                    ? Files.createTempFile(directory, ".openmaver-accounts-", ".tmp",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(directory, ".openmaver-accounts-", ".tmp");
            try {
                Files.write(temporary, data);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                accounts = updated;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private static void restrict(Path file) throws IOException {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        }
    }

    /** Called only on Minecraft's GUI thread, after authentication has fully succeeded. */
    static void applySession(Minecraft minecraft, MicrosoftAuth.Result result) throws IOException {
        try {
            Field world = field(minecraft.getClass(), "theWorld", "field_71441_e");
            if (world.get(minecraft) != null) throw new MicrosoftAuth.Failure("请先退出当前世界，再切换账号。");
            Field session;
            try {
                session = field(minecraft.getClass(), "session", "field_71449_j");
            } catch (NoSuchFieldException e) {
                session = null;
                for (Field candidate : minecraft.getClass().getDeclaredFields()) {
                    if (candidate.getType() == Session.class) { session = candidate; break; }
                }
                if (session == null) throw e;
                session.setAccessible(true);
            }
            // In 1.8.9 Microsoft game tokens use the normal MOJANG session type.
            Session replacement = new Session(result.name, result.id, result.accessToken, "mojang");
            session.set(minecraft, replacement);
            try {
                Object properties = field(minecraft.getClass(), "profileProperties", "field_181038_N").get(minecraft);
                if (properties != null) properties.getClass().getMethod("clear").invoke(properties);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional skin/profile cache; the session itself is already valid.
            }
        } catch (MicrosoftAuth.Failure e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new MicrosoftAuth.Failure("当前游戏版本无法切换登录会话，请确认使用 Forge 1.8.9。");
        }
    }

    private static Field field(Class<?> type, String... names) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) { }
            }
        }
        throw new NoSuchFieldException("Minecraft account field unavailable");
    }
}
