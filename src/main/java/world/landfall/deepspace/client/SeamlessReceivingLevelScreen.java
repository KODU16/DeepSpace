package world.landfall.deepspace.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;

import java.util.function.BooleanSupplier;

public class SeamlessReceivingLevelScreen extends ReceivingLevelScreen {
    public SeamlessReceivingLevelScreen(BooleanSupplier levelReceived, Reason reason) {
        super(levelReceived, reason);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Keep the safety gate active without covering the newly created client level.
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally transparent: no panorama, blur, or "loading terrain" presentation.
    }

    @Override
    public void tick() {
        // 星系不走原版视距区块，不能等 ReceivingLevelScreen 的区块门槛。
        this.onClose();
    }
}
