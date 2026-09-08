package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** World-scoped persistence for the deliberately opt-in skit workspace. */
public final class SkitModeSavedData extends SavedData {

	private static final Codec<SkitStep> STEP_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("delay_ticks").forGetter(SkitStep::delayTicks),
			SkitPlacement.CODEC.fieldOf("placement").forGetter(SkitStep::placement),
			SkitActionCodec.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(SkitStep::actions),
			Codec.BOOL.optionalFieldOf("place_before_actions", true).forGetter(SkitStep::placeBeforeActions)
	).apply(instance, SkitStep::new));
	private static final Codec<SkitScript> SCRIPT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(SkitScript::name),
			Codec.STRING.fieldOf("agent").forGetter(SkitScript::agentSelector),
			STEP_CODEC.listOf().fieldOf("steps").forGetter(SkitScript::steps)
	).apply(instance, SkitScript::new));
	private static final Codec<SkitModeSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("enabled", false).forGetter(SkitModeSavedData::enabled),
			Codec.unboundedMap(Codec.STRING, SkitPlacement.CODEC).optionalFieldOf("placements", Map.of())
					.forGetter(data -> data.placements),
			SCRIPT_CODEC.listOf().optionalFieldOf("scripts", List.of()).forGetter(data -> List.copyOf(data.scripts.values())),
			ActorCodec.VALUE.listOf().optionalFieldOf("actors", List.of()).forGetter(SkitModeSavedData::actors),
			DirectorTake.CODEC.listOf().optionalFieldOf("takes", List.of()).forGetter(SkitModeSavedData::takes)
	).apply(instance, SkitModeSavedData::decode));
	public static final SavedDataType<SkitModeSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "skit_mode"), SkitModeSavedData::new, CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	private static final int MAX_SCRIPTS = 128;
	private final Map<String, DirectorTake> takes = new LinkedHashMap<>();
	public List<DirectorTake> takes() { return List.copyOf(takes.values()); }
	public DirectorTake take(String name) { return takes.get(name); }
	public void putTake(DirectorTake take) { if (!takes.containsKey(take.name()) && takes.size() >= 64) throw new IllegalArgumentException("Take library is full"); takes.put(take.name(), take); setDirty(); }
	public void removeTake(String name) { takes.remove(name); setDirty(); }
	private boolean enabled;
	private final Map<String, SkitPlacement> placements;
	private final Map<String, SkitScript> scripts;
	private final Map<dev.agaminggod.arenaagents.agent.AgentId, SkitActor> actors = new LinkedHashMap<>();
	private static final class ActorCodec {
		static final Codec<SkitActor> VALUE = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.xmap(dev.agaminggod.arenaagents.agent.AgentId::parse, Object::toString).fieldOf("id").forGetter(SkitActor::agentId),
				Codec.STRING.fieldOf("name").forGetter(SkitActor::name),
				Codec.STRING.fieldOf("appearance").forGetter(SkitActor::appearance),
				Codec.BOOL.optionalFieldOf("dead", false).forGetter(SkitActor::dead)
		).apply(instance, SkitActor::new));
	}

	public SkitModeSavedData() {
		this(false, Map.of(), List.of());
	}

	private SkitModeSavedData(boolean enabled, Map<String, SkitPlacement> placements, List<SkitScript> scripts) {
		this.enabled = enabled;
		this.placements = new LinkedHashMap<>(Objects.requireNonNull(placements, "placements must not be null"));
		this.scripts = new LinkedHashMap<>();
		if (scripts.size() > MAX_SCRIPTS) throw new IllegalArgumentException("Too many persisted skit scripts");
		for (SkitScript script : scripts) {
			if (this.scripts.put(script.name(), script) != null) throw new IllegalArgumentException("Duplicate skit script: " + script.name());
		}
	}

	private static SkitModeSavedData decode(boolean enabled, Map<String, SkitPlacement> placements, List<SkitScript> scripts, List<SkitActor> actors, List<DirectorTake> takes) {
		SkitModeSavedData data = new SkitModeSavedData(enabled, placements, scripts);
		for (SkitActor actor : actors) data.putActor(actor);
		for (DirectorTake take : takes) { if (data.take(take.name()) != null) throw new IllegalArgumentException("Duplicate take"); data.putTake(take); }
		return data;
	}

	public List<SkitActor> actors() { return List.copyOf(actors.values()); }
	public void putActor(SkitActor actor) {
		if (!actors.containsKey(actor.agentId()) && actors.size() >= 32) throw new IllegalArgumentException("Director cast is full");
		for (SkitActor other : actors.values()) {
			if (!other.agentId().equals(actor.agentId()) && other.name().equalsIgnoreCase(actor.name()))
				throw new IllegalArgumentException("Duplicate actor name");
		}
		actors.put(actor.agentId(), actor);
		setDirty();
	}
	public void removeActor(dev.agaminggod.arenaagents.agent.AgentId id) {
		actors.remove(id);
		placements.remove(id.toString());
		setDirty();
	}

	public static SkitModeSavedData get(MinecraftServer server) {
		return Objects.requireNonNull(server, "server must not be null").overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public boolean enabled() { return enabled; }

	public void setEnabled(boolean enabled) {
		if (this.enabled != enabled) { this.enabled = enabled; setDirty(); }
	}

	public Map<String, SkitPlacement> placements() { return Map.copyOf(placements); }

	public SkitPlacement placement(String agentSelector) { return placements.get(agentSelector); }

	public void putPlacement(String agentSelector, SkitPlacement placement) {
		placements.put(Objects.requireNonNull(agentSelector), Objects.requireNonNull(placement));
		setDirty();
	}

	public SkitScript script(String name) { return scripts.get(name); }

	public List<SkitScript> scripts() { return List.copyOf(scripts.values()); }

	public void createScript(SkitScript script) {
		if (scripts.containsKey(script.name())) throw new dev.agaminggod.arenaagents.agent.AgentDomainException(
				"SKIT_SCRIPT_EXISTS", "A script named " + script.name() + " already exists. Select it to edit, or choose a new name");
		putScript(script);
	}

	public void putScript(SkitScript script) {
		Objects.requireNonNull(script, "script must not be null");
		if (!scripts.containsKey(script.name()) && scripts.size() >= MAX_SCRIPTS) throw new IllegalArgumentException("Too many skit scripts");
		scripts.put(script.name(), script);
		setDirty();
	}

	public boolean removeScript(String name) {
		if (scripts.remove(name) == null) return false;
		setDirty();
		return true;
	}
}
