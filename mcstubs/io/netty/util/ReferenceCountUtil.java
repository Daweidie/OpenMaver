package io.netty.util;

/** Compile-time API only; the game supplies the real Netty implementation. */
public final class ReferenceCountUtil {
    public static boolean release(Object value) { return false; }
}
