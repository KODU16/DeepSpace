package world.landfall.deepspace.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.integration.InfiniteDimensionsIntegration;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

import java.util.Comparator;

/**
 * Registers /deepspace debug summon_ring_world, which generates a random ring world
 * galaxy and its hyper relay at the location connected to the initial galaxy.
 */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class RingWorldCommand {

    private RingWorldCommand() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("deepspace")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("debug")
                        .then(Commands.literal("summon_ring_world")
                                .executes(RingWorldCommand::summonRingWorld)
                                .then(Commands.literal("broken")
                                        .then(Commands.argument("count", IntegerArgumentType.integer())
                                                .executes(RingWorldCommand::summonBrokenRingWorld))))
                        .then(Commands.literal("output_ring_world_edge_pos")
                                .executes(RingWorldCommand::outputRingWorldEdgePositions))
                        .then(Commands.literal("set_ring_world_edge")
                                .then(Commands.literal("all")
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .executes(RingWorldCommand::moveAllRingWorldEdges))))
                                .then(Commands.argument("i", IntegerArgumentType.integer())
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .executes(RingWorldCommand::setRingWorldEdge)))))));
    }

    private static int summonRingWorld(CommandContext<CommandSourceStack> context) {
        return summonRingWorld(context, 0);
    }

    /** Generates a forced ring world with an exact random subset of zero to three broken sections. */
    private static int summonBrokenRingWorld(CommandContext<CommandSourceStack> context) {
        int count = IntegerArgumentType.getInteger(context, "count");
        if (count < 0 || count >= 4) {
            context.getSource().sendFailure(Component.literal("损坏环棱数量必须为 0~3；4 及以上的数无效。"));
            return 0;
        }
        return summonRingWorld(context, count);
    }

    private static int summonRingWorld(CommandContext<CommandSourceStack> context, int brokenSectionCount) {
        CommandSourceStack source = context.getSource();
        try {
            Galaxy galaxy = InfiniteDimensionsIntegration.summonRingWorldAtPrimary(
                    source.getServer(),
                    brokenSectionCount
            );
            source.sendSuccess(() -> Component.literal(
                    "Summoned ring world galaxy " + galaxy.name() + " (" + galaxy.dimension().location()
                            + ") with " + galaxy.brokenRingSectionCount()
                            + " broken sections and a hyper relay in the initial galaxy."
            ), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to summon ring world: " + exception.getMessage()));
            return 0;
        }
    }

    /** 按 0~3 顺序在聊天框输出可直接用于默认中心数组的四条棱 X/Z 坐标。 */
    private static int outputRingWorldEdgePositions(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(source.getLevel().dimension());
        var ringEdges = galaxy == null
                ? java.util.List.<Planet>of()
                : PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                        .filter(Planet::isRingWorldEdge)
                        .sorted(Comparator.comparing(Planet::getId))
                        .toList();
        if (galaxy == null || ringEdges.size() != 4) {
            source.sendFailure(Component.literal("需要在环世界星系的太空维度中使用该命令。"));
            return 0;
        }

        StringBuilder positions = new StringBuilder("当前环世界棱中心（0~3）：\n");
        for (int index = 0; index < ringEdges.size(); index++) {
            Vec3 center = ringEdges.get(index).getCenter();
            positions.append('{')
                    .append(Double.toString(center.x))
                    .append(", ")
                    .append(Double.toString(center.z))
                    .append('}');
            if (index < ringEdges.size() - 1) {
                positions.append(",\n");
            }
        }
        // 输出前强制同步当前位置，使客户端 OBJ 与动态世界面使用同一组中心坐标。
        PlanetRegistry.syncToAllPlayers();
        source.sendSuccess(() -> Component.literal(positions.toString()), false);
        return 1;
    }

    /** 临时移动当前环世界星系的一条棱，便于在游戏中校准四段连接位置。 */
    private static int setRingWorldEdge(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(source.getLevel().dimension());
        var ringEdges = galaxy == null
                ? java.util.List.<Planet>of()
                : PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                        .filter(Planet::isRingWorldEdge)
                        .sorted(Comparator.comparing(Planet::getId))
                        .toList();
        if (galaxy == null || ringEdges.size() != 4) {
            source.sendFailure(Component.literal("需要在环世界星系的太空维度中使用该命令。"));
            return 0;
        }

        int index = IntegerArgumentType.getInteger(context, "i");
        if (index < 0 || index > 3) {
            source.sendFailure(Component.literal("环世界棱索引错误：i 必须为 0~3。"));
            return 0;
        }

        double x = DoubleArgumentType.getDouble(context, "x");
        double z = DoubleArgumentType.getDouble(context, "y");
        Planet moved = ringEdges.get(index).withHorizontalCenter(x, z);
        PlanetRegistry.registerPlanet(moved);
        PlanetRegistry.syncToAllPlayers();
        source.sendSuccess(
                () -> Component.literal(String.format(
                        "已将环世界棱 %d 的中心设为 (%.3f, %.3f)。",
                        index,
                        x,
                        z
                )),
                true
        );
        return 1;
    }

    /** 按各棱当前朝向，将四条棱同时沿恒星方向和模型头部方向平移。 */
    private static int moveAllRingWorldEdges(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(source.getLevel().dimension());
        var ringEdges = galaxy == null
                ? java.util.List.<Planet>of()
                : PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                        .filter(Planet::isRingWorldEdge)
                        .sorted(Comparator.comparing(Planet::getId))
                        .toList();
        if (galaxy == null || ringEdges.size() != 4) {
            source.sendFailure(Component.literal("需要在环世界星系的太空维度中使用该命令。"));
            return 0;
        }

        double towardStar = DoubleArgumentType.getDouble(context, "x");
        double towardHead = DoubleArgumentType.getDouble(context, "y");
        Vec3 starCenter = galaxy.sun().getCenter();
        for (Planet edge : ringEdges) {
            Vec3 center = edge.getCenter();
            double starDx = starCenter.x - center.x;
            double starDz = starCenter.z - center.z;
            double starDistance = Math.hypot(starDx, starDz);
            if (starDistance == 0.0D) {
                source.sendFailure(Component.literal("无法移动环世界棱：棱中心与恒星中心重合。"));
                return 0;
            }

            Vec3 headDirection = ringWorldEdgeHeadDirection(center, starCenter);
            double movedX = center.x + starDx / starDistance * towardStar + headDirection.x * towardHead;
            double movedZ = center.z + starDz / starDistance * towardStar + headDirection.z * towardHead;
            PlanetRegistry.registerPlanet(edge.withHorizontalCenter(movedX, movedZ));
        }
        PlanetRegistry.syncToAllPlayers();
        source.sendSuccess(
                () -> Component.literal(String.format(
                        "已将四条环世界棱向恒星方向移动 %.3f、向棱头部方向移动 %.3f。",
                        towardStar,
                        towardHead
                )),
                true
        );
        return 1;
    }

    /** 与世界渲染旋转保持一致，返回模型局部 +Z 所指的水平棱头方向。 */
    private static Vec3 ringWorldEdgeHeadDirection(Vec3 center, Vec3 starCenter) {
        double dx = center.x - starCenter.x;
        double dz = center.z - starCenter.z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new Vec3(0.0D, 0.0D, dx >= 0.0D ? 1.0D : -1.0D);
        }
        return new Vec3(dz >= 0.0D ? -1.0D : 1.0D, 0.0D, 0.0D);
    }
}
