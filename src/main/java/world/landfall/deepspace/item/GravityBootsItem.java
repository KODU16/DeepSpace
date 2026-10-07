package world.landfall.deepspace.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

/** Iron boots with the same vanilla appearance and a switchable space-gravity ability. */
public final class GravityBootsItem extends ArmorItem {
    public GravityBootsItem(Properties properties) {
        super(ArmorMaterials.IRON, Type.BOOTS, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.deepspace.gravity_boots.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
