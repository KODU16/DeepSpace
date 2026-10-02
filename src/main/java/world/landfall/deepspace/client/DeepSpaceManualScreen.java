package world.landfall.deepspace.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** DeepSpace-drawn manual with a category, entry and page hierarchy. */
final class DeepSpaceManualScreen extends Screen {
    private static final int MAX_WIDTH = 620;
    private static final int MAX_HEIGHT = 420;
    private static final int ROW_HEIGHT = 42;
    private static final int TEXT_COLOR = 0xFFF4F6F8;
    private static final int ACCENT = 0xFFA9D8EC;
    private static final int HIGHLIGHT = 0xFFD3E9F3;

    private final Screen parent;
    private List<Category> categories = List.of();
    private Category category;
    private Entry entry;
    private int pageIndex;
    private int scroll;

    DeepSpaceManualScreen(Screen parent) {
        super(Component.translatable("gui.deepspace.manual.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        loadContents();
    }

    private void loadContents() {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        JsonObject index = read(Deepspace.path("manual/index.json"));
        if (index == null || !index.has("categories")) return;
        List<Category> loaded = new ArrayList<>();
        for (var idValue : index.getAsJsonArray("categories")) {
            String id = idValue.getAsString();
            JsonObject definition = localized(language, "categories/" + id + ".json");
            if (definition == null) continue;
            List<Entry> entries = new ArrayList<>();
            for (var entryIdValue : definition.getAsJsonArray("manual_entries")) {
                JsonObject entryDefinition = localized(language, "entries/" + id + "/"
                        + entryIdValue.getAsString() + ".json");
                if (entryDefinition == null) continue;
                List<Page> pages = new ArrayList<>();
                for (var pageValue : entryDefinition.getAsJsonArray("pages")) {
                    JsonObject page = pageValue.getAsJsonObject();
                    pages.add(new Page(string(page, "title"), string(page, "text")));
                }
                entries.add(new Entry(string(entryDefinition, "name"), List.copyOf(pages)));
            }
            loaded.add(new Category(string(definition, "name"), string(definition, "description"),
                    List.copyOf(entries)));
        }
        categories = List.copyOf(loaded);
    }

    private static JsonObject localized(String language, String path) {
        String selected = language.equals("zh_cn") ? "zh_cn" : "en_us";
        JsonObject result = read(Deepspace.path("manual/" + selected + "/" + path));
        return result != null ? result : read(Deepspace.path("manual/en_us/" + path));
    }

    private static JsonObject read(ResourceLocation id) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;
        try (Reader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | IllegalStateException exception) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private int left() { return (width - Math.min(MAX_WIDTH, width - 20)) / 2; }
    private int right() { return width - left(); }
    private int top() { return (height - Math.min(MAX_HEIGHT, height - 20)) / 2; }
    private int bottom() { return height - top(); }
    private int contentTop() { return top() + 61; }
    private int contentBottom() { return bottom() - 42; }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xEE1A1D21);
        graphics.fill(left() - 2, top() - 2, right() + 2, bottom() + 2, ACCENT);
        graphics.fill(left(), top(), right(), bottom(), 0xFF2C3137);
        graphics.fill(left() + 2, top() + 2, right() - 2, top() + 40, 0xFF3B444B);
        graphics.drawString(font, title, left() + 17, top() + 11, HIGHLIGHT, false);
        graphics.drawString(font, breadcrumb(), left() + 17, top() + 27, ACCENT, false);
        graphics.fill(left() + 16, top() + 46, right() - 16, top() + 47, 0xFF829EAC);
        graphics.enableScissor(left() + 16, contentTop(), right() - 16, contentBottom());
        if (entry != null) renderPage(graphics);
        else renderDirectory(graphics, mouseX, mouseY);
        graphics.disableScissor();
        renderFooter(graphics, mouseX, mouseY);
    }

    private Component breadcrumb() {
        if (entry != null) return Component.literal(category.name() + " / " + entry.name());
        if (category != null) return Component.literal(category.name());
        return Component.translatable("gui.deepspace.manual.contents");
    }

    private void renderDirectory(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + 18;
        int right = right() - 18;
        int y = contentTop() - scroll;
        int index = 0;
        if (category == null) {
            for (Category value : categories) {
                drawRow(graphics, x, right, y, mouseX, mouseY, ++index, value.name(), value.description());
                y += ROW_HEIGHT;
            }
        } else {
            for (Entry value : category.entries()) {
                drawRow(graphics, x, right, y, mouseX, mouseY, ++index, value.name(),
                        Component.translatable("gui.deepspace.manual.pages", value.pages().size()).getString());
                y += ROW_HEIGHT;
            }
        }
    }

