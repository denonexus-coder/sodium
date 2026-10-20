package me.jellysquid.mods.sodium.client.gui;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.data.fingerprint.HashedFingerprint;
import me.jellysquid.mods.sodium.client.gui.console.Console;
import me.jellysquid.mods.sodium.client.gui.console.message.MessageLevel;
import me.jellysquid.mods.sodium.client.gui.flywheel.FlywheelOptionsPage;
import me.jellysquid.mods.sodium.client.gui.vanillin.VanillinOptionsPage;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlElement;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import me.jellysquid.mods.sodium.client.gui.prompt.ScreenPrompt;
import me.jellysquid.mods.sodium.client.gui.prompt.ScreenPromptable;
import me.jellysquid.mods.sodium.client.gui.screen.ConfigCorruptedScreen;
import me.jellysquid.mods.sodium.client.gui.widgets.FlatButtonWidget;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Language;
import net.minecraft.util.Util;
import net.minecraft.util.math.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// TODO: Rename in Sodium 0.6
public class SodiumOptionsGUI extends Screen implements ScreenPromptable {
    private final List<OptionPage> pages = new ArrayList<>();

    private final List<ControlElement<?>> controls = new ArrayList<>();

    private static final int ROW_HEIGHT = 20;
    private static final int SECTION_HEIGHT = 16;
    private static final int GROUP_GAP = 6;
    private static final int[] PAGE_ACCENTS = {
            0xFF4FC3F7, 0xFF8AD97B, 0xFFFFC15E, 0xFFB7A4F5, 0xFF63D8D2, 0xFFFF8FA3, 0xFFFFB067
    };
    private static final FlatButtonWidget.Style RAIL_BUTTON_STYLE = createRailButtonStyle();

    private final List<SectionHeader> sections = new ArrayList<>();

    private int railWidth;
    private int contentX, contentY, contentW, contentH;
    private boolean detailVisible;
    private int detailX, detailY, detailW, detailH;
    private int contentScroll, contentMaxScroll;
    private int detailScroll, detailMaxScroll;
    private @Nullable Option<?> detailOption;
    private boolean draggingContent;
    private double dragStartY;
    private int dragStartScroll;
    private boolean draggingDetail;
    private double dragDetailStartY;
    private int dragDetailStartScroll;

    private static FlatButtonWidget.Style createRailButtonStyle() {
        FlatButtonWidget.Style style = new FlatButtonWidget.Style();
        style.bgDefault = 0x00000000;
        style.bgHovered = 0x26FFFFFF;
        style.bgDisabled = 0x10000000;
        style.textDefault = 0xFFC3CBD9;
        style.textDisabled = 0x60FFFFFF;

        return style;
    }

    private final Screen prevScreen;

    private OptionPage currentPage;

    private FlatButtonWidget applyButton, closeButton, undoButton;
    private FlatButtonWidget donateButton, hideDonateButton;

    private boolean hasPendingChanges;
    private ControlElement<?> hoveredElement;

    private @Nullable ScreenPrompt prompt;

    private SodiumOptionsGUI(Screen prevScreen) {
        super(Text.literal("Configurações do Sodium"));

        this.prevScreen = prevScreen;

        this.pages.add(SodiumGameOptionPages.general());
        this.pages.add(SodiumGameOptionPages.quality());
        this.pages.add(SodiumGameOptionPages.visual());
        this.pages.add(SodiumGameOptionPages.performance());
        this.pages.add(SodiumGameOptionPages.advanced());

        // Flywheel's own page, but only when Flywheel is installed: a tab that would open on
        // nothing but greyed rows is worse than no tab at all.
        OptionPage flywheelPage = FlywheelOptionsPage.create();

        if (flywheelPage != null) {
            this.pages.add(flywheelPage);
        }

        // Vanillin decides which vanilla objects Flywheel instances. Its page is listed after
        // Flywheel's because it configures what the engine draws, not how the engine runs, and it
        // is hidden the same way: no Vanillin, no tab.
        OptionPage vanillinPage = VanillinOptionsPage.create();

        if (vanillinPage != null) {
            this.pages.add(vanillinPage);
        }

        this.checkPromptTimers();
    }

