package net.minecraft.entity.ai.attributes;

import java.util.UUID;

/** 测试替身：与 1.8.9 AttributeModifier 相同的构造签名。 */
public final class AttributeModifier {
    private final UUID id;
    private final String name;
    private final double amount;
    private final int operation;

    public AttributeModifier(UUID id, String name, double amount, int operation) {
        this.id = id;
        this.name = name;
        this.amount = amount;
        this.operation = operation;
    }

    public UUID getID() { return id; }
    public String getName() { return name; }
    public double getAmount() { return amount; }
    public int getOperation() { return operation; }
}
