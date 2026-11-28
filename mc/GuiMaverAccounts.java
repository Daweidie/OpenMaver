import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main-menu account manager. Opening through + starts browser authorization exactly once. */
public final class GuiMaverAccounts extends GuiScreen {
    private static final int ADD = 1, BACK = 2, BROWSER = 3, COPY = 4, LOGIN = 5, REMOVE = 6,
            PREVIOUS = 7, NEXT = 8;
    private final GuiScreen parent;
    private final boolean autoStart;
    private final LoginFactory logins;
    private final List<Control> controls = new ArrayList<Control>();
    private MaverAccounts.Store store;
    private MicrosoftAuth.Attempt attempt;
    private String expectedId;
    private String message = "使用微软账号登录 Minecraft Java 正版。";
    private boolean initialized, closed, error;
    private int page;
    private float scale, left, top;

    interface LoginFactory {
        MicrosoftAuth.Attempt login(String clientId);
        MicrosoftAuth.Attempt refresh(String clientId, String refreshToken);
    }

    public GuiMaverAccounts(GuiScreen parent, boolean autoStart) {
        this(parent, autoStart, new LoginFactory() {
            @Override public MicrosoftAuth.Attempt login(String clientId) { return MicrosoftAuth.login(clientId); }
            @Override public MicrosoftAuth.Attempt refresh(String clientId, String refreshToken) {
                return MicrosoftAuth.refresh(clientId, refreshToken);
            }
        });
    }

    GuiMaverAccounts(GuiScreen parent, boolean autoStart, LoginFactory logins) {
        this.parent = parent;
        this.autoStart = autoStart;
        this.logins = logins;
    }

    private static final class Control {
        final int action, x, y, w, h;
        final String label;
        final MaverAccounts.Account account;
        final boolean enabled;

        Control(int action, String label, int x, int y, int w, int h, boolean enabled,
                MaverAccounts.Account account) {
            this.action = action;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.enabled = enabled;
            this.account = account;
        }

