package com.steve.ai.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 带滚动的设置界面基类。
 *
 * <p><b>解决什么问题：</b>原来的设置界面把每个控件钉在硬编码的 Y 坐标上
 * （例如 {@code startY = 50} 起、每行 25 像素）。窗口一矮，或者玩家的 GUI 缩放一大，
 * 底部的"保存/返回"就掉到屏幕外面，而且没有任何办法滚过去 —— 玩家只能卡在那一页。</p>
 *
 * <p><b>做法：</b>把"内容坐标"和"屏幕坐标"分开。子类只管按顺序往下排控件
 * （{@link #addContent}），基类负责：</p>
 * <ul>
 *   <li>窗口够高时，内容整体靠上摆放，不滚动；</li>
 *   <li>窗口不够高时，自动启用鼠标滚轮滚动，并在右侧画一个滚动条；</li>
 *   <li>用 scissor 裁剪内容区，滚动时不会盖住标题；</li>
 *   <li>完全移出可视区的控件会被置为不可见，因此也点不到（避免"看不见却能误点"）。</li>
 * </ul>
 *
 * <p>标题与副标题固定在顶部，不参与滚动，这样滚动到任何位置都知道自己在哪一页。</p>
 */
public abstract class ScrollableSettingsScreen extends Screen {

    /** 顶部标题区高度：内容从这里往下开始。 */
    private static final int HEADER_HEIGHT = 52;
    /** 底部留白，避免滚动条和最后一行贴着屏幕边缘。 */
    private static final int FOOTER_MARGIN = 8;
    /** 滚轮每格的滚动距离（像素）。 */
    private static final double SCROLL_STEP = 20.0;

    protected final Screen parent;

    /** 已登记的内容项：控件 + 它在"内容坐标系"里的 Y。 */
    private final List<ContentEntry> content = new ArrayList<>();

    private double scroll;
    private int contentHeight;

    private record ContentEntry(AbstractWidget widget, int contentY) {}

    protected ScrollableSettingsScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /**
     * 子类在这里创建控件。
     *
     * <p>调用顺序即显示顺序；Y 从 0 开始往下累加即可，基类会负责加上标题偏移。</p>
     */
    protected abstract void buildContent();

    /**
     * 把一个控件按内容坐标加入界面。
     *
     * @param widget   已设置好 bounds 的控件（其中的 Y 会被基类重写）
     * @param contentY 相对内容顶部的 Y 坐标
     */
    protected <T extends AbstractWidget> T addContent(T widget, int contentY) {
        content.add(new ContentEntry(widget, contentY));
        addRenderableWidget(widget);
        return widget;
    }

    /** 声明内容总高度，用于判断是否需要滚动条。 */
    protected void setContentHeight(int contentHeight) {
        this.contentHeight = contentHeight;
    }

    /** 副标题/操作提示，子类可覆盖。 */
    protected String hintText() {
        return null;
    }

    /**
     * 在内容区（已裁剪）内绘制自定义内容，例如控件旁边的文字标签。
     *
     * <p>调用时机在 {@code super.render} 之前，因此控件本身会覆盖在标签之上。
     * 使用控件的 {@code getY()} 来定位标签，滚动时就会自动跟随。</p>
     */
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        super.init();
        content.clear();
        scroll = 0;
        contentHeight = 0;

        buildContent();

        // 内容高度未显式声明时，按最后一个控件的底部推算，避免每个子类都要手工维护。
        if (contentHeight <= 0 && !content.isEmpty()) {
            int max = 0;
            for (ContentEntry entry : content) {
                max = Math.max(max, entry.contentY() + entry.widget().getHeight());
            }
            contentHeight = max;
        }

        applyScroll();
    }

    // ------------------------------------------------------------------
    // 滚动
    // ------------------------------------------------------------------

    private int viewTop() {
        return HEADER_HEIGHT;
    }

    private int viewBottom() {
        return Math.max(viewTop() + 20, this.height - FOOTER_MARGIN);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewBottom() - viewTop()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = maxScroll();
        if (max <= 0) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        scroll = Mth.clamp(scroll - delta * SCROLL_STEP, 0, max);
        applyScroll();
        return true;
    }

    /** 把内容坐标换算成屏幕坐标，并隐藏完全移出可视区的控件。 */
    private void applyScroll() {
        int offset = (int) Math.round(scroll);
        int top = viewTop();
        int bottom = viewBottom();

        for (ContentEntry entry : content) {
            AbstractWidget widget = entry.widget();
            widget.setY(entry.contentY() + top - offset);

            boolean onScreen = widget.getY() + widget.getHeight() > top
                && widget.getY() < bottom;
            widget.visible = onScreen;
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 标题区固定，不随内容滚动
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        String hint = hintText();
        if (hint != null && !hint.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(hint),
                this.width / 2, 30, 0xAAAAAA);
        }

        // 内容区裁剪：滚动时控件不会越界盖住标题
        graphics.enableScissor(0, viewTop(), this.width, viewBottom());
        renderContent(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();

        renderScrollbar(graphics);
    }

    /** 右侧滚动条。只有内容确实超出可视区时才画。 */
    private void renderScrollbar(GuiGraphics graphics) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }

        int top = viewTop();
        int bottom = viewBottom();
        int trackHeight = bottom - top;

        int x = this.width - 7;
        graphics.fill(x, top, x + 3, bottom, 0x30FFFFFF);

        int barHeight = Math.max(16,
            (int) ((double) trackHeight * trackHeight / Math.max(1, contentHeight)));
        int barY = top + (int) ((double) (trackHeight - barHeight) * (scroll / max));
        graphics.fill(x, barY, x + 3, barY + barHeight, 0xA0FFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /** 统一的返回逻辑，子类可直接复用。 */
    protected void goBack() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
