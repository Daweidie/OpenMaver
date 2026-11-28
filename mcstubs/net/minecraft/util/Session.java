package net.minecraft.util;

import com.mojang.authlib.GameProfile;

/** Minimal 1.8.9 stub for reading the active account name / profile. */
public class Session {
    private final String username;
    private final GameProfile profile;
    private final String token;

    public Session(String username, GameProfile profile) {
        this.username = username;
        this.profile = profile;
        this.token = "";
    }

    public Session(String username, String playerID, String token, String sessionType) {
        this.username = username;
        this.token = token;
        String id = playerID.replace("-", "");
        this.profile = new GameProfile(java.util.UUID.fromString(id.substring(0, 8) + "-"
                + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20)), username);
    }

    public String getUsername() {
        return username;
    }

    public GameProfile getProfile() {
        return profile;
    }

    /** Access token used by the official Minecraft services API (Microsoft / Mojang). */
    public String getToken() {
        return token;
    }
}