        boolean contains(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    @Override public void initGui() {
        buttonList.clear();
        HudFont.useMenuFont();
        closed = false;
        if (!initialized) {
            initialized = true;
            try { store = MaverAccounts.openStore(); }
            catch (IOException | RuntimeException e) {
                message = "无法读取已保存账号；本次仍可登录，但不会覆盖账号文件。";
                error = true;
            }
            if (autoStart) begin(null);
        }
        layout();
    }

    private void begin(MaverAccounts.Account account) {
        if (attempt != null) attempt.cancel();
        expectedId = account == null ? null : account.id;
        error = false;
        message = "";
        attempt = account == null ? logins.login(MaverAccounts.clientId())
                : logins.refresh(account.clientId, account.refreshToken);
    }

    /** GUI-thread boundary: closing or replacing the screen makes late results inert. */
    void pollCompletion() {
        if (closed || attempt == null || !attempt.isDone() || mc == null || mc.currentScreen != this) return;
        MicrosoftAuth.Attempt finished = attempt;
        attempt = null;
        MicrosoftAuth.Result result = finished.result();
        if (result == null) {
            message = finished.error() == null ? "已取消登录。" : finished.error();
            error = finished.error() != null;
            return;
        }
        if (expectedId != null && !expectedId.equals(result.id)) {
            message = "账号身份与保存记录不一致，请重新添加账号。";
            error = true;
            return;
        }
        try {
            MaverAccounts.applySession(mc, result);
        } catch (IOException e) {
            message = e.getMessage();
            error = true;
            return;
        }
        try {
            if (store == null) throw new IOException("Account storage unavailable");
            store.put(result);
        } catch (IOException | RuntimeException e) {
            message = "已登录 " + result.name + "，但未能保存账号；下次需要重新授权。";
            error = true;
            return;
        }
        mc.displayGuiScreen(parent);
    }

    private List<MaverAccounts.Account> accounts() {
        return store == null ? Collections.<MaverAccounts.Account>emptyList() : store.list();
    }

    private void layout() {
        scale = Math.max(0.4F, Math.min(1.25F, Math.min(width / 460.0F, height / 330.0F)));
        left = (width - 420 * scale) / 2;
        top = (height - 300 * scale) / 2;
        controls.clear();
        boolean busy = attempt != null;
        boolean link = busy && attempt.authorizationUrl() != null;
        control(ADD, busy ? "Cancel" : "Add account", 14, 260, 112, 25, true, null);
        control(BROWSER, "Open browser", 132, 260, 96, 25, link, null);
        control(COPY, "Copy link", 234, 260, 90, 25, link, null);
        control(BACK, "Back", 330, 260, 76, 25, true, null);
        List<MaverAccounts.Account> accounts = accounts();
        int pages = Math.max(1, (accounts.size() + 3) / 4);
        page = Math.max(0, Math.min(page, pages - 1));
        if (pages > 1) {
            control(PREVIOUS, "<", 344, 94, 28, 16, page > 0, null);
            control(NEXT, ">", 378, 94, 28, 16, page + 1 < pages, null);
        }
        for (int i = page * 4; i < Math.min(accounts.size(), page * 4 + 4); i++) {
            int y = 116 + (i % 4) * 32;
            MaverAccounts.Account account = accounts.get(i);
            control(LOGIN, "Login", 274, y + 3, 57, 24, !busy, account);
            control(REMOVE, "Remove", 337, y + 3, 69, 24, !busy, account);
        }
    }

    private void control(int action, String label, int x, int y, int w, int h,
            boolean enabled, MaverAccounts.Account account) {
        controls.add(new Control(action, label, px(x), py(y), Math.round(w * scale),
                Math.round(h * scale), enabled, account));
    }

    @Override public void drawScreen(int mx, int my, float partialTicks) {
        pollCompletion();
        if (closed) return;
        HudFont.useMenuFont();
        layout();
        Gui.drawRect(0, 0, width, height, 0xFF10141B);
        box(0, 0, 420, 300, 0xFF1C222D);
        box(0, 0, 420, 2, 0xFF85B6FF);
        text("Microsoft account", 14, 17, 0xFFF4F7FC, 1.3F);
        drawWrapped(attempt == null ? message : attempt.status(), 14, 44, 390,
                error ? 0xFFFF9F9F : 0xFFBFCBDA);
        box(14, 85, 392, 2, 0xFF303B4D);
        if (attempt != null) {
            int offset = (int) ((System.currentTimeMillis() % 1800) * 310 / 1800);
            box(14 + offset, 85, 82, 2, 0xFF85B6FF);
        }
        text("Saved accounts", 14, 98, 0xFF8F9CB1, 0.8F);
        List<MaverAccounts.Account> accounts = accounts();
        if (accounts.isEmpty()) text("添加账号后，会在这里保存登录信息。", 14, 139, 0xFF8391A7, 0.85F);
        String current = mc.getSession() == null ? "" : mc.getSession().getUsername();
        for (int i = page * 4; i < Math.min(accounts.size(), page * 4 + 4); i++) {
            int y = 116 + (i % 4) * 32;
            MaverAccounts.Account account = accounts.get(i);
            box(14, y, 392, 30, 0xFF252E3C);
            text(account.name, 22, y + 10, 0xFFF4F7FC, 0.95F);
            if (account.name.equals(current)) text("Current", 190, y + 10, 0xFF85B6FF, 0.8F);
        }
        if (attempt != null && !message.isEmpty()) text(message, 14, 246, 0xFFBFCBDA, 0.75F);
        for (Control control : controls) {
            boolean hover = control.enabled && control.contains(mx, my);
            int color = !control.enabled ? 0xFF252D3A : hover ? 0xFF466286 : 0xFF34455D;
            if (control.action == REMOVE && hover) color = 0xFF83434B;
            Gui.drawRect(control.x, control.y, control.x + control.w, control.y + control.h, color);
            float size = 0.82F * scale;
            int tw = HudFont.getStringWidth(fontRendererObj, control.label, size);
            HudFont.drawString(fontRendererObj, control.label, control.x + (control.w - tw) / 2,
                    control.y + Math.round(8 * scale), control.enabled ? 0xFFF4F7FC : 0xFF68758A, size);
        }
    }

    @Override protected void mouseClicked(int mx, int my, int button) throws IOException {
        if (button != 0 || closed) return;
        layout();
        for (Control control : controls) {
            if (!control.enabled || !control.contains(mx, my)) continue;
            switch (control.action) {
                case ADD:
                    if (attempt == null) begin(null);
                    else {
                        attempt.cancel();
                        attempt = null;
                        message = "已取消登录。";
                        error = false;
                    }
                    break;
                case BACK: mc.displayGuiScreen(parent); break;
                case BROWSER: if (attempt != null) attempt.openBrowser(); break;
                case COPY:
                    String url = attempt == null ? null : attempt.authorizationUrl();
                    if (url != null) {
                        try {
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
                            message = "登录链接已复制。";
                        } catch (RuntimeException e) {
                            message = "无法访问剪贴板，请点击 Open browser 重试。";
                        }
                    }
                    break;
                case LOGIN: begin(control.account); break;
                case REMOVE:
                    try {
                        store.remove(control.account.id);
                        message = "已移除保存的账号，当前游戏会话仍然有效。";
                        error = false;
                    } catch (IOException | RuntimeException e) {
                        message = "无法移除账号，请检查账号文件是否可写。";
                        error = true;
                    }
                    break;
                case PREVIOUS: page--; break;
                case NEXT: page++; break;
                default: break;
            }
            return;
        }
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent);
    }

    @Override public void onGuiClosed() {
        closed = true;
        if (attempt != null) {
            attempt.cancel();
            attempt = null;
        }
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    private int px(float x) { return Math.round(left + x * scale); }
    private int py(float y) { return Math.round(top + y * scale); }
    private void box(int x, int y, int w, int h, int color) { Gui.drawRect(px(x), py(y), px(x + w), py(y + h), color); }
    private void text(String value, int x, int y, int color, float size) {
        HudFont.drawString(fontRendererObj, value, px(x), py(y), color, size * scale);
    }

    private void drawWrapped(String value, int x, int y, int maxWidth, int color) {
        StringBuilder line = new StringBuilder();
        int row = 0;
        for (int i = 0; i < value.length(); i++) {
            String next = line.toString() + value.charAt(i);
            if (HudFont.getStringWidth(fontRendererObj, next, 0.88F * scale) > maxWidth * scale && line.length() > 0) {
                text(line.toString(), x, y + row * 12, color, 0.88F);
                row++;
                if (row == 3) return;
                line.setLength(0);
            }
            line.append(value.charAt(i));
        }
        text(line.toString(), x, y + row * 12, color, 0.88F);
    }
}