    private void drawRow(GuiGraphics graphics, int left, int right, int y, int mouseX, int mouseY,
                         int index, String name, String description) {
        boolean hovered = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW_HEIGHT - 3;
        graphics.fill(left, y, right, y + ROW_HEIGHT - 3, hovered ? 0xFF4A5962 : 0xFF373F46);
        graphics.fill(left, y, left + 3, y + ROW_HEIGHT - 3, hovered ? HIGHLIGHT : ACCENT);
        graphics.drawString(font, String.format("%02d", index), left + 12, y + 7, HIGHLIGHT, false);
        int textWidth = Math.max(1, right - left - 50);
        graphics.drawString(font, font.plainSubstrByWidth(name, textWidth),
                left + 42, y + 7, TEXT_COLOR, false);
        graphics.drawString(font, font.plainSubstrByWidth(description, textWidth),
                left + 42, y + 22, 0xFFB7C4CB, false);
    }

    private void renderPage(GuiGraphics graphics) {
        if (entry.pages().isEmpty()) return;
        Page page = entry.pages().get(pageIndex);
        int x = left() + 22;
        int y = contentTop() - scroll;
        graphics.drawString(font, page.title().isBlank() ? entry.name() : page.title(), x, y, HIGHLIGHT, false);
        y += 22;
        String content = page.text().replace("$(br2)", "\n\n").replace("$(br)", "\n");
        for (String paragraph : content.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                y += 9;
                continue;
            }
            for (var line : font.split(Component.literal(paragraph), right() - left() - 48)) {
                graphics.drawString(font, line, x, y, TEXT_COLOR, false);
                y += 12;
            }
        }
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = bottom() - 34;
        drawControl(graphics, left() + 16, y, 62, Component.translatable("gui.deepspace.manual.back"), mouseX, mouseY);
        if (entry != null) {
            drawControl(graphics, right() - 124, y, 44, Component.literal("◀"), mouseX, mouseY);
            drawControl(graphics, right() - 64, y, 44, Component.literal("▶"), mouseX, mouseY);
            // Keep the page counter to the left of both navigation controls.
            graphics.drawCenteredString(font, (pageIndex + 1) + " / " + entry.pages().size(),
                    right() - 158, y + 7, HIGHLIGHT);
        } else {
            graphics.drawString(font, Component.translatable("gui.deepspace.manual.hint"),
                    left() + 92, y + 7, 0xFFB7C4CB, false);
        }
    }

    private void drawControl(GuiGraphics graphics, int x, int y, int width, Component text,
                             int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, 24);
        graphics.fill(x, y, x + width, y + 24, hovered ? 0xFF668797 : 0xFF47545D);
        graphics.drawCenteredString(font, text, x + width / 2, y + 8, TEXT_COLOR);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int footer = bottom() - 34;
        if (inside(mouseX, mouseY, left() + 16, footer, 62, 24)) {
            goBack();
            return true;
        }
        if (entry != null) {
            if (inside(mouseX, mouseY, right() - 124, footer, 44, 24)) {
                pageIndex = Math.max(0, pageIndex - 1);
                scroll = 0;
                return true;
            }
            if (inside(mouseX, mouseY, right() - 64, footer, 44, 24)) {
                pageIndex = Math.min(entry.pages().size() - 1, pageIndex + 1);
                scroll = 0;
                return true;
            }
        }
        if (entry == null && mouseX >= left() + 18 && mouseX < right() - 18
                && mouseY >= contentTop() && mouseY < contentBottom()) {
            int index = (int) (mouseY - contentTop() + scroll) / ROW_HEIGHT;
            if (category == null && index >= 0 && index < categories.size()) category = categories.get(index);
            else if (category != null && index >= 0 && index < category.entries().size())
                entry = category.entries().get(index);
            scroll = 0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void goBack() {
        if (entry != null) entry = null;
        else if (category != null) category = null;
        else onClose();
        pageIndex = 0;
        scroll = 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Page scrolling follows wrapped text height instead of assuming a fixed number of lines.
        int extent = entry != null ? pageHeight()
                : (category == null ? categories.size() : category.entries().size()) * ROW_HEIGHT;
        int viewport = Math.max(1, contentBottom() - contentTop());
        scroll = Math.clamp(scroll - (int) Math.round(scrollY * 24), 0, Math.max(0, extent - viewport));
        return true;
    }

    private int pageHeight() {
        if (entry.pages().isEmpty()) return 0;
        String text = entry.pages().get(pageIndex).text()
                .replace("$(br2)", "\n\n").replace("$(br)", "\n");
        int height = 22;
        for (String paragraph : text.split("\n", -1)) {
            height += paragraph.isEmpty() ? 9
                    : font.split(Component.literal(paragraph), right() - left() - 48).size() * 12;
        }
        return height;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Category(String name, String description, List<Entry> entries) { }
    private record Entry(String name, List<Page> pages) { }
    private record Page(String title, String text) { }
}
