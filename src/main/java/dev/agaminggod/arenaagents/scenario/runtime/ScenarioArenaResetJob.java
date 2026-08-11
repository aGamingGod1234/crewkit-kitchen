package dev.agaminggod.arenaagents.scenario.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded canonicalize/apply/exhaustive-verify reset pipeline. */
public final class ScenarioArenaResetJob {
	public static final int MAXIMUM_WORK_PER_TICK = 2_048;
	public static final long MAXIMUM_TICK_NANOS = 4_000_000L;

	private final List<ScenarioArenaBlueprint.Placement> source;
	private final TreeMap<Long, ScenarioArenaBlueprint.Placement> canonicalByPosition = new TreeMap<>();
	private final ArrayList<ScenarioArenaBlueprint.Placement> canonicalBuilder = new ArrayList<>();
	private List<ScenarioArenaBlueprint.Placement> canonical = List.of();
	private Iterator<ScenarioArenaBlueprint.Placement> canonicalIterator;
	private MessageDigest blueprintHasher;
	private Phase phase = Phase.CANONICALIZE;
	private int sourceIndex;
	private int phaseIndex;
	private int applied;
	private int verified;
	private int mismatches;
	private String blueprintHash = "unavailable";
	private MessageDigest managedHasher;
	private ScenarioResetReceipt receipt;
	private String failureReason = "";

	public ScenarioArenaResetJob(List<ScenarioArenaBlueprint.Placement> placements) {
		this.source = List.copyOf(Objects.requireNonNull(placements, "placements must not be null"));
		for (ScenarioArenaBlueprint.Placement placement : source) {
			Objects.requireNonNull(placement, "placements must not contain null");
		}
	}

	public Tick tick(ServerLevel level) {
		Objects.requireNonNull(level, "level must not be null");
		if (phase == Phase.COMPLETE || phase == Phase.FAILED) return snapshotTick(0);
		long started = System.nanoTime();
		int worked = 0;
		while (worked < MAXIMUM_WORK_PER_TICK
				&& (worked == 0 || System.nanoTime() - started < MAXIMUM_TICK_NANOS)) {
			if (!step(level)) break;
			worked++;
		}
		return snapshotTick(worked);
	}

	private boolean step(ServerLevel level) {
		return switch (phase) {
			case CANONICALIZE -> canonicalizeOne();
			case APPLY -> applyOne(level);
			case VERIFY -> verifyOne(level);
			case COMPLETE, FAILED -> false;
		};
	}

	private boolean canonicalizeOne() {
		if (sourceIndex < source.size()) {
			ScenarioArenaBlueprint.Placement placement = source.get(sourceIndex++);
			canonicalByPosition.put(placement.position().asLong(), placement);
			if (sourceIndex == source.size()) beginCanonicalFinalization();
			return true;
		}
		if (canonicalIterator == null) beginCanonicalFinalization();
		if (!canonicalIterator.hasNext()) {
			finishCanonicalization();
			return false;
		}
		ScenarioArenaBlueprint.Placement canonicalPlacement = canonicalIterator.next();
		canonicalBuilder.add(canonicalPlacement);
		updateHash(blueprintHasher, canonicalPlacement.position(), canonicalPlacement.state());
		if (!canonicalIterator.hasNext()) finishCanonicalization();
		return true;
	}

	private void beginCanonicalFinalization() {
		if (canonicalIterator != null) return;
		canonicalIterator = canonicalByPosition.values().iterator();
		blueprintHasher = sha256();
	}

	private void finishCanonicalization() {
		canonical = List.copyOf(canonicalBuilder);
		blueprintHash = HexFormat.of().formatHex(blueprintHasher.digest());
		phase = Phase.APPLY;
		phaseIndex = 0;
	}

	private boolean applyOne(ServerLevel level) {
		if (phaseIndex >= canonical.size()) {
			phase = Phase.VERIFY;
			phaseIndex = 0;
			managedHasher = sha256();
			return false;
		}
		ScenarioArenaBlueprint.Placement placement = canonical.get(phaseIndex);
		if (!level.hasChunkAt(placement.position())) {
			fail("UNLOADED_MANAGED_CHUNK");
			return false;
		}
		level.setBlock(placement.position(), placement.state(), 2);
		phaseIndex++;
		applied++;
		if (phaseIndex == canonical.size()) {
			phase = Phase.VERIFY;
			phaseIndex = 0;
			managedHasher = sha256();
		}
		return true;
	}

	private boolean verifyOne(ServerLevel level) {
		if (phaseIndex >= canonical.size()) {
			completeVerification();
			return false;
		}
		ScenarioArenaBlueprint.Placement expected = canonical.get(phaseIndex);
		BlockPos position = expected.position();
		if (!level.hasChunkAt(position)) {
			fail("UNLOADED_MANAGED_CHUNK");
			return false;
		}
		BlockState actual = level.getBlockState(position);
		updateHash(managedHasher, position, actual);
		if (!actual.equals(expected.state())) mismatches++;
		phaseIndex++;
		verified++;
		if (phaseIndex == canonical.size()) completeVerification();
		return true;
	}

