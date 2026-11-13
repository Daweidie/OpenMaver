package com.mojang.authlib;

import java.util.UUID;

/**
 * Minimal authlib stub used only while compiling {@code mc/}.
 *
 * <p>At runtime the real {@code com.mojang.authlib.GameProfile} bundled with Minecraft 1.8.9
 * is used; this class is not packaged into any jar.
 */
public class GameProfile {
    public GameProfile(UUID id, String name) {
    }

    public UUID getId() {
        return null;
    }

    public String getName() {
        return "";
    }
}
