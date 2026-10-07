package world.landfall.deepspace.planet;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.List;

/**
 * Describes an independently registered Deep Space galaxy dimension.
 */
public record Galaxy(
        @NotNull String id,
        @NotNull String name,
        @NotNull ResourceKey<Level> dimension,
        @NotNull Vec3 arrival,
        @NotNull Sun sun,
        @NotNull List<Sun> suns,
        int brokenRingSections,
        int repairableBrokenSections
) {
    /** Keeps single-star callers source-compatible while the primary sun remains the lighting reference. */
    public Galaxy(String id, String name, ResourceKey<Level> dimension, Vec3 arrival, Sun sun) {
        this(id, name, dimension, arrival, sun, List.of(sun), 0);
    }

    /** Keeps multi-star callers source-compatible; ordinary galaxies have no broken ring sections. */
    public Galaxy(String id, String name, ResourceKey<Level> dimension, Vec3 arrival, Sun sun, List<Sun> suns) {
        this(id, name, dimension, arrival, sun, suns, 0);
    }

    public Galaxy(String id, String name, ResourceKey<Level> dimension, Vec3 arrival, Sun sun,
                  List<Sun> suns, int brokenRingSections) {
        this(id, name, dimension, arrival, sun, suns, brokenRingSections, 0);
    }

    public Galaxy {
        Objects.requireNonNull(id, "Galaxy ID cannot be null");
        Objects.requireNonNull(name, "Galaxy name cannot be null");
        Objects.requireNonNull(dimension, "Galaxy dimension cannot be null");
        Objects.requireNonNull(arrival, "Galaxy arrival cannot be null");
        Objects.requireNonNull(sun, "Galaxy sun cannot be null");
        List<Sun> originalSuns = List.copyOf(Objects.requireNonNull(suns, "Galaxy suns cannot be null"));
        int primaryIndex = originalSuns.indexOf(sun);
        if (originalSuns.isEmpty() || primaryIndex < 0) {
            throw new IllegalArgumentException("Galaxy suns must contain its primary sun");
        }
        // Keep intentional names (such as the save name); only replace the generic "Sun" placeholder.
        List<Sun> namedSuns = new java.util.ArrayList<>(originalSuns.size());
        for (int index = 0; index < originalSuns.size(); index++) {
            Sun original = originalSuns.get(index);
            namedSuns.add("Sun".equals(original.getName())
                    ? original.withName(StarIdentity.name(name, originalSuns.size(), index))
                    : original);
        }
        suns = List.copyOf(namedSuns);
        sun = suns.get(primaryIndex);
        // Normalize synchronized and legacy masks so every current ring world uses the complete model.
        brokenRingSections = RingWorldDamage.activeMask(brokenRingSections);
        repairableBrokenSections = RingWorldDamage.activeMask(repairableBrokenSections);
        if ((repairableBrokenSections & ~brokenRingSections) != 0) {
            throw new IllegalArgumentException("Repairable sections must be broken sections");
        }
    }

    /** Arrival uses save geometry while its serialized component stays canonical. */
    public Vec3 arrival() {
        return SpaceObjectScale.arrival(arrival, this);
    }

    public Vec3 unscaledArrival() { return arrival; }

    public boolean isRingWorldSectionBroken(int sectionIndex) {
        return RingWorldDamage.isBroken(brokenRingSections, sectionIndex);
    }

    public int brokenRingSectionCount() {
        return RingWorldDamage.count(brokenRingSections);
    }

    public void toNetwork(@NotNull FriendlyByteBuf buffer) {
        buffer.writeUtf(id);
        buffer.writeUtf(name);
        buffer.writeResourceLocation(dimension.location());
        buffer.writeVec3(arrival);
        buffer.writeCollection(suns, (target, value) -> value.toNetwork(target));
        buffer.writeVarInt(suns.indexOf(sun));
        buffer.writeVarInt(brokenRingSections);
        buffer.writeVarInt(repairableBrokenSections);
    }

    @NotNull
    public static Galaxy fromNetwork(@NotNull FriendlyByteBuf buffer) {
        String id = buffer.readUtf();
        String name = buffer.readUtf();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
        Vec3 arrival = buffer.readVec3();
        List<Sun> suns = buffer.readList(Sun::fromNetwork);
        int primaryIndex = buffer.readVarInt();
        int brokenRingSections = buffer.readVarInt();
        int repairableBrokenSections = buffer.readVarInt();
        if (suns.isEmpty() || primaryIndex < 0 || primaryIndex >= suns.size()) {
            throw new IllegalArgumentException("Invalid synchronized galaxy star list");
        }
        return new Galaxy(id, name, dimension, arrival, suns.get(primaryIndex), suns,
                brokenRingSections, repairableBrokenSections);
    }
}