    private void checkPromptTimers() {
        // Never show the prompt in developer workspaces.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }

        var options = SodiumClientMod.options();

        // If the user has already seen the prompt, don't show it again.
        if (options.notifications.hasSeenDonationPrompt) {
            return;
        }

        HashedFingerprint fingerprint = null;

        try {
            fingerprint = HashedFingerprint.loadFromDisk();
        } catch (Throwable t) {
            SodiumClientMod.logger()
                    .error("Failed to read the fingerprint from disk", t);
        }

        // If the fingerprint doesn't exist, or failed to be loaded, abort.
        if (fingerprint == null) {
            return;
        }

        // The fingerprint records the installation time. If it's been a while since installation, show the user
        // a prompt asking for them to consider donating.
        var now = Instant.now();
        var threshold = Instant.ofEpochSecond(fingerprint.timestamp())
                .plus(3, ChronoUnit.DAYS);

        if (now.isAfter(threshold)) {
            this.openDonationPrompt(options);
        }
    }

    private void openDonationPrompt(SodiumGameOptions options) {
        var prompt = new ScreenPrompt(this, DONATION_PROMPT_MESSAGE, 320, 190,
                new ScreenPrompt.Action(Text.literal("Buy us a coffee"), this::openDonationPage));
        prompt.setFocused(true);

        options.notifications.hasSeenDonationPrompt = true;

        try {
            SodiumGameOptions.writeToDisk(options);
        } catch (IOException e) {
            SodiumClientMod.logger()
                    .error("Failed to update config file", e);
        }
    }

    public static Screen createScreen(Screen currentScreen) {
        if (SodiumClientMod.options().isReadOnly()) {
            return new ConfigCorruptedScreen(currentScreen, SodiumOptionsGUI::new);
        } else {
            return new SodiumOptionsGUI(currentScreen);
        }
    }

    public void setPage(OptionPage page) {
        this.currentPage = page;
        this.contentScroll = 0;
        this.detailScroll = 0;
        this.detailOption = null;

        this.rebuildGUI();
    }

    @Override
    protected void init() {
        super.init();

        this.rebuildGUI();

        if (this.prompt != null) {
            this.prompt.init();
        }
    }

    private void rebuildGUI() {
        this.controls.clear();

        this.clearChildren();

        if (this.currentPage == null) {
            if (this.pages.isEmpty()) {
                throw new IllegalStateException("No pages are available?!");
            }

            // Just use the first page for now
            this.currentPage = this.pages.get(0);
        }

        this.computeLayout();

        this.rebuildGUIPages();
        this.rebuildGUIOptions();

        this.undoButton = new FlatButtonWidget(new Dim2i(this.width - 203, this.height - 28, 63, 20), Text.literal("Anular"), this::undoChanges);
        this.applyButton = new FlatButtonWidget(new Dim2i(this.width - 136, this.height - 28, 63, 20), Text.literal("Aplicar"), this::applyChanges);
        this.closeButton = new FlatButtonWidget(new Dim2i(this.width - 69, this.height - 28, 63, 20), Text.translatable("gui.done"), this::close);
        this.donateButton = new FlatButtonWidget(new Dim2i(this.width - 128, 4, 100, 20), Text.literal("Oferecer um café"), this::openDonationPage);
        this.hideDonateButton = new FlatButtonWidget(new Dim2i(this.width - 24, 4, 18, 20), Text.literal("x"), this::hideDonationButton);

        if (SodiumClientMod.options().notifications.hasClearedDonationButton) {
            this.setDonationButtonVisibility(false);
        }

        this.addDrawableChild(this.undoButton);
        this.addDrawableChild(this.applyButton);
        this.addDrawableChild(this.closeButton);
        this.addDrawableChild(this.donateButton);
        this.addDrawableChild(this.hideDonateButton);
    }

