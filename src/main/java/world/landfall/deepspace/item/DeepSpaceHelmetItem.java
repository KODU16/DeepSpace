package world.landfall.deepspace.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

/** Adds the HUD description while retaining the base helmet's iron armor behavior. */
public final class DeepSpaceHelmetItem extends ArmorItem {
    public DeepSpaceHelmetItem(Properties properties) {
        super(ArmorMaterials.IRON, Type.HELMET, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.deepspace.deepspace_helmet.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
