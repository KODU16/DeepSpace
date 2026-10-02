package world.landfall.deepspace.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/** A small terminal-styled book control shared by the galaxy and star-map views. */
final class DeepSpaceManualButton {
    private static final int SIZE = 28;

    private DeepSpaceManualButton() {
    }

    static boolean contains(Screen screen, double mouseX, double mouseY) {
        return mouseX >= screen.width - SIZE - 22 && mouseX < screen.width - 22
                && mouseY >= screen.height - SIZE - 22 && mouseY < screen.height - 22;
    }

    static void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        int x = screen.width - SIZE - 22;
        int y = screen.height - SIZE - 22;
        boolean hovered = contains(screen, mouseX, mouseY);
        graphics.fill(x, y, x + SIZE, y + SIZE, hovered ? 0xFF617D8B : 0xEE343D44);
        graphics.fill(x, y, x + SIZE, y + 1, 0xFFA9D8EC);
        graphics.fill(x, y + SIZE - 1, x + SIZE, y + SIZE, 0xFFA9D8EC);
        graphics.fill(x, y, x + 1, y + SIZE, 0xFFA9D8EC);
        graphics.fill(x + SIZE - 1, y, x + SIZE, y + SIZE, 0xFFA9D8EC);
        // Render the vanilla book item so the terminal uses its actual inventory icon.
        graphics.renderItem(Items.BOOK.getDefaultInstance(), x + 6, y + 6);
        if (hovered) {
            graphics.renderTooltip(screen.getMinecraft().font,
                    Component.translatable("gui.deepspace.manual.open"), mouseX, mouseY);
        }
    }
}