    /**
     * Splits the screen into the three columns of the redesign: a fixed navigation rail, the
     * scrolling option list and, on wide screens, the detail pane. The footer keeps its height
     * even when the list is short, so the buttons never jump.
     */
    private void computeLayout() {
        boolean compact = this.width < 560;

        this.railWidth = compact ? 76 : 82;
        this.detailVisible = this.width >= 600;

        this.contentX = this.railWidth + 8;
        this.contentY = 32;

        int contentRight = this.width - 8;

        if (this.detailVisible) {
            this.detailW = Mth.clamp(this.width / 5, 156, 220);
            this.detailX = this.width - 6 - this.detailW;
            this.detailY = 30;
            this.detailH = Math.max(60, this.height - this.detailY - 42);

            contentRight = this.detailX - 8;
        }

        this.contentW = Math.max(150, contentRight - this.contentX);
        this.contentH = Math.max(60, this.height - this.contentY - 38);
    }

    private void setDonationButtonVisibility(boolean value) {
        this.donateButton.setVisible(value);
        this.hideDonateButton.setVisible(value);
    }

    private void hideDonationButton() {
        SodiumGameOptions options = SodiumClientMod.options();
        options.notifications.hasClearedDonationButton = true;

        try {
            SodiumGameOptions.writeToDisk(options);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save configuration", e);
        }

        this.setDonationButtonVisibility(false);
    }

    private void rebuildGUIPages() {
        int x = 6;
        int y = 34;

        for (OptionPage page : this.pages) {
            FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, this.railWidth - 12, 20), page.getName(), () -> this.setPage(page));
            button.setStyle(RAIL_BUTTON_STYLE);
            button.setSelected(this.currentPage == page);
            button.setSelectedColor(this.accent());

            y += 22;

