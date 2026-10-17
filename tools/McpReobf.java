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

        m(MC, "getMinecraft", "func_71410_x");
        m(MC, "displayGuiScreen", "func_147108_a");
        m(GS, "initGui", "func_73866_w_");
        m(GS, "drawScreen", "func_73863_a");
        m(GS, "drawDefaultBackground", "func_146276_q_");
        m(GS, "keyTyped", "func_73869_a");
        m(GS, "actionPerformed", "func_146284_a");
        m(GS, "doesGuiPauseGame", "func_73868_f");
        m(GS, "mouseClicked", "func_73864_a");
        m(GUI, "drawRect", "func_73734_a");
        m(GUI, "drawCenteredString", "func_73732_a");
        m(GUI, "drawString", "func_73731_b");
        m(FR, "drawStringWithShadow", "func_175063_a");
        m(FR, "getStringWidth", "func_78256_a");
        m(SR, "getScaledWidth", "func_78326_a");
        m(SR, "getScaledHeight", "func_78328_b");

        f(MC, "currentScreen", "field_71462_r");
        f(MC, "fontRendererObj", "field_71466_p");
        f(MC, "theWorld", "field_71441_e");
        f(MC, "thePlayer", "field_71439_g");
        f(GS, "fontRendererObj", "field_146289_q");
        f(GS, "buttonList", "field_146292_n");
        f(GS, "width", "field_146294_l");
        f(GS, "height", "field_146295_m");
        f(GS, "mc", "field_146297_k");
        f(BTN, "id", "field_146127_k");
        f(BTN, "displayString", "field_146126_j");

        SUPER.put("GuiOpenMaverClickGui", GS);
        SUPER.put(GS, GUI);
        SUPER.put(GUI, "java/lang/Object");
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
        out.write(b, cpEnd, b.length - cpEnd);
        return out.toByteArray();
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
