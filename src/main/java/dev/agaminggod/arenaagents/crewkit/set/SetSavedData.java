package dev.agaminggod.arenaagents.crewkit.set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persists the kitchen origin (so other features find it after a restart) and a palette-packed
 * snapshot of the terrain the set replaced, so teardown can put the world back.
 */
public final class SetSavedData extends SavedData {
	private static final Codec<int[]> INT_ARRAY = Codec.INT_STREAM.xmap(IntStream::toArray, Arrays::stream);
	private static final Codec<SetSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BlockPos.CODEC.optionalFieldOf("origin").forGetter(data -> Optional.ofNullable(data.origin)),
			Codec.STRING.optionalFieldOf("dimension", "minecraft:overworld").forGetter(data -> data.dimension),
			Codec.BOOL.optionalFieldOf("built", false).forGetter(data -> data.built),
			BlockState.CODEC.listOf().optionalFieldOf("snapshot_palette", List.of()).forGetter(data -> data.palette),
			INT_ARRAY.optionalFieldOf("snapshot", new int[0]).forGetter(data -> data.snapshot),
			Codec.INT.optionalFieldOf("generation", 0).forGetter(data -> data.generation)
	).apply(instance, SetSavedData::new));
	public static final SavedDataType<SetSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "crewkit_set"),
			SetSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	BlockPos origin;
	String dimension = "minecraft:overworld";
	boolean built;
	List<BlockState> palette = List.of();
	int[] snapshot = new int[0];
	/** Bumped on every build; markers carry it so stale ones from unloaded chunks can be dropped on load. */
	int generation;

	public SetSavedData() {
	}

	private SetSavedData(Optional<BlockPos> origin, String dimension, boolean built, List<BlockState> palette, int[] snapshot, int generation) {
		this.origin = origin.orElse(null);
		this.generation = generation;
		this.dimension = dimension;
		this.built = built;
		this.palette = List.copyOf(palette);
		this.snapshot = snapshot;
	}

	public static SetSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Optional<BlockPos> origin() {
		return Optional.ofNullable(origin);
	}

	boolean hasSnapshot() {
		return snapshot.length > 0 && !palette.isEmpty();
	}

	void update(BlockPos origin, String dimension, boolean built, List<BlockState> palette, int[] snapshot) {
		this.origin = origin;
		this.dimension = dimension;
		this.built = built;
		this.palette = List.copyOf(palette);
		this.snapshot = snapshot;
		setDirty();
	}

	int nextGeneration() {
		generation++;
		setDirty();
		return generation;
	}
}
