import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 极简 MCP→SRG reobf 工具（Forge 1.8.9）。
 *
 * <p>为什么需要：Forge 1.8.9 运行期用 SRG 名（{@code func_71410_x}/{@code field_71466_p}），
 * 而 javac 编出来的是 MCP 名（{@code getMinecraft}/{@code fontRendererObj}）。
 * 注入到 Forge 客户端的类必须先把**成员名**改成 SRG（类名 MCP 与 SRG 相同，无需改）。
 *
 * <p>实现：解析 class 常量池，把 owner 属于 {@code net/minecraft/*} 的
 * {@code Methodref}/{@code Fieldref} 的成员名替换为 SRG 名（追加新常量，避免影响
 * 本类同名方法，比如我们 override 的 {@code drawScreen}）。
 *
 * <p>映射表只覆盖本骨架用到的成员；新增 MC API 时在此补充即可。
 * （如需完整映射，可换成 MCP 的 {@code methods.csv}/{@code fields.csv}。）
 */
public final class McpReobf {

    /** (owner, MCP 方法名) -> SRG 名 */
    private static final Map<String, String> METHODS = new HashMap<String, String>();
    /** (owner, MCP 字段名) -> SRG 名 */
    private static final Map<String, String> FIELDS = new HashMap<String, String>();
    /** 本工程类的父类链（用于解析继承成员） */
    private static final Map<String, String> SUPER = new HashMap<String, String>();

    private static void m(String owner, String mcp, String srg) {
        METHODS.put(owner + "#" + mcp, srg);
    }

    private static void f(String owner, String mcp, String srg) {
        FIELDS.put(owner + "#" + mcp, srg);
    }

    static {
        final String MC = "net/minecraft/client/Minecraft";
        final String GS = "net/minecraft/client/gui/GuiScreen";
        final String GUI = "net/minecraft/client/gui/Gui";
        final String FR = "net/minecraft/client/gui/FontRenderer";
        final String SR = "net/minecraft/client/gui/ScaledResolution";
        final String BTN = "net/minecraft/client/gui/GuiButton";
        final String ENTITY = "net/minecraft/entity/Entity";
        final String LIVING = "net/minecraft/entity/EntityLivingBase";
        final String WORLD = "net/minecraft/world/World";
        final String BB = "net/minecraft/util/AxisAlignedBB";
        final String RM = "net/minecraft/client/renderer/entity/RenderManager";
        final String GSM = "net/minecraft/client/renderer/GlStateManager";
        final String MOP = "net/minecraft/util/MovingObjectPosition";
        final String ACP = "net/minecraft/client/entity/AbstractClientPlayer";
        final String EOPMP = "net/minecraft/client/entity/EntityOtherPlayerMP";
        final String WCLIENT = "net/minecraft/client/multiplayer/WorldClient";
        final String TM = "net/minecraft/client/renderer/texture/TextureManager";
        final String STACK = "net/minecraft/item/ItemStack";
        final String SESSION = "net/minecraft/util/Session";
        final String DPS = "net/minecraft/client/resources/DefaultPlayerSkin";
        final String MINPUT = "net/minecraft/util/MovementInput";
        final String MIFO = "net/minecraft/util/MovementInputFromOptions";

        m("net/minecraft/util/BlockPos", "getX", "func_177958_n");
        m("net/minecraft/util/BlockPos", "getY", "func_177956_o");
        m("net/minecraft/util/BlockPos", "getZ", "func_177952_p");
        m(MC, "getMinecraft", "func_71410_x");
        m(MC, "displayGuiScreen", "func_147108_a");
        m(MC, "toggleFullscreen", "func_71352_k");
        m(MC, "getSession", "func_110432_I");
        m(MC, "shutdown", "func_71400_g");
        m(SESSION, "getUsername", "func_111285_a");
        m(SESSION, "getProfile", "func_148256_e");
        m(SESSION, "getToken", "func_148254_d");
        m("net/minecraft/client/renderer/texture/TextureManager", "deleteTexture", "func_147645_c");
        m(DPS, "getDefaultSkin", "func_177334_a");
        m(GS, "initGui", "func_73866_w_");
        m(GS, "drawScreen", "func_73863_a");
        m(GS, "drawDefaultBackground", "func_146276_q_");
        m(GS, "keyTyped", "func_73869_a");
        m(GS, "actionPerformed", "func_146284_a");
        m(GS, "doesGuiPauseGame", "func_73868_f");
        m(GS, "mouseClicked", "func_73864_a");
        m(GS, "handleMouseInput", "func_146274_d");
        m(GS, "onGuiClosed", "func_146281_b");
        m(BTN, "drawButton", "func_146112_a");
        m(GUI, "drawRect", "func_73734_a");
        m(GUI, "drawModalRectWithCustomSizedTexture", "func_146110_a");
        m(GUI, "drawCenteredString", "func_73732_a");
        m(GUI, "drawString", "func_73731_b");
        m(FR, "drawString", "func_78276_b");
        m(FR, "drawStringWithShadow", "func_175063_a");
        m(FR, "getStringWidth", "func_78256_a");
        m(SR, "getScaledWidth", "func_78326_a");
        m(SR, "getScaledHeight", "func_78328_b");
        m(SR, "getScaleFactor", "func_78325_e");
        m(MC, "getRenderManager", "func_175598_ae");
        m(ENTITY, "getDistanceSqToEntity", "func_70068_e");
        m(ENTITY, "getDistanceToEntity", "func_70032_d");
        m(ENTITY, "getName", "func_70005_c_");
        m(ENTITY, "isInvisibleToPlayer", "func_98034_c");
        m(ENTITY, "isSneaking", "func_70093_af");
        m(ENTITY, "getEntityBoundingBox", "func_174813_aQ");
        m(ENTITY, "getEntityId", "func_145782_y");
        m(ENTITY, "setEntityId", "func_145769_d");
        m(ENTITY, "setLocationAndAngles", "func_70012_b");
        m(ENTITY, "setCustomNameTag", "func_96094_a");
        m(ENTITY, "setAlwaysRenderNameTag", "func_174805_g");
        m(WCLIENT, "addEntityToWorld", "func_73027_a");
        m(WCLIENT, "removeEntityFromWorld", "func_73028_b");
        m(LIVING, "isEntityAlive", "func_70089_S");
        m(LIVING, "getEyeHeight", "func_70047_e");
        m(LIVING, "getHealth", "func_110143_aJ");
        m(LIVING, "setHealth", "func_70606_j");
        m(LIVING, "getMaxHealth", "func_110138_aP");
        m(LIVING, "getTotalArmorValue", "func_70658_aO");
        m(LIVING, "getHeldItem", "func_70694_bm");
        m(LIVING, "canEntityBeSeen", "func_70685_l");
        // FullBright：isPotionActive 这里只使用 int 重载（Potion 重载未调用）。
        m(LIVING, "isPotionActive", "func_82165_m");
        m(LIVING, "addPotionEffect", "func_70690_d");
        m(LIVING, "removePotionEffect", "func_82170_o");
        m(LIVING, "getActivePotionEffects", "func_70651_bq");
        m("net/minecraft/potion/PotionEffect", "getPotionID", "func_76456_a");
        m("net/minecraft/potion/PotionEffect", "getDuration", "func_76459_b");
        m("net/minecraft/potion/PotionEffect", "getAmplifier", "func_76458_c");
        m("net/minecraft/potion/PotionEffect", "getIsPotionDurationMax", "func_100011_g");
        f("net/minecraft/potion/Potion", "potionTypes", "field_76425_a");
        m("net/minecraft/potion/Potion", "getName", "func_76393_a");
        m("net/minecraft/potion/Potion", "getLiquidColor", "func_76401_j");
        m("net/minecraft/potion/Potion", "hasStatusIcon", "func_76400_d");
        m("net/minecraft/potion/Potion", "getStatusIconIndex", "func_76392_e");
        m("net/minecraft/client/resources/I18n", "format", "func_135052_a");
        m(MC, "getTextureManager", "func_110434_K");
        m(ACP, "getLocationSkin", "func_110306_p");
        m(TM, "bindTexture", "func_110577_a");
        m(TM, "loadTexture", "func_110579_a");
        m(STACK, "getDisplayName", "func_82833_r");
        m(STACK, "getUnlocalizedName", "func_77977_a");
        m(STACK, "getMaxDamage", "func_77658_a");
        m(STACK, "getItemDamage", "func_77960_j");
        m(GSM, "color", "func_179131_c");
        m(GSM, "bindTexture", "func_179144_i");
        m(GSM, "setActiveTexture", "func_179138_g");
        m(GSM, "enableDepth", "func_179126_j");
        m(GSM, "depthMask", "func_179132_a");
        m(GSM, "depthFunc", "func_179143_c");
        m(GSM, "enableAlpha", "func_179141_d");
        m(GSM, "alphaFunc", "func_179092_a");
        m(GSM, "enableBlend", "func_179147_l");
        m(GSM, "blendFunc", "func_179112_b");
        m(GSM, "enableTexture2D", "func_179098_w");
        m(GSM, "enableCull", "func_179089_o");
        m(GSM, "colorMask", "func_179135_a");

        f(MC, "currentScreen", "field_71462_r");
        f(MC, "renderManager", "field_175616_W");
        f(MC, "fontRendererObj", "field_71466_p");
        f(MC, "theWorld", "field_71441_e");
        f(MC, "thePlayer", "field_71439_g");
        f(MC, "objectMouseOver", "field_71476_x");
        f(MC, "gameSettings", "field_71474_y");
        f(GS, "fontRendererObj", "field_146289_q");
        f(GS, "buttonList", "field_146292_n");
        f(GS, "width", "field_146294_l");
        f(GS, "height", "field_146295_m");
        f(GS, "mc", "field_146297_k");
        f(BTN, "id", "field_146127_k");
        f(BTN, "displayString", "field_146126_j");
        f(BTN, "xPosition", "field_146128_h");
        f(BTN, "yPosition", "field_146129_i");
        f(BTN, "width", "field_146120_f");
        f(BTN, "height", "field_146121_g");
        f(BTN, "visible", "field_146125_m");
        f(BTN, "enabled", "field_146124_l");
        f(WORLD, "loadedEntityList", "field_72996_f");
        f(ENTITY, "posX", "field_70165_t");
        f(ENTITY, "posY", "field_70163_u");
        f(ENTITY, "posZ", "field_70161_v");
        f(ENTITY, "motionX", "field_70159_w");
        f(ENTITY, "motionY", "field_70181_x");
        f(ENTITY, "motionZ", "field_70179_y");
        f(ENTITY, "height", "field_70131_O");
        f(ENTITY, "rotationYaw", "field_70177_z");
        f(ENTITY, "rotationPitch", "field_70125_A");
        f(BB, "minX", "field_72340_a");
        f(BB, "minY", "field_72338_b");
        f(BB, "minZ", "field_72339_c");
        f(BB, "maxX", "field_72336_d");
        f(BB, "maxY", "field_72337_e");
        f(BB, "maxZ", "field_72334_f");
        f(RM, "viewerPosX", "field_78725_b");
        f(RM, "viewerPosY", "field_78726_c");
        f(RM, "viewerPosZ", "field_78723_d");
        f(RM, "playerViewY", "field_78735_i");
        f(RM, "playerViewX", "field_78732_j");
        f("net/minecraft/client/settings/GameSettings", "hideGUI", "field_74319_N");
        f("net/minecraft/client/settings/GameSettings", "thirdPersonView", "field_74320_O");
        f(MOP, "typeOfHit", "field_72313_a");
        f(MOP, "entityHit", "field_72308_g");
        m(MIFO, "updatePlayerMoveState", "func_78898_a");
        // Forge 1.8.9: beu/a:F = strafe, beu/c:Z = jump.
        // field_78903_e belongs to MovementInputFromOptions.gameSettings, not jump.
        f(MINPUT, "moveForward", "field_78900_b");
        f(MINPUT, "moveStrafe", "field_78902_a");
        f(MINPUT, "jump", "field_78901_c");
        f(MINPUT, "sneak", "field_78899_d");

        SUPER.put("GuiOpenMaverClickGui", GS);
        SUPER.put("ScaffoldMovementInput", MIFO);
        SUPER.put(MIFO, MINPUT);
        SUPER.put("GuiMaverMainMenu", "net/minecraft/client/gui/GuiMainMenu");
        SUPER.put("net/minecraft/client/gui/GuiMainMenu", GS);
        SUPER.put("GuiMaverAccounts", GS);
        SUPER.put(GS, GUI);
        SUPER.put(GUI, "java/lang/Object");
        SUPER.put(LIVING, ENTITY);
        SUPER.put("net/minecraft/entity/player/EntityPlayer", LIVING);
        SUPER.put(ACP, "net/minecraft/entity/player/EntityPlayer");
        SUPER.put(EOPMP, ACP);
        SUPER.put(WCLIENT, WORLD);
    }

    private McpReobf() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法: McpReobf <inputDir> <outputDir>");
            System.exit(2);
        }
        final Path in = Paths.get(args[0]);
        final Path out = Paths.get(args[1]);
        if (Files.exists(out)) {
            try (Stream<Path> s = Files.walk(out)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        Files.createDirectories(out);

        final int[] count = {0};
        List<Path> classes = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(in)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(classes::add);
        }
        for (Path p : classes) {
            byte[] data = Files.readAllBytes(p);
            byte[] result = transform(data);
            Path dest = out.resolve(in.relativize(p).toString());
            Files.createDirectories(dest.getParent());
            Files.write(dest, result);
            count[0]++;
        }
        System.out.println("[McpReobf] reobf " + count[0] + " 个类 -> " + out);
    }

    // ==================================================================
    // 常量池解析与重写
    // ==================================================================

    private static byte[] transform(byte[] b) throws IOException {
        int p = 0;
        if (u4(b, p) != 0xCAFEBABEL) {
            throw new IllegalArgumentException("不是 class 文件");
        }
        p += 8; // magic(4) + minor(2) + major(2)
        int cpCount = u2(b, p);
        p += 2;

        int[] tags = new int[cpCount];
        byte[][] raw = new byte[cpCount][];
        String[] utf8 = new String[cpCount];
        int[] classUtf = new int[cpCount];          // Class -> name UTF8 index
        int[] natName = new int[cpCount];            // NameAndType -> name index
        int[] natDesc = new int[cpCount];            // NameAndType -> desc index
        int[] refClass = new int[cpCount];           // ref -> class index
        int[] refNat = new int[cpCount];             // ref -> NameAndType index

        for (int i = 1; i < cpCount; i++) {
            int tag = b[p] & 0xFF;
            int start = p;
            tags[i] = tag;
            switch (tag) {
                case 1: { // UTF8
                    int len = u2(b, p + 1);
                    p += 3 + len;
                    raw[i] = slice(b, start, p);
                    utf8[i] = new String(b, start + 3, len, java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
                case 3: // Integer
                case 4: // Float
                    p += 5;
                    raw[i] = slice(b, start, p);
                    break;
                case 5: // Long
                case 6: // Double
                    p += 9;
                    raw[i] = slice(b, start, p);
                    i++; // 占两个槽
                    break;
                case 7: // Class
                    classUtf[i] = u2(b, p + 1);
                    p += 3;
                    raw[i] = slice(b, start, p);
                    break;
                case 8: // String
                case 16: // MethodType
                    p += 3;
                    raw[i] = slice(b, start, p);
                    break;
                case 15: // MethodHandle
                    p += 4;
                    raw[i] = slice(b, start, p);
                    break;
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                    refClass[i] = u2(b, p + 1);
                    refNat[i] = u2(b, p + 3);
                    p += 5;
                    raw[i] = slice(b, start, p);
                    break;
                case 12: // NameAndType
                    natName[i] = u2(b, p + 1);
                    natDesc[i] = u2(b, p + 3);
                    p += 5;
                    raw[i] = slice(b, start, p);
                    break;
                case 18: // InvokeDynamic
                    p += 5;
                    raw[i] = slice(b, start, p);
                    break;
                case 17: // Dynamic
                case 19: // Module
                case 20: // Package
                    p += 5;
                    raw[i] = slice(b, start, p);
                    break;
                default:
                    throw new IllegalStateException("未知常量池 tag=" + tag + " @ " + i);
            }
        }
        int cpEnd = p; // 常量池结束，后面就是 access_flags...

        List<byte[]> appended = new ArrayList<byte[]>();
        int nextIndex = cpCount;

        // 遍历引用，收集需要改名的 (refIndex, newSrgName)
        for (int i = 1; i < cpCount; i++) {
            int tag = tags[i];
            if (tag != 9 && tag != 10 && tag != 11) {
                continue;
            }
            String owner = utf8[classUtf[refClass[i]]];
            String name = utf8[natName[refNat[i]]];
            if (owner == null || name == null) {
                continue;
            }

            // 沿继承链找声明该成员的 owner（覆盖 this.继承成员 的情况）
            String srg = null;
            String cur = owner;
            for (int guard = 0; guard < 8 && cur != null; guard++) {
                String candidate = (tag == 9) ? FIELDS.get(cur + "#" + name)
                                              : METHODS.get(cur + "#" + name);
                if (candidate != null) {
                    srg = candidate;
                    break;
                }
                cur = SUPER.get(cur);
            }
            if (srg == null || srg.equals(name)) {
                continue;
            }

            // 追加 UTF8(srg) + 新 NameAndType(name=新UTF8, desc=原desc)，并指向新 NAT
            int srgUtf = nextIndex++;
            appended.add(utf8Entry(srg));
            int newNat = nextIndex++;
            appended.add(natEntry(srgUtf, natDesc[refNat[i]]));

            // 改写该 ref 的 nat 索引
            byte[] r = raw[i].clone();
            r[3] = (byte) ((newNat >> 8) & 0xFF);
            r[4] = (byte) (newNat & 0xFF);
            raw[i] = r;
        }

        // 本类自己声明的 override 方法也必须改名。只改引用不够：
        // javac 产物里的方法声明仍是 MCP 名（如 drawScreen），而运行期父类是
        // SRG 名（func_73863_a），不改声明就不会真正覆盖，结果就是 GUI 的
        // 界面/点击/按键全部走父类空实现（只剩一个鼠标指针）。
        byte[] body = slice(b, cpEnd, b.length);
        int p2 = 0;
        p2 += 2; // access_flags
        int thisClassIndex = u2(body, p2);
        p2 += 2;
        String thisName = utf8[classUtf[thisClassIndex]];
        p2 += 2; // super_class
        int interfaceCount = u2(body, p2);
        p2 += 2 + interfaceCount * 2;

        int fieldCount = u2(body, p2);
        p2 += 2;
        for (int i = 0; i < fieldCount; i++) {
            p2 += 6; // access_flags + name_index + descriptor_index
            p2 = skipAttributes(body, p2);
        }

        int methodCount = u2(body, p2);
        p2 += 2;
        for (int i = 0; i < methodCount; i++) {
            int accessFlags = u2(body, p2);
            int nameOffset = p2 + 2;
            int nameIndex = u2(body, nameOffset);
            p2 += 6; // access_flags + name_index + descriptor_index
            p2 = skipAttributes(body, p2);

            String name = utf8[nameIndex];
            if (name == null || name.startsWith("<")
                    || (accessFlags & 0x0002) != 0   // private 不能 override
                    || (accessFlags & 0x0008) != 0) { // static 不是 override
                continue;
            }
            String srg = findSrgMethod(thisName, name);
            if (srg == null || srg.equals(name)) {
                continue;
            }
            int srgUtf = nextIndex++;
            appended.add(utf8Entry(srg));
            body[nameOffset] = (byte) ((srgUtf >> 8) & 0xFF);
            body[nameOffset + 1] = (byte) (srgUtf & 0xFF);
        }

        // 组装输出
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(b, 0, 8); // magic + minor + major
        int newCount = nextIndex;
        out.write((newCount >> 8) & 0xFF);
        out.write(newCount & 0xFF);
        for (int i = 1; i < cpCount; i++) {
            if (raw[i] != null) {
                out.write(raw[i]);
            }
        }
        for (byte[] entry : appended) {
            out.write(entry);
        }
        out.write(body);
        return out.toByteArray();
    }

    /** 沿本工程的 SUPER 链查 MCP 方法名对应的 SRG 名。 */
    private static String findSrgMethod(String className, String methodName) {
        String cur = className;
        for (int guard = 0; guard < 8 && cur != null; guard++) {
            String srg = METHODS.get(cur + "#" + methodName);
            if (srg != null) {
                return srg;
            }
            cur = SUPER.get(cur);
        }
        return null;
    }

    /** 跳过 attributes 表，返回下一个字段/方法/类属性的起始偏移。 */
    private static int skipAttributes(byte[] data, int offset) {
        int count = u2(data, offset);
        int p = offset + 2;
        for (int i = 0; i < count; i++) {
            int length = (int) u4(data, p + 2);
            p += 6 + length;
        }
        return p;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u4(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    private static byte[] utf8Entry(String s) {
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] r = new byte[3 + data.length];
        r[0] = 1;
        r[1] = (byte) ((data.length >> 8) & 0xFF);
        r[2] = (byte) (data.length & 0xFF);
        System.arraycopy(data, 0, r, 3, data.length);
        return r;
    }

    private static byte[] natEntry(int nameIndex, int descIndex) {
        return new byte[] {
            12,
            (byte) ((nameIndex >> 8) & 0xFF), (byte) (nameIndex & 0xFF),
            (byte) ((descIndex >> 8) & 0xFF), (byte) (descIndex & 0xFF),
        };
    }
}
