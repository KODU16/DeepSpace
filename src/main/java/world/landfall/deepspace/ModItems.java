package world.landfall.deepspace;

import com.simibubi.create.content.logistics.vault.ItemVaultBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import world.landfall.deepspace.item.*;

import java.util.function.Supplier;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Deepspace.MODID);
    public static final DeferredItem<Item> ANGEL_BLOCK_ITEM = ITEMS.register("angel_block", () -> new AngelBlockItem(new Item.Properties()));
    public static final DeferredItem<JetpackItem> JETPACK_ITEM = ITEMS.register("jetpack", JetpackItem::new);
    public static final DeferredItem<Item> OXYGENATOR_BLOCK_ITEM = ITEMS.register("oxygenator", OxygenatorBlockItem::new);
    public static final DeferredItem<BucketItem> OXYGEN_BUCKET = ITEMS.register("oxygen_bucket", () ->
            new BucketItem(ModFluids.OXYGEN.get(), new Item.Properties()
                    .craftRemainder(Items.BUCKET)
                    .stacksTo(1)));
    public static final DeferredItem<BlockItem> MOONSTONE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.MOON_STONE);
    public static final DeferredItem<BlockItem> LUNAR_COBBLE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.LUNAR_COBBLE);
    public static final DeferredItem<BlockItem> LUNAR_SOIL = ITEMS.registerSimpleBlockItem(ModBlocks.LUNAR_SOIL);
    public static final DeferredItem<BlockItem> STAR_BRAMBLE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.STAR_BRAMBLE);
    public static final DeferredItem<Item> STARBULB_ITEM = ITEMS.registerItem("starbulb", Item::new);

    public static final DeferredItem<BlockItem> PICKLE_MOSS_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.PICKLE_MOSS_BLOCK);
    public static final DeferredItem<BlockItem> PICKLE_CORE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.PICKLE_CORE_BLOCK);
    public static final DeferredItem<BlockItem> PICKLE_VINE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.PICKLE_VINE_BLOCK);

    public static final DeferredItem<BlockItem> SELENIC_GRASS_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.SELENIC_GRASS_BLOCK);
    public static final DeferredItem<BlockItem> SELENIC_FAUNA_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.SELENIC_FAUNA_BLOCK);
    public static final DeferredItem<BlockItem> SELENIC_CORE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.SELENIC_CORE_BLOCK);
    public static final DeferredItem<BlockItem> SELENIC_VINE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.SELENIC_VINE_BLOCK);

    public static final DeferredItem<Item> SILICON_CRYSTAL_ITEM = ITEMS.registerItem("silicon_crystal", Item::new);
    public static final DeferredItem<Item> RAW_SILICON_ITEM = ITEMS.registerItem("raw_silicon", Item::new);
    public static final DeferredItem<BlockItem> MOONSTONE_SILICON_ORE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.MOONSTONE_SILICON_ORE_BLOCK);
    public static final DeferredItem<BlockItem> HYPER_RELAY_ENGINE_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.HYPER_RELAY_ENGINE_BLOCK);

    public static final Supplier<ItemStack> CREATIVE_JETPACK_ITEM = () -> {
        var item = JETPACK_ITEM.toStack();
        item.set(JetpackItem.JetpackComponent.SUPPLIER, new JetpackItem.JetpackComponent(100, -1));

        return item;
    };
    public static final DeferredItem<JetHelmetItem> JET_HELMET_ITEM = ITEMS.register("jet_helmet", JetHelmetItem::new);
    public static final Supplier<ItemStack> CREATIVE_JET_HELMET_ITEM = () -> {
        var item = JET_HELMET_ITEM.toStack();
        item.set(JetHelmetItem.JetHelmetComponent.SUPPLIER, JetHelmetItem.JetHelmetComponent.creative());
        return item;
    };
    public static final DeferredItem<RocketBoosterItem> ROCKET_BOOSTER_ITEM = ITEMS.register("rocket_booster", RocketBoosterItem::new);
    // The terminal item opens both the star map and the Paradise Probe.
    public static final DeferredItem<StarMapItem> DEEPSPACE_TERMINAL_ITEM =
            ITEMS.register("deepspace_terminal", StarMapItem::new);
    public static void register(IEventBus eventBus) {
        JetpackItem.JetpackComponent.register(eventBus);
        JetHelmetItem.JetHelmetComponent.register(eventBus);
        eventBus.addListener(ModItems::registerCapabilities);

        ITEMS.register(eventBus);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Expose the helmet as a standard NeoForge oxygen-only fluid container.
        event.registerItem(Capabilities.FluidHandler.ITEM,
                (stack, context) -> new JetHelmetItem.OxygenFluidHandler(stack),
                JET_HELMET_ITEM.get());
    }
}
