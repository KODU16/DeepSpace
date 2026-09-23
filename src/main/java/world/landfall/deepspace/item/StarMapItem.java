package world.landfall.deepspace.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.network.StarMapOpenPacket;
import world.landfall.deepspace.planet.StarMapExploration;

/** Opens the player's persistent galaxy exploration map. */
public final class StarMapItem extends Item {
    public StarMapItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (player instanceof ServerPlayer serverPlayer) {
            StarMapExploration.recordCurrentGalaxy(serverPlayer);
            PacketDistributor.sendToPlayer(serverPlayer, StarMapOpenPacket.forPlayer(serverPlayer));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