            this.addDrawableChild(button);
        }
    }

    private void rebuildGUIOptions() {
        this.sections.clear();

        int total = 0;

        for (OptionGroup group : this.currentPage.getGroups()) {
            if (group.getName() != null) {
                total += SECTION_HEIGHT;
            }

            total += group.getOptions().size() * ROW_HEIGHT + GROUP_GAP;
        }

        this.contentMaxScroll = Math.max(0, total - this.contentH);
        this.contentScroll = Mth.clamp(this.contentScroll, 0, this.contentMaxScroll);

        int offset = 0;

        for (OptionGroup group : this.currentPage.getGroups()) {
            Text name = group.getName();

            if (name != null) {
                int y = this.contentY + offset - this.contentScroll;

                if (y + SECTION_HEIGHT > this.contentY && y < this.contentY + this.contentH) {
                    this.sections.add(new SectionHeader(y, name));
                }

                offset += SECTION_HEIGHT;
            }

            for (Option<?> option : group.getOptions()) {
                int y = this.contentY + offset - this.contentScroll;

                // Only rows that fit entirely are created. That keeps every widget inside the
                // content rectangle, so no row can draw (or be clicked) over the header, the
                // footer or the detail pane, and no scissor is needed around the children.
                if (y >= this.contentY && y + ROW_HEIGHT <= this.contentY + this.contentH) {
                    ControlElement<?> element = option.getControl().createElement(new Dim2i(this.contentX, y, this.contentW, ROW_HEIGHT));

                    this.addDrawableChild(element);
                    this.controls.add(element);
                }

                offset += ROW_HEIGHT;
            }

            offset += GROUP_GAP;
        }
    }

    private int accent() {
        int index = Math.max(0, this.pages.indexOf(this.currentPage));

        return PAGE_ACCENTS[index % PAGE_ACCENTS.length];
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static String impactWord(OptionImpact impact) {
        return switch (impact) {
            case LOW -> "baixo";
            case MEDIUM -> "médio";
            case HIGH -> "alto";
            case VARIES -> "variável";
        };
    }

    private record SectionHeader(int y, Text name) {
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * Whether the cursor is over the interactive part of a row (the right-aligned control). The
     * rest of the strip is free for drag-to-scroll, which is the only way to scroll on touch
     * devices that do not translate a finger drag into a mouse wheel event.
     */
    private boolean isOverControl(double mouseX, double mouseY) {
        for (ControlElement<?> element : this.controls) {
            Dim2i dim = element.getDimensions();

            if (mouseY >= dim.y() && mouseY < dim.getLimitY() && mouseX >= dim.getLimitX() - element.getOption().getControl().getMaxWidth()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        this.updateControls();

        super.renderBackground(drawContext);

        this.renderChrome(drawContext);

        super.render(drawContext, this.prompt != null ? -1 : mouseX, this.prompt != null ? -1 : mouseY, delta);

        this.renderDetail(drawContext);
        this.renderScrollbar(drawContext);

        // Screens narrower than the detail pane keep the floating tooltip: there is no room for
        // both columns, and a cut tooltip is still better than no explanation at all.
        if (!this.detailVisible && this.hoveredElement != null) {
            this.renderOptionTooltip(drawContext, this.hoveredElement);
        }

        if (this.prompt != null) {
            this.prompt.render(drawContext, mouseX, mouseY, delta);
        }
    }

    private void renderChrome(DrawContext drawContext) {
        int accent = this.accent();

        drawContext.fillGradient(0, 0, this.width, this.height, 0xFF0A0C11, 0xFF11151E);

        drawContext.fill(0, 0, this.railWidth + 4, this.height, 0xFF0C0F15);
        drawContext.fill(this.railWidth + 4, 0, this.railWidth + 5, this.height, 0xFF1D2330);

        int selectedIndex = this.pages.indexOf(this.currentPage);

        if (selectedIndex >= 0) {
            int y = 34 + selectedIndex * 22;

            drawContext.fill(4, y + 2, 6, y + 18, accent);
        }

        Text title = this.currentPage.getName();

        drawContext.drawTextWithShadow(this.textRenderer, title, this.contentX, 10, accent);

        int titleWidth = Math.min(this.contentW, this.textRenderer.getWidth(title) + 22);
        drawContext.fill(this.contentX, 24, this.contentX + titleWidth, 25, withAlpha(accent, 0x80));

        drawContext.enableScissor(this.contentX, this.contentY, this.contentX + this.contentW, this.contentY + this.contentH);

        for (SectionHeader section : this.sections) {
            String upper = section.name().getString().toUpperCase(Locale.ROOT);
            int textY = section.y() + 4;

            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(upper), this.contentX + 2, textY, withAlpha(accent, 0xC0));

            int lineX = this.contentX + 8 + this.textRenderer.getWidth(upper);
            drawContext.fill(lineX, textY + 7, this.contentX + this.contentW, textY + 8, 0x22FFFFFF);
        }

        drawContext.disableScissor();

        if (this.detailVisible) {
            drawContext.fill(this.detailX, this.detailY, this.detailX + this.detailW, this.detailY + this.detailH, 0xB00B0E14);
            drawContext.fill(this.detailX, this.detailY, this.detailX + 1, this.detailY + this.detailH, 0xFF1D2330);
        }

        drawContext.fill(this.contentX, this.height - 36, this.width - 6, this.height - 35, 0x22FFFFFF);
    }

    private void renderDetail(DrawContext drawContext) {
        if (!this.detailVisible) {
            this.detailMaxScroll = 0;
            return;
        }

        int x = this.detailX + 8;
        int maxWidth = this.detailW - 16;
        int y = this.detailY + 8;

        ControlElement<?> element = this.hoveredElement;

        if (element == null) {
            List<OrderedText> hint = this.textRenderer.wrapLines(
                    Text.literal("Toque ou passe o cursor sobre uma opção para ler a explicação completa.").formatted(Formatting.GRAY), maxWidth);

            for (int i = 0; i < hint.size(); i++) {
                drawContext.drawTextWithShadow(this.textRenderer, hint.get(i), x, y + i * 10, 0xFF8A93A3);
            }

            this.detailOption = null;
            this.detailMaxScroll = 0;
            this.detailScroll = 0;

            return;
        }

        Option<?> option = element.getOption();

        if (option != this.detailOption) {
            this.detailOption = option;
            this.detailScroll = 0;
        }

        drawContext.drawTextWithShadow(this.textRenderer, option.getName(), x, y, 0xFFFFFFFF);
        y += 14;

        OptionImpact impact = option.getImpact();

        if (impact != null) {
            Text badge = Text.literal("Impacto " + impactWord(impact));
            int badgeWidth = this.textRenderer.getWidth(badge) + 8;

            drawContext.fill(x, y, x + badgeWidth, y + 12, withAlpha(impact.getColor(), 0x33));
            drawContext.drawTextWithShadow(this.textRenderer, badge, x + 4, y + 2, impact.getColor());

            y += 18;
        }

        drawContext.fill(x, y, this.detailX + this.detailW - 8, y + 1, 0x22FFFFFF);
        y += 7;

        List<OrderedText> lines = new ArrayList<>(this.textRenderer.wrapLines(option.getTooltip(), maxWidth));

        int viewTop = y;
        int viewBottom = this.detailY + this.detailH - 6;
        int viewHeight = Math.max(0, viewBottom - viewTop);
        int textHeight = lines.size() * 10;

        this.detailMaxScroll = Math.max(0, textHeight - viewHeight);
        this.detailScroll = Mth.clamp(this.detailScroll, 0, this.detailMaxScroll);

        if (viewHeight > 0) {
            drawContext.enableScissor(this.detailX + 1, viewTop, this.detailX + this.detailW, viewBottom);

            int textY = viewTop - this.detailScroll;

            for (OrderedText line : lines) {
                if (textY + 10 >= viewTop && textY <= viewBottom) {
                    drawContext.drawTextWithShadow(this.textRenderer, line, x, textY, 0xFFB9C2D0);
                }

                textY += 10;
            }

            drawContext.disableScissor();
        }

        if (this.detailMaxScroll > 0) {
            int trackX = this.detailX + this.detailW - 4;
            int trackTop = viewTop;
            int visible = Math.max(10, viewHeight * viewHeight / (viewHeight + this.detailMaxScroll));
            int offset = (int) ((long) this.detailScroll * (viewHeight - visible) / this.detailMaxScroll);

            drawContext.fill(trackX, trackTop, trackX + 2, trackTop + viewHeight, 0x22FFFFFF);
            drawContext.fill(trackX, trackTop + offset, trackX + 2, trackTop + offset + visible, withAlpha(option.getImpact() != null ? option.getImpact().getColor() : accent(), 0x90));
        }
    }

    private void renderScrollbar(DrawContext drawContext) {
        if (this.contentMaxScroll <= 0) {
            return;
        }

        int trackX = this.contentX + this.contentW + 2;
        int trackTop = this.contentY;
        int visible = Math.max(12, this.contentH * this.contentH / (this.contentH + this.contentMaxScroll));
        int maxOffset = this.contentH - visible;
        int offset = (int) ((long) this.contentScroll * maxOffset / this.contentMaxScroll);

        drawContext.fill(trackX, trackTop, trackX + 2, trackTop + this.contentH, 0x22FFFFFF);
        drawContext.fill(trackX, trackTop + offset, trackX + 2, trackTop + offset + visible, withAlpha(this.accent(), 0xA0));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (this.detailVisible && this.detailMaxScroll > 0
                && this.isInside(mouseX, mouseY, this.detailX, this.detailY, this.detailW, this.detailH)) {
            int next = Mth.clamp(this.detailScroll - (int) (amount * 14), 0, this.detailMaxScroll);

            if (next != this.detailScroll) {
                this.detailScroll = next;

                return true;
            }
        }

        if (this.isInside(mouseX, mouseY, this.contentX, this.contentY, this.contentW, this.contentH)) {
            int next = Mth.clamp(this.contentScroll - (int) (amount * (ROW_HEIGHT * 2)), 0, this.contentMaxScroll);

            if (next != this.contentScroll) {
                this.contentScroll = next;
                this.rebuildGUI();

                return true;
            }

            return this.contentMaxScroll > 0;
        }

        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingDetail) {
            if (this.detailMaxScroll > 0) {
                this.detailScroll = Mth.clamp(this.dragDetailStartScroll - (int) (mouseY - this.dragDetailStartY), 0, this.detailMaxScroll);
            }

            return true;
        }

        if (this.draggingContent) {
            if (this.contentMaxScroll > 0) {
                int next = Mth.clamp(this.dragStartScroll - (int) (mouseY - this.dragStartY), 0, this.contentMaxScroll);

                if (next != this.contentScroll) {
                    this.contentScroll = next;
                    this.rebuildGUI();
                }
            }

            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingContent = false;
        this.draggingDetail = false;

        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateControls() {
        ControlElement<?> hovered = this.getActiveControls()
                .filter(ControlElement::isHovered)
                .findFirst()
                .orElse(this.getActiveControls() // If there is no hovered element, use the focused element.
                        .filter(ControlElement::isFocused)
                        .findFirst()
                        .orElse(null));

        boolean hasChanges = this.getAllOptions()
                .anyMatch(Option::hasChanged);

        for (OptionPage page : this.pages) {
            for (Option<?> option : page.getOptions()) {
                if (option.hasChanged()) {
                    hasChanges = true;
                }
            }
        }

        this.applyButton.setEnabled(hasChanges);
        this.undoButton.setVisible(hasChanges);
        this.closeButton.setEnabled(!hasChanges);

        this.hasPendingChanges = hasChanges;
        this.hoveredElement = hovered;
    }

    private Stream<Option<?>> getAllOptions() {
        return this.pages.stream()
                .flatMap(s -> s.getOptions().stream());
    }

    private Stream<ControlElement<?>> getActiveControls() {
        return this.controls.stream();
    }

    /**
     * The impact suffix in Portuguese. The enum's own names come from translation keys, which fall
     * back to English, so the suffix is spelled out here instead of appended in the caller.
     */
    private static Text impactLabel(OptionImpact impact) {
        String value = switch (impact) {
            case LOW -> "baixo";
            case MEDIUM -> "médio";
            case HIGH -> "alto";
            case VARIES -> "varia";
        };

        return Text.literal("Impacto no desempenho: " + value).formatted(Formatting.GRAY);
    }

    private void renderOptionTooltip(DrawContext drawContext, ControlElement<?> element) {
        Dim2i dim = element.getDimensions();

        int textPadding = 3;
        int boxPadding = 3;

        int boxWidth = 200;

        int boxY = dim.y();
        int boxX = dim.getLimitX() + boxPadding;

        Option<?> option = element.getOption();
        List<OrderedText> tooltip = new ArrayList<>(this.textRenderer.wrapLines(option.getTooltip(), boxWidth - (textPadding * 2)));

        OptionImpact impact = option.getImpact();

        if (impact != null) {
            tooltip.add(Language.getInstance().reorder(impactLabel(impact)));
        }

        int boxHeight = (tooltip.size() * 12) + boxPadding;
        int boxYLimit = boxY + boxHeight;
        int boxYCutoff = this.height - 40;

        // If the box is going to be cutoff on the Y-axis, move it back up the difference
        if (boxYLimit > boxYCutoff) {
            boxY -= boxYLimit - boxYCutoff;
        }

        drawContext.fillGradient(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0000000, 0xE0000000);

        for (int i = 0; i < tooltip.size(); i++) {
            drawContext.drawTextWithShadow(this.textRenderer, tooltip.get(i), boxX + textPadding, boxY + textPadding + (i * 12), 0xFFFFFFFF);
        }
    }

    private void applyChanges() {
        final HashSet<OptionStorage<?>> dirtyStorages = new HashSet<>();
        final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);

        this.getAllOptions().forEach((option -> {
            if (!option.hasChanged()) {
                return;
            }

            option.applyChanges();

            flags.addAll(option.getFlags());
            dirtyStorages.add(option.getStorage());
        }));

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world != null) {
            if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD)) {
                client.worldRenderer.reload();
            } else if (flags.contains(OptionFlag.REQUIRES_RENDERER_UPDATE)) {
                client.worldRenderer.scheduleTerrainUpdate();
            }
        }

        if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD)) {
            client.setMipmapLevels(client.options.getMipmapLevels().getValue());
            client.reloadResourcesConcurrently();
        }

        if (flags.contains(OptionFlag.REQUIRES_GAME_RESTART)) {
            Console.instance().logMessage(MessageLevel.WARN,
                    Text.translatable("sodium.console.game_restart"), 10.0);
        }

        for (OptionStorage<?> storage : dirtyStorages) {
            storage.save();
        }
    }

    private void undoChanges() {
        this.getAllOptions()
                .forEach(Option::reset);
    }

    private void openDonationPage() {
        Util.getOperatingSystem()
                .open("https://caffeinemc.net/donate");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.prompt != null && this.prompt.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (this.prompt == null && keyCode == GLFW.GLFW_KEY_P && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            MinecraftClient.getInstance().setScreen(new VideoOptionsScreen(this.prevScreen, MinecraftClient.getInstance().options));

            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.prompt != null) {
            return this.prompt.mouseClicked(mouseX, mouseY, button);
        }

        // A press on the empty part of a row starts a drag-to-scroll. Starting it over a slider
        // or a toggle would steal the drag from the control, so those areas are left alone.
        if (button == 0
                && this.isInside(mouseX, mouseY, this.contentX, this.contentY, this.contentW, this.contentH)
                && !this.isOverControl(mouseX, mouseY)) {
            this.draggingContent = true;
            this.dragStartY = mouseY;
            this.dragStartScroll = this.contentScroll;
        }

        if (button == 0 && this.detailVisible && this.detailMaxScroll > 0
                && this.isInside(mouseX, mouseY, this.detailX, this.detailY, this.detailW, this.detailH)) {
            this.draggingDetail = true;
            this.dragDetailStartY = mouseY;
            this.dragDetailStartScroll = this.detailScroll;
        }

        boolean clicked = super.mouseClicked(mouseX, mouseY, button);

        if (!clicked) {
            this.setFocused(null);
            return true;
        }

        return clicked;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !this.hasPendingChanges;
    }

    @Override
    public void close() {
        this.client.setScreen(this.prevScreen);
    }

    @Override
    public List<? extends Element> children() {
        return this.prompt == null ? super.children() : this.prompt.getWidgets();
    }

    @Override
    public void setPrompt(@Nullable ScreenPrompt prompt) {
        this.prompt = prompt;
    }

    @Nullable
    @Override
    public ScreenPrompt getPrompt() {
        return this.prompt;
    }

    @Override
    public Dim2i getDimensions() {
        return new Dim2i(0, 0, this.width, this.height);
    }

    private static final List<StringVisitable> DONATION_PROMPT_MESSAGE;

    static {
        DONATION_PROMPT_MESSAGE = List.of(
                StringVisitable.concat(Text.literal("Hello!")),
                StringVisitable.concat(Text.literal("It seems that you've been enjoying "), Text.literal("Sodium").setStyle(Style.EMPTY.withColor(0x27eb92)), Text.literal(", the powerful and open rendering optimization mod for Minecraft.")),
                StringVisitable.concat(Text.literal("Mods like these are complex. They require "), Text.literal("thousands of hours").setStyle(Style.EMPTY.withColor(0xff6e00)), Text.literal(" of development, debugging, and tuning to create the experience that players have come to expect.")),
                StringVisitable.concat(Text.literal("If you'd like to show your token of appreciation, and support the development of our mod in the process, then consider "), Text.literal("buying us a coffee").setStyle(Style.EMPTY.withColor(0xed49ce)), Text.literal(".")),
                StringVisitable.concat(Text.literal("And thanks again for using our mod! We hope it helps you (and your computer.)"))
        );
    }
}
