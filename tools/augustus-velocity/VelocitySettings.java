/** Export reviewable metadata without creating a client or a Velocity session. */
public final class VelocitySettings {
    public static void main(String[] args) {
        System.out.println("config-key\tsource-label\tdefault\tmin\tmax\tdecimals\tchoices");
        for (AugustusVelocity.Option option : AugustusVelocity.options()) {
            String value=option.toggle?Boolean.toString(option.initial>.5):Double.toString(option.initial);
            System.out.println("velocity.augustus."+option.key+"\t"+option.label+"\t"+value+"\t"
                    +option.min+"\t"+option.max+"\t"+option.decimals+"\t"
                    +(option.choices==null?"":String.join("|",option.choices)));
        }
    }
}
