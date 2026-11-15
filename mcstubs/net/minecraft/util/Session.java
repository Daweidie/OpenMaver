package net.minecraft.util;

import com.mojang.authlib.GameProfile;

/** Minimal 1.8.9 stub for reading the active account name / profile. */
public class Session {
    private final String username;
    private final GameProfile profile;

    public Session(String username, GameProfile profile) {
        this.username = username;
        this.profile = profile;
    }

    public String getUsername() {
        return username;
    }

    public GameProfile getProfile() {
        return profile;
    }

    /** Access token used by the official Minecraft services API (Microsoft / Mojang). */
    public String getToken() {
        return "";
    }
}
