package world.landfall.deepspace.blockentity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.ryanhcode.sable.Sable;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.VeilRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModAttatchments;
import world.landfall.deepspace.ModBlocks;
import world.landfall.deepspace.item.JetHelmetItem;
import world.landfall.deepspace.integration.IrisIntegration;
import world.landfall.deepspace.render.shapes.Sphere;

/** Provides ship-aware oxygen and helmet charging without a kinetic network. */
public class OxygenatorBlockEntity extends BlockEntity {
    public static final BlockEntityType<OxygenatorBlockEntity> TYPE = BlockEntityType.Builder.of(
            OxygenatorBlockEntity::new, ModBlocks.OXYGENATOR_BLOCK.get()).build(null);
    private boolean enabled;
    private int radius = 4;

    public OxygenatorBlockEntity(BlockPos pos, BlockState state) {
        super(TYPE, pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, OxygenatorBlockEntity entity) {
        if (level.isClientSide || level.getGameTime() % 10 != 0 || !state.is(ModBlocks.OXYGENATOR_BLOCK.get())) return;
        int signal = level.getBestNeighborSignal(pos);
        boolean enabled = signal > 0;
        int radius = radiusForSignal(signal);
        if (entity.enabled != enabled || entity.radius != radius) {
            entity.enabled = enabled;
            entity.radius = radius;
            entity.setChanged();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
        if (!enabled) return;
        Vec3 center = Sable.HELPER.projectOutOfSubLevel(level, pos.getCenter());
        // Compare projected positions so players and dropped helmets aboard the same ship are included.
        for (var player : level.players()) {
            if (Sable.HELPER.projectOutOfSubLevel(level, player.position()).distanceToSqr(center) < radius * radius) {
                player.setData(ModAttatchments.LAST_OXYGENATED, 0f);
                refillHelmet(player.getItemBySlot(EquipmentSlot.HEAD));
            }
        }
        // Search both plot and world coordinates when the generator is mounted on a ship.
        var nearbyItems = new java.util.LinkedHashSet<>(level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(radius)));
        nearbyItems.addAll(level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(center, center).inflate(radius)));
        for (var item : nearbyItems) {
            if (Sable.HELPER.projectOutOfSubLevel(level, item.position()).distanceToSqr(center) < radius * radius) {
                refillHelmet(item.getItem());
            }
        }
    }

    /** Redstone strength scales the former 4-to-32-block oxygen range. */
    public static int radiusForSignal(int signal) {
        return 4 + 28 * Math.clamp(signal, 0, 15) / 15;
    }

    private static void refillHelmet(ItemStack stack) {
        var oxygen = stack.get(JetHelmetItem.JetHelmetComponent.SUPPLIER);
        if (oxygen != null && !oxygen.infinite() && oxygen.amount() < oxygen.capacity()) {
            stack.set(JetHelmetItem.JetHelmetComponent.SUPPLIER, oxygen.withAmount(oxygen.amount() + 25));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Enabled", enabled);
        tag.putInt("Radius", radius);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        enabled = tag.getBoolean("Enabled");
        radius = Math.clamp(tag.getInt("Radius"), 4, 32);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Keeps the authored bubble while the block model supplies its standalone casing. */
    public static class Renderer implements BlockEntityRenderer<OxygenatorBlockEntity> {
        private Sphere sphere;
        private int renderedRadius;
        private static final RenderStateShard.ShaderStateShard BUBBLE_SHADER = new RenderStateShard.ShaderStateShard(() ->
                VeilRenderBridge.toShaderInstance(VeilRenderSystem.setShader(Deepspace.path("bubble"))));
        private static final RenderType BUBBLE = bubbleType(false);
        private static final RenderType SHADER_BUBBLE = bubbleType(true);

        public Renderer(BlockEntityRendererProvider.Context context) {}

        private static RenderType bubbleType(boolean shaderPack) {
            var state = RenderType.CompositeState.builder().setShaderState(BUBBLE_SHADER)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setTransparencyState(shaderPack ? RenderStateShard.GLINT_TRANSPARENCY : RenderStateShard.ADDITIVE_TRANSPARENCY)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE).createCompositeState(true);
            return RenderType.create("deepspace_oxygen_bubble", DefaultVertexFormat.BLOCK,
                    VertexFormat.Mode.TRIANGLES, 186432, true, false, state);
        }

        @Override
        public void render(OxygenatorBlockEntity entity, float partialTick, PoseStack poseStack,
                           MultiBufferSource buffers, int light, int overlay) {
            if (!entity.enabled || entity.getLevel() == null) return;
            if (sphere == null || renderedRadius != entity.radius) {
                renderedRadius = entity.radius;
                sphere = new Sphere(renderedRadius, 32, 32);
            }
            var shader = VeilRenderSystem.setShader(Deepspace.path("bubble"));
            shader.getUniform("Time").setFloat((entity.getLevel().getDayTime() + partialTick) / 2f);
            RenderSystem.setShaderTexture(0, Deepspace.path("textures/atmosphere.png"));
            var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            Vec3 center = Sable.HELPER.projectOutOfSubLevel(entity.getLevel(), entity.getBlockPos().getCenter());
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            sphere.render(poseStack, buffer, center.subtract(camera).toVector3f(), new Quaternionf());
            (IrisIntegration.isShaderPackEnabled() ? SHADER_BUBBLE : BUBBLE).draw(buffer.buildOrThrow());
        }

        @Override
        public boolean shouldRenderOffScreen(OxygenatorBlockEntity entity) { return true; }

        @Override
        public int getViewDistance() { return 500; }
    }
}
