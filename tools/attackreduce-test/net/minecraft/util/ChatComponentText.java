package net.minecraft.util;

/** 本地聊天接收夹具；不引入任何聊天网络包。 */
public final class ChatComponentText {
    private final String text;
    public ChatComponentText(String text) { this.text = text; }
    public String getUnformattedText() { return text; }
}