	private void completeVerification() {
		String managedHash = HexFormat.of().formatHex(managedHasher.digest());
		boolean matches = mismatches == 0 && managedHash.equals(blueprintHash);
		receipt = new ScenarioResetReceipt(
				blueprintHash, managedHash, canonical.size(), applied, verified, matches
		);
		phase = matches ? Phase.COMPLETE : Phase.FAILED;
		if (!matches) failureReason = "RESET_VERIFICATION_MISMATCH";
	}

	private void fail(String reason) {
		failureReason = reason;
		phase = Phase.FAILED;
		receipt = new ScenarioResetReceipt(
				blueprintHash, "unavailable", canonical.size(), applied, verified, false
		);
	}

	public Phase phase() {
		return phase;
	}

	public int totalPlacements() {
		return phase == Phase.CANONICALIZE ? source.size() : canonical.size();
	}

	public int completedWork() {
		return switch (phase) {
			case CANONICALIZE -> sourceIndex;
			case APPLY -> applied;
			case VERIFY, COMPLETE, FAILED -> verified;
		};
	}

	public Optional<ScenarioResetReceipt> receipt() {
		return Optional.ofNullable(receipt);
	}

	public String failureReason() {
		return failureReason;
	}

	public List<ScenarioArenaBlueprint.Placement> canonicalPlacements() {
		return canonical;
	}

	private Tick snapshotTick(int worked) {
		return new Tick(phase, worked, completedWork(), totalPlacements(), receipt(), failureReason);
	}

	public static List<ScenarioArenaBlueprint.Placement> canonicalize(
			List<ScenarioArenaBlueprint.Placement> placements
	) {
		TreeMap<Long, ScenarioArenaBlueprint.Placement> canonical = new TreeMap<>();
		for (ScenarioArenaBlueprint.Placement placement : List.copyOf(placements)) {
			canonical.put(placement.position().asLong(), placement);
		}
		return List.copyOf(canonical.values());
	}

	public static String hash(List<ScenarioArenaBlueprint.Placement> placements) {
		return hashCanonical(canonicalize(placements));
	}

	public static ScenarioResetReceipt verifyCanonical(
			List<ScenarioArenaBlueprint.Placement> expectedPlacements,
			List<ScenarioArenaBlueprint.Placement> actualPlacements
	) {
		List<ScenarioArenaBlueprint.Placement> expected = canonicalize(expectedPlacements);
		List<ScenarioArenaBlueprint.Placement> actual = canonicalize(actualPlacements);
		String expectedHash = hashCanonical(expected);
		String actualHash = hashCanonical(actual);
		boolean exact = expected.size() == actual.size();
		if (exact) {
			for (int index = 0; index < expected.size(); index++) {
				ScenarioArenaBlueprint.Placement wanted = expected.get(index);
				ScenarioArenaBlueprint.Placement observed = actual.get(index);
				if (!wanted.position().equals(observed.position()) || !wanted.state().equals(observed.state())) {
					exact = false;
				}
			}
		}
		return new ScenarioResetReceipt(
				expectedHash,
				actualHash,
				expected.size(),
				expected.size(),
				expected.size(),
				exact && expectedHash.equals(actualHash)
		);
	}

	private static String hashCanonical(List<ScenarioArenaBlueprint.Placement> placements) {
		MessageDigest digest = sha256();
		for (ScenarioArenaBlueprint.Placement placement : placements) {
			updateHash(digest, placement.position(), placement.state());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void updateHash(MessageDigest digest, BlockPos position, BlockState state) {
		String entry = position.getX() + "," + position.getY() + "," + position.getZ()
				+ "=" + stableStateKey(state) + "\n";
		digest.update(entry.getBytes(StandardCharsets.UTF_8));
	}

	private static String stableStateKey(BlockState state) {
		StringBuilder value = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		state.getValues()
				.sorted(Comparator.comparing(entry -> entry.property().getName()))
				.forEach(entry -> value.append('|')
						.append(entry.property().getName()).append('=').append(entry.valueName()));
		return value.toString();
	}

	public static int batchEnd(int startInclusive, int totalSize, int maximum) {
		if (startInclusive < 0 || totalSize < startInclusive || maximum < 1) {
			throw new IllegalArgumentException("invalid reset batch bounds");
		}
		return (int) Math.min((long) totalSize, (long) startInclusive + maximum);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	public enum Phase {
		CANONICALIZE,
		APPLY,
		VERIFY,
		COMPLETE,
		FAILED
	}

	public record Tick(
			Phase phase,
			int worked,
			int completed,
			int total,
			Optional<ScenarioResetReceipt> receipt,
			String failureReason
	) {
		public Tick {
			Objects.requireNonNull(phase, "phase must not be null");
			if (worked < 0 || completed < 0 || total < 0) throw new IllegalArgumentException("progress is invalid");
			receipt = Objects.requireNonNull(receipt, "receipt must not be null");
			failureReason = Objects.requireNonNull(failureReason, "failureReason must not be null");
		}
	}
}
