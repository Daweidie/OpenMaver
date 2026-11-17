package net.minecraft.network;

import java.util.ArrayList;
import java.util.List;

public class NetHandlerPlayClient {
    public final List<Object> sent = new ArrayList<Object>();

    public void addToSendQueue(Object packet) {
        sent.add(packet);
    }
}
