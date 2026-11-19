package com.bgmext.gui;

import com.bgmext.BgmExtension;
import com.bgmext.MusicCategory;
import com.bgmext.config.MusicConfig;
import com.bgmext.playback.MusicPlayback;
import com.bgmext.scanner.MusicLibrary;
import com.bgmext.scanner.Track;
import com.bgmext.vanilla.VanillaMusicRegistry;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 音乐设置界面。
 *
 * 列表完全手动绘制（不用子 widget），因此可以自由裁剪、滚动、折叠，
 * 也不会出现 widget 互相重叠抢点击的问题。
 *
 * 交互：
 * - 分组（原版音乐 / 自定义音乐、以及每个原版条目）可折叠
 * - 原版条目行右侧的小方块 = 整体开关；行主体 = 折叠/展开
 * - 右侧滚动条可拖动
 */
public class MusicSettingsScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int HEADER_H = 18;
    private static final int TAB_W = 92;
    private static final int TAB_H = 20;
    private static final int TAB_GAP = 4;
    private static final int DELETE_ZONE = 26;
    private static final int CHIP_ZONE = 22;
    private static final int TRIANGLE_ZONE = 16;
    private static final int SCROLLBAR_W = 8;

    private MusicCategory currentCategory = MusicCategory.GAME;
    private double scroll = 0;
    private boolean draggingScrollbar = false;

    private int listX, listY, listW, listH;

    private final List<Row> rows = new ArrayList<>();
    private int contentHeight = 0;

    /** 已展开的原版条目；不在集合里 = 折叠（默认折叠，否则列表太长） */
    private final Set<ResourceLocation> expandedEvents = new HashSet<>();
    private boolean vanillaSectionCollapsed = false;
    private boolean customSectionCollapsed = false;

    /** 全局去抖：某些模组包会重复派发鼠标事件，必须放在 mouseClicked 最顶端 */
    private long lastGlobalClickMs = 0L;
    private double lastGlobalClickX = -1;
    private double lastGlobalClickY = -1;

    public MusicSettingsScreen() {
        super(Component.literal("\u266a \u97f3\u4e50\u8bbe\u7f6e"));
        MusicConfig.load();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();

        int tabX = 12;
        int tabY = 32;
        for (MusicCategory cat : MusicCategory.values()) {
            final MusicCategory target = cat;
            addRenderableWidget(Button.builder(
                    Component.literal(cat.getDirName()),
                    b -> {
                        currentCategory = target;
                        scroll = 0;
                        rebuildRows();
                    }
            ).pos(tabX, tabY).size(TAB_W, TAB_H).build());
            tabY += TAB_H + TAB_GAP;
        }

        int bottomY = this.height - 28;

        addRenderableWidget(Button.builder(
                Component.literal("\u25b6 \u7acb\u5373\u64ad\u653e"),
                b -> playNow()
        ).pos(12, bottomY - 52).size(TAB_W, TAB_H)
         .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                 Component.literal("\u4ece\u5f53\u524d\u5206\u7c7b\u968f\u673a\u62bd\u4e00\u9996\uff0c\u7acb\u523b\u64ad\u653e")))
         .build());

        addRenderableWidget(Button.builder(
                Component.literal("\u6253\u5f00\u6587\u4ef6\u5939"),
                b -> openMusicFolder()
        ).pos(12, bottomY - 26).size(TAB_W, TAB_H)
         .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                 Component.literal("\u5728\u7cfb\u7edf\u6587\u4ef6\u7ba1\u7406\u5668\u4e2d\u6253\u5f00\u5f53\u524d\u5206\u7c7b\u7684\u97f3\u4e50\u6587\u4ef6\u5939")))
         .build());

        addRenderableWidget(Button.builder(
                Component.literal("\u91cd\u65b0\u626b\u63cf"),
                b -> {
                    MusicLibrary.reload();
                    VanillaMusicRegistry.invalidate();
                    rebuildRows();
                }
        ).pos(12, bottomY).size(TAB_W, TAB_H).build());

        addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE,
                b -> this.onClose()
        ).pos(this.width / 2 - 50, bottomY).size(100, TAB_H).build());

        addRenderableWidget(new VolumeSlider(
                this.width / 2 + 58, bottomY, 132, TAB_H, MusicConfig.getCustomVolumeDb()));

        this.listX = 12 + TAB_W + 16;
        this.listY = 30;
        this.listW = Math.max(120, this.width - this.listX - 18 - SCROLLBAR_W);
        this.listH = Math.max(60, bottomY - 8 - this.listY);

        rebuildRows();
    }

    @Override
    public void onClose() {
        MusicConfig.save();
        super.onClose();
    }

    // ------------------------------------------------------------------
    // 数据 → 行
    // ------------------------------------------------------------------

    private void rebuildRows() {
        rows.clear();

        // ---- 原版音乐 ----
        List<ResourceLocation> events = VanillaMusicRegistry.eventsFor(currentCategory);
        int vanillaFiles = 0;
        for (ResourceLocation id : events) {
            vanillaFiles += VanillaMusicRegistry.filesOf(id).size();
        }
        rows.add(Row.section(RowKind.HEADER_VANILLA,
                "\u539f\u7248\u97f3\u4e50", events.size(), vanillaFiles));

        if (!vanillaSectionCollapsed) {
            if (events.isEmpty()) {
                rows.add(Row.hint("\uff08\u672a\u8bfb\u5230\u539f\u7248\u97f3\u4e50\u6ce8\u518c\u8868\uff09"));
            }
            for (ResourceLocation eventId : events) {
                rows.add(Row.event(eventId));
                if (expandedEvents.contains(eventId)) {
                    for (ResourceLocation file : VanillaMusicRegistry.filesOf(eventId)) {
                        rows.add(Row.vanillaFile(eventId, file));
                    }
                }
            }
        }

        // ---- 自定义音乐 ----
        List<Track> customs = new ArrayList<>(MusicLibrary.getTracks(currentCategory));
        rows.add(Row.section(RowKind.HEADER_CUSTOM,
                "\u81ea\u5b9a\u4e49\u97f3\u4e50", customs.size(), 0));

        if (!customSectionCollapsed) {
            if (customs.isEmpty()) {
                rows.add(Row.hint("\uff08\u6b64\u5206\u7c7b\u4e0b\u6ca1\u6709\u81ea\u5b9a\u4e49\u97f3\u4e50\uff09"));
            } else {
                for (Track t : customs) {
                    rows.add(Row.custom(t));
                }
            }
        }

        int y = 0;
        for (Row r : rows) {
            r.contentY = y;
            y += r.height();
        }
        contentHeight = y;
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private double maxScroll() {
        return Math.max(0, contentHeight - listH);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int tabY = 32;
        for (MusicCategory cat : MusicCategory.values()) {
            if (cat == currentCategory) {
                g.fill(11, tabY - 1, 13 + TAB_W, tabY + TAB_H + 1, 0x50FFD700);
            }
            tabY += TAB_H + TAB_GAP;
        }

        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        String status = MusicPlayback.statusText();
        if (status != null) {
            int color = status.startsWith("\u25b6") ? 0xFF7CFC7C : 0xFFFFAA55;
            g.drawString(this.font, status, listX, 20, color, false);
        }

        renderList(g, mouseX, mouseY);
        renderScrollbar(g, mouseX, mouseY);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        g.pose().pushPose();
        g.pose().translate(0f, (float) (listY - scroll), 0f);

        boolean mouseInList = isMouseInList(mouseX, mouseY);

        for (Row r : rows) {
            if (r.contentY + r.height() < scroll || r.contentY > scroll + listH) continue;

            switch (r.kind) {
                case HEADER_VANILLA, HEADER_CUSTOM -> {
                    boolean collapsed = (r.kind == RowKind.HEADER_VANILLA)
                            ? vanillaSectionCollapsed : customSectionCollapsed;
                    String arrow = collapsed ? "\u25b6 " : "\u25bc ";
                    String label = arrow + r.text;
                    int color = (r.kind == RowKind.HEADER_VANILLA) ? 0xFFCCCCCC : 0xFFCCCCCC;
                    g.drawString(this.font, label, listX + 4, r.contentY + 5, color, false);

                    String count = r.subCount > 0
                            ? "(" + r.subCount + (r.fileCount > 0 ? " \u6761 / " + r.fileCount + " \u9996" : " \u9996") + ")"
                            : "";
                    if (!count.isEmpty()) {
                        g.drawString(this.font, count,
                                listX + 8 + this.font.width(label), r.contentY + 5, 0xFF888888, false);
                    }
                    g.fill(listX, r.contentY + r.height() - 2, listX + listW, r.contentY + r.height() - 1,
                            0x30FFFFFF);
                }
                case HINT -> g.drawString(this.font, r.text, listX + 22, r.contentY + 4,
                        0xFF777777, false);
                case EVENT -> drawEventRow(g, r, mouseInList, mouseX, mouseY);
                case VANILLA_FILE -> {
                    boolean on = MusicConfig.isVanillaEnabled(r.file);
                    drawToggleRow(g, r.contentY, "      " + VanillaMusicRegistry.shortName(r.file),
                            null, on, null, mouseInList, mouseX, mouseY, false);
                }
                case CUSTOM -> {
                    boolean on = MusicConfig.isCustomEnabled(currentCategory, r.track.getFileName());
                    drawToggleRow(g, r.contentY, r.track.getDisplayTitle(),
                            r.track.getDisplaySubtitle(), on,
                            r.track.getFormattedDuration(), mouseInList, mouseX, mouseY, true);
                }
            }
        }

        g.pose().popPose();
        g.disableScissor();
    }

    private void drawEventRow(GuiGraphics g, Row r, boolean mouseInList, int mouseX, int mouseY) {
        int y = r.contentY;
        boolean rowHover = mouseInList && isRowHovered(y, mouseX, mouseY);
        boolean expanded = expandedEvents.contains(r.eventId);
        boolean all = r.allEventFilesEnabled();

        g.fill(listX, y, listX + listW, y + ROW_H - 2, rowHover ? 0x28FFFFFF : 0x14FFFFFF);

        String arrow = expanded ? "\u25bc" : "\u25b6";
        g.drawString(this.font, arrow, listX + 4, y + 6, 0xFFAAAAAA, false);

        String name = r.eventId.getPath();
        g.drawString(this.font, name, listX + TRIANGLE_ZONE + 2, y + 6, 0xFFFFFFFF, false);

        String count = "(" + r.fileCount + ")";
        g.drawString(this.font, count, listX + TRIANGLE_ZONE + 6 + this.font.width(name), y + 6,
                0xFF888888, false);

        // 右侧小方块：整体开关
        int chipX = listX + listW - CHIP_ZONE - 2;
        boolean chipHover = mouseInList && rowHover && mouseX >= chipX;
        int chipColor = all ? 0x6000AA00 : 0x60AA0000;
        g.fill(chipX, y + 2, listX + listW - 2, y + ROW_H - 4,
                chipHover ? (chipColor | 0x30000000) : chipColor);
        g.drawCenteredString(this.font, all ? "\u2714" : "\u2718",
                chipX + (CHIP_ZONE) / 2, y + 6, all ? 0xFF55FF55 : 0xFFFF5555);
    }

    private void drawToggleRow(GuiGraphics g, int y, String label, String subtitle, boolean enabled,
                               String rightText, boolean mouseInList,
                               int mouseX, int mouseY, boolean withDelete) {
        int barW = listW - (withDelete ? DELETE_ZONE : 0);
        boolean rowHover = mouseInList && isRowHovered(y, mouseX, mouseY);

        int bg = (rowHover && mouseX < listX + barW) ? 0x30FFFFFF : 0x18FFFFFF;
        g.fill(listX, y, listX + barW - 2, y + ROW_H - 2, bg);

        int color = enabled ? 0xFF55FF55 : 0xFFFF5555;
        g.drawString(this.font, enabled ? "\u2714" : "\u2718", listX + 4, y + 6, color, false);

        int reserved = (rightText != null) ? this.font.width(rightText) + 12 : 0;
        int textX = listX + 16;
        int textMaxX = listX + barW - 8 - reserved;

        String shownLabel = label;
        String shownSub = subtitle;
        if (shownSub != null && textX + this.font.width(shownLabel + " \u00b7 " + shownSub) > textMaxX) {
            shownSub = null;
        }
        int labelW = this.font.width(shownLabel);
        if (textX + labelW > textMaxX) {
            int avail = Math.max(8, textMaxX - textX);
            shownLabel = this.font.plainSubstrByWidth(shownLabel, avail - this.font.width("\u2026"))
                    + "\u2026";
            labelW = this.font.width(shownLabel);
        }

        g.drawString(this.font, shownLabel, textX, y + 6, 0xFFFFFF, false);
        if (shownSub != null) {
            g.drawString(this.font, "\u00b7 " + shownSub, textX + labelW + 4, y + 6, 0x99A8B8, false);
        }

        if (rightText != null) {
            int w = this.font.width(rightText);
            g.drawString(this.font, rightText, listX + barW - 10 - w, y + 6, 0x888888, false);
        }

        if (withDelete) {
            int dx = listX + listW - DELETE_ZONE + 2;
            boolean delHover = mouseInList && rowHover && mouseX >= dx;
            g.fill(dx, y, listX + listW, y + ROW_H - 2, delHover ? 0x70FF0000 : 0x30FF0000);
            g.drawCenteredString(this.font, "\u2715", dx + (DELETE_ZONE - 2) / 2, y + 6, 0xFFFFFF);
        }
    }

    private boolean isMouseInList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX <= listX + listW
                && mouseY >= listY && mouseY <= listY + listH;
    }

    private boolean isRowHovered(int contentY, int mouseX, int mouseY) {
        double cy = mouseY - listY + scroll;
        return cy >= contentY && cy < contentY + ROW_H - 2
                && mouseX >= listX && mouseX <= listX + listW;
    }

    // ---- 滚动条 ----

    private int scrollbarX() {
        return listX + listW + 2;
    }

    private int thumbHeight() {
        if (contentHeight <= 0) return listH;
        return Math.max(20, (int) ((double) listH * listH / contentHeight));
    }

    private int thumbY() {
        double max = maxScroll();
        if (max <= 0) return listY;
        int thumbH = thumbHeight();
        return listY + (int) ((listH - thumbH) * (scroll / max));
    }

    private void renderScrollbar(GuiGraphics g, int mouseX, int mouseY) {
        int x = scrollbarX();
        g.fill(x, listY, x + SCROLLBAR_W, listY + listH, 0x22FFFFFF);

        if (maxScroll() <= 0) return;

        int thumbY = thumbY();
        int thumbH = thumbHeight();
        boolean hot = draggingScrollbar
                || (mouseX >= x - 2 && mouseX <= x + SCROLLBAR_W + 2
                        && mouseY >= thumbY && mouseY <= thumbY + thumbH);
        g.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, hot ? 0xCCFFFFFF : 0x80FFFFFF);
    }

    /** 把鼠标位置映射成滚动位置（按滑块中心跟随鼠标） */
    private void scrollFromMouse(double mouseY) {
        double max = maxScroll();
        if (max <= 0) return;
        int thumbH = thumbHeight();
        double span = Math.max(1, listH - thumbH);
        double rel = (mouseY - listY - thumbH / 2.0) / span;
        scroll = Mth.clamp(rel * max, 0, max);
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isMouseInList(mouseX, mouseY)
                || (mouseX >= scrollbarX() && mouseX <= scrollbarX() + SCROLLBAR_W
                        && mouseY >= listY && mouseY <= listY + listH)) {
            scroll = Mth.clamp(scroll - delta * ROW_H * 1.5, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            long now = System.currentTimeMillis();
            boolean sameSpot = Math.abs(mouseX - lastGlobalClickX) < 2.0
                    && Math.abs(mouseY - lastGlobalClickY) < 2.0;
            if (sameSpot && now - lastGlobalClickMs < 250L) {
                return true;
            }
            lastGlobalClickMs = now;
            lastGlobalClickX = mouseX;
            lastGlobalClickY = mouseY;

            // 滚动条：按下即开始拖动
            if (mouseX >= scrollbarX() - 2 && mouseX <= scrollbarX() + SCROLLBAR_W + 2
                    && mouseY >= listY && mouseY <= listY + listH) {
                draggingScrollbar = true;
                scrollFromMouse(mouseY);
                return true;
            }
        }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) return false;
        if (!isMouseInList(mouseX, mouseY)) return false;

        double cy = mouseY - listY + scroll;
        for (Row r : rows) {
            if (cy < r.contentY || cy >= r.contentY + r.height()) continue;

            switch (r.kind) {
                case HEADER_VANILLA -> {
                    vanillaSectionCollapsed = !vanillaSectionCollapsed;
                    rebuildRows();
                    return true;
                }
                case HEADER_CUSTOM -> {
                    customSectionCollapsed = !customSectionCollapsed;
                    rebuildRows();
                    return true;
                }
                case EVENT -> {
                    int chipX = listX + listW - CHIP_ZONE - 2;
                    if (mouseX >= chipX) {
                        // 右侧小方块：整体开关
                        boolean all = r.allEventFilesEnabled();
                        for (ResourceLocation file : VanillaMusicRegistry.filesOf(r.eventId)) {
                            MusicConfig.setVanillaEnabled(currentCategory.getDirName(), file, !all);
                        }
                        BgmExtension.LOGGER.info("[bgmext] 原版条目 {} 整体 enabled={}",
                                r.eventId, !all);
                    } else {
                        // 行主体：折叠 / 展开
                        if (!expandedEvents.remove(r.eventId)) {
                            expandedEvents.add(r.eventId);
                        }
                    }
                    rebuildRows();
                    return true;
                }
                case VANILLA_FILE -> {
                    boolean on = MusicConfig.isVanillaEnabled(r.file);
                    MusicConfig.setVanillaEnabled(currentCategory.getDirName(), r.file, !on);
                    BgmExtension.LOGGER.info("[bgmext] 原版文件 {} enabled={}", r.file, !on);
                    return true;
                }
                case CUSTOM -> {
                    int dx = listX + listW - DELETE_ZONE;
                    if (mouseX >= dx) {
                        confirmDelete(r.track);
                    } else {
                        boolean on = MusicConfig.isCustomEnabled(currentCategory, r.track.getFileName());
                        MusicConfig.setCustomEnabled(currentCategory, r.track.getFileName(), !on);
                        BgmExtension.LOGGER.info("[bgmext] 自定义 {} enabled={}",
                                r.track.getFileName(), !on);
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 「立即播放」：从当前分类随机抽一首，立刻播放 */
    private void playNow() {
        if (this.minecraft == null) return;
        boolean ok = MusicPlayback.playRandomNow(this.minecraft, currentCategory);
        BgmExtension.LOGGER.info("[bgmext] 立即播放 (分类 {}): {}",
                currentCategory.getDirName(), ok ? "已开始" : "未播放（见上方日志）");
    }

    /** 在系统文件管理器里打开「当前分类」的音乐文件夹（不存在就先建出来） */
    private void openMusicFolder() {
        try {
            Path dir = MusicConfig.getMusicDir()
                    .resolve(currentCategory.getDirName())
                    .toAbsolutePath();
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
            BgmExtension.LOGGER.info("[bgmext] opened music folder: {}", dir);
        } catch (Exception e) {
            BgmExtension.LOGGER.error("[bgmext] failed to open music folder", e);
        }
    }

    private void confirmDelete(Track track) {
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        try {
                            Files.deleteIfExists(track.getFilePath());
                            BgmExtension.LOGGER.info("[bgmext] deleted file {}", track.getFilePath());
                        } catch (IOException e) {
                            BgmExtension.LOGGER.error("[bgmext] delete failed {}", track.getFilePath(), e);
                        }
                        MusicConfig.deleteCustomTrack(currentCategory, track.getFileName());
                        MusicLibrary.reload();
                        rebuildRows();
                    }
                    this.minecraft.setScreen(MusicSettingsScreen.this);
                },
                Component.literal("\u786e\u5b9a\u8981\u5220\u9664\u5417\uff1f"),
                Component.literal(track.getDisplayTitle() + "  (" + track.getFileName() + ")")));
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ------------------------------------------------------------------
    // 音量滑块
    // ------------------------------------------------------------------

    /**
     * 自定义音乐音量（分贝）。默认 -15 dB——自定义曲目通常比原版响得多。
     * 吸附到整分贝：配置里不会出现 -14.758064 这种值。
     */
    private static class VolumeSlider extends AbstractSliderButton {

        private static final float MIN_DB = MusicConfig.MIN_VOLUME_DB;
        private static final float MAX_DB = MusicConfig.MAX_VOLUME_DB;

        VolumeSlider(int x, int y, int width, int height, float db) {
            super(x, y, width, height, Component.empty(), toFraction(snap(db)));
            // 把内存里的值也吸附，关闭界面时统一落盘
            MusicConfig.setCustomVolumeDb(snap(db));
            updateMessage();
        }

        private static float snap(float db) {
            return Math.round(db);
        }

        private static double toFraction(float db) {
            return (db - MIN_DB) / (MAX_DB - MIN_DB);
        }

        private float db() {
            return (float) (MIN_DB + this.value * (MAX_DB - MIN_DB));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("\u97f3\u91cf %+.0f dB", db())));
        }

        @Override
        protected void applyValue() {
            float snapped = snap(db());
            MusicConfig.setCustomVolumeDb(snapped);

            // 让滑块本身也停到整分贝刻度上。
            // 直接给 value 赋值不会再次触发 applyValue（只有 setValue 会），所以不会递归。
            double target = toFraction(snapped);
            if (Math.abs(this.value - target) > 1.0e-4) {
                this.value = target;
            }
            updateMessage();
        }
    }

    // ------------------------------------------------------------------
    // 行模型
    // ------------------------------------------------------------------

    private enum RowKind { HEADER_VANILLA, HEADER_CUSTOM, HINT, EVENT, VANILLA_FILE, CUSTOM }

    private static final class Row {
        final RowKind kind;
        final String text;
        final ResourceLocation eventId;
        final ResourceLocation file;
        final Track track;
        final int subCount;
        final int fileCount;
        int contentY;

        private Row(RowKind kind, String text, ResourceLocation eventId, ResourceLocation file,
                    Track track, int subCount, int fileCount) {
            this.kind = kind;
            this.text = text;
            this.eventId = eventId;
            this.file = file;
            this.track = track;
            this.subCount = subCount;
            this.fileCount = fileCount;
        }

        static Row section(RowKind kind, String text, int subCount, int fileCount) {
            return new Row(kind, text, null, null, null, subCount, fileCount);
        }

        static Row hint(String t) {
            return new Row(RowKind.HINT, t, null, null, null, 0, 0);
        }

        static Row event(ResourceLocation id) {
            return new Row(RowKind.EVENT, null, id, null, null, 0,
                    VanillaMusicRegistry.filesOf(id).size());
        }

        static Row vanillaFile(ResourceLocation eventId, ResourceLocation file) {
            return new Row(RowKind.VANILLA_FILE, null, eventId, file, null, 0, 0);
        }

        static Row custom(Track t) {
            return new Row(RowKind.CUSTOM, null, null, null, t, 0, 0);
        }

        int height() {
            return kind == RowKind.HEADER_VANILLA || kind == RowKind.HEADER_CUSTOM
                    ? HEADER_H : ROW_H;
        }

        /** 该条目下的文件是否全部启用 */
        boolean allEventFilesEnabled() {
            List<ResourceLocation> files = VanillaMusicRegistry.filesOf(eventId);
            if (files.isEmpty()) return true;
            for (ResourceLocation f : files) {
                if (!MusicConfig.isVanillaEnabled(f)) return false;
            }
            return true;
        }
    }
}