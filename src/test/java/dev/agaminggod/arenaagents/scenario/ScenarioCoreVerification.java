package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioLoadoutPlan;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioLoadoutService;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioEventMarker;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParkourCourse;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioPlacementBatchPolicy;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRosterReadinessBarrier;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRosterActivator;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeClock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ScenarioCoreVerification {
	private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
	private static final long CREATED_AT_EPOCH_MS = 1_750_000_000_000L;

	private ScenarioCoreVerification() {
	}

	public static void main(String[] args) {
		int assertions = verify();
		System.out.printf("PASS: %d scenario core assertions%n", assertions);
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyBuiltInPresets();
		assertions += verifySessionConfigValidation();
		assertions += verifyDeterministicSpawnAllocation();
		assertions += verifyBuildingPlotSpawnAlignment();
		assertions += verifyControllerReachableParkourCourse();
		assertions += verifyStandardizedLoadouts();
		assertions += verifyLoadoutApplication();
		assertions += verifyRosterReadinessBarrier();
		assertions += verifyRosterActivationOrder();
		assertions += verifyPlacementBatchPolicy();
		assertions += verifyDeterministicDirectorEvents();
		assertions += verifySafeEventMarkers();
		assertions += verifyRuntimeClockDispatch();
		assertions += verifyPhases();
		assertions += verifyLifecycle();
		assertions += verifyScoringAndEvidence();
		assertions += verifyReset();
		return assertions;
	}

	private static int verifyRosterReadinessBarrier() {
		ScenarioRosterReadinessBarrier barrier = new ScenarioRosterReadinessBarrier(
				List.of("agent-a", "agent-b", "agent-c"),
				240L
		);
		ScenarioRosterReadinessBarrier.Assessment partial = barrier.assess(
				0L,
				List.of("agent-a", "agent-c")
		);
		assertEquals(
				ScenarioRosterReadinessBarrier.Status.WAITING,
				partial.status(),
				"partial fake-player roster remains behind readiness barrier"
		);
		assertEquals(List.of("agent-b"), partial.missingIds(), "readiness barrier reports the missing contestant");
		assertEquals(
				ScenarioRosterReadinessBarrier.Status.READY,
				barrier.assess(239L, List.of("agent-c", "agent-b", "agent-a")).status(),
				"full fake-player roster becomes ready before timeout"
		);
		ScenarioRosterReadinessBarrier.Assessment timedOut = barrier.assess(240L, List.of("agent-a"));
		assertEquals(
				ScenarioRosterReadinessBarrier.Status.TIMED_OUT,
				timedOut.status(),
				"incomplete fake-player roster times out at the bounded deadline"
		);
		assertEquals(
				List.of("agent-b", "agent-c"),
				timedOut.missingIds(),
				"timed-out readiness barrier preserves deterministic missing order"
		);
		return 5;
	}

	private static int verifyRosterActivationOrder() {
		ArrayList<String> events = new ArrayList<>();
		new ScenarioRosterActivator().activate(
				List.of("agent-a", "agent-b", "agent-c"),
				agent -> events.add("load-" + agent),
				agent -> events.add("start-" + agent)
		);
		assertEquals(
				List.of(
						"load-agent-a",
						"load-agent-b",
						"load-agent-c",
						"start-agent-a",
						"start-agent-b",
						"start-agent-c"
				),
				events,
				"every contestant loadout completes before any goal starts"
		);
		return 1;
	}

	private static int verifyPlacementBatchPolicy() {
		ScenarioPlacementBatchPolicy policy = new ScenarioPlacementBatchPolicy(3, 4L);
		assertEquals(
				new ScenarioPlacementBatchPolicy.Window(2, 5),
				policy.window(2, 10),
				"placement batch caps inspections while work remains"
		);
		assertEquals(
				new ScenarioPlacementBatchPolicy.Window(9, 10),
				policy.window(9, 10),
				"placement batch preserves final partial progress"
		);
		assertEquals(
				new ScenarioPlacementBatchPolicy.Window(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
				policy.window(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
				"placement batch preserves progress without integer overflow"
		);
		assertTrue(policy.shouldContinue(0, 9L), "placement batch always permits one progress step");
		assertTrue(policy.shouldContinue(1, 3L), "placement batch continues inside its time budget");
		assertTrue(!policy.shouldContinue(1, 4L), "placement batch yields at its time budget");
		return 6;
	}

	private static int verifyControllerReachableParkourCourse() {
		ScenarioParkourCourse course = ScenarioParkourCourse.create();
		assertEquals(16, course.lanes().size(), "parkour course exposes sixteen independent lanes");
		assertEquals(
				new ScenarioParkourCourse.Platform(-23, 1, -24),
				course.lanes().getFirst().platforms().getFirst(),
				"first parkour lane starts at the authored west edge"
		);
		assertEquals(
				new ScenarioParkourCourse.Platform(22, 17, 24),
				course.lanes().getLast().platforms().getLast(),
				"last parkour lane reaches the common finish row"
		);
		assertTrue(
				course.lanes().stream().allMatch(lane -> {
					for (int index = 1; index < lane.platforms().size(); index++) {
						ScenarioParkourCourse.Platform previous = lane.platforms().get(index - 1);
						ScenarioParkourCourse.Platform next = lane.platforms().get(index);
						int zDistance = next.z() - previous.z();
						int yDistance = next.y() - previous.y();
						if (!((zDistance == 2 && yDistance == 0) || (zDistance == 1 && yDistance == 1))) {
							return false;
						}
					}
					return true;
				}),
				"every parkour transition is a supported gap jump or one-block step-up"
		);
		assertTrue(
				course.lanes().stream()
						.flatMap(lane -> lane.platforms().stream())
						.allMatch(platform -> platform.x() >= -23 && platform.x() <= 22
								&& platform.y() >= 1 && platform.y() <= 17
								&& platform.z() >= -24 && platform.z() <= 24),
				"parkour course stays inside the authored arena bounds"
		);

		List<Double> laneAxes = List.of(
				-23.0D, -20.0D, -17.0D, -14.0D, -11.0D, -8.0D, -5.0D, -2.0D,
				1.0D, 4.0D, 7.0D, 10.0D, 13.0D, 16.0D, 19.0D, 22.0D
		);
		int assertions = 5;
		for (int count = 1; count <= 16; count++) {
			List<ScenarioSpawn> allocated = new ScenarioSpawnAllocator().allocate(
					config(ScenarioPresets.require("thinking-tower"), 8_000L + count, 11L, participants(count))
			);
			assertEquals(
					count,
					new HashSet<>(allocated.stream().map(spawn -> spawn.x() + ":" + spawn.z()).toList()).size(),
					"parkour lane starts are unique " + count
			);
			assertTrue(
					allocated.stream().allMatch(spawn -> laneAxes.contains(spawn.x()) && spawn.z() == -24.0D),
					"parkour contestants spawn on authored lane starts " + count
			);
			assertions += 2;
		}
		return assertions;
	}

	private static int verifyStandardizedLoadouts() {
		ScenarioLoadoutPlan survival = ScenarioLoadoutPlan.forContestant(
				ScenarioCategory.SURVIVAL,
				AgentGameMode.SURVIVAL
		);
		assertTrue(survival.hasItem("minecraft:iron_pickaxe"), "survival loadout includes a pickaxe");
		assertTrue(survival.hasItem("minecraft:iron_axe"), "survival loadout includes an axe");
		assertTrue(survival.hasItem("minecraft:bread"), "survival loadout includes food");
		assertTrue(survival.hasItem("minecraft:torch"), "survival loadout includes torches");

		ScenarioLoadoutPlan building = ScenarioLoadoutPlan.forContestant(
				ScenarioCategory.BUILDING,
				AgentGameMode.SURVIVAL
		);
		assertTrue(building.hasItem("minecraft:stone_bricks"), "building palette includes structure blocks");
		assertTrue(building.hasItem("minecraft:glass"), "building palette includes glass");
		assertTrue(building.hasItem("minecraft:sea_lantern"), "building palette includes lighting");
		assertEquals(
				List.of(),
				ScenarioLoadoutPlan.forContestant(ScenarioCategory.BUILDING, AgentGameMode.CREATIVE).entries(),
				"creative builders do not receive a redundant palette"
		);

		ScenarioLoadoutPlan pvp = ScenarioLoadoutPlan.forContestant(ScenarioCategory.PVP, AgentGameMode.SURVIVAL);
		assertTrue(pvp.hasItem("minecraft:iron_sword"), "PvP loadout includes a weapon");
		assertTrue(pvp.hasItem("minecraft:cooked_beef"), "PvP loadout includes food");
		assertEquals(
				List.of(
						ScenarioLoadoutPlan.ArmorSlot.HEAD,
						ScenarioLoadoutPlan.ArmorSlot.CHEST,
						ScenarioLoadoutPlan.ArmorSlot.LEGS,
						ScenarioLoadoutPlan.ArmorSlot.FEET
				),
				pvp.entries().stream()
						.map(ScenarioLoadoutPlan.Entry::armorSlot)
						.filter(slot -> slot != ScenarioLoadoutPlan.ArmorSlot.NONE)
						.toList(),
				"PvP armor is equipped in every armor slot"
		);

		ScenarioLoadoutPlan parkour = ScenarioLoadoutPlan.forContestant(
				ScenarioCategory.PARKOUR,
				AgentGameMode.ADVENTURE
		);
		assertTrue(parkour.hasItem("minecraft:cooked_beef"), "parkour loadout includes food");
		assertTrue(
				parkour.entries().stream().anyMatch(entry -> entry.armorSlot() == ScenarioLoadoutPlan.ArmorSlot.FEET),
				"parkour loadout equips safe footwear"
		);
		assertEquals(
				survival.entries().size(),
				new HashSet<>(survival.entries().stream().map(ScenarioLoadoutPlan.Entry::inventorySlot).toList()).size(),
				"survival loadout uses unique inventory slots"
		);
		return 14;
	}

	private static int verifyLoadoutApplication() {
		RecordingLoadoutTarget target = new RecordingLoadoutTarget();
		new ScenarioLoadoutService().apply(
				ScenarioLoadoutPlan.forContestant(ScenarioCategory.PVP, AgentGameMode.SURVIVAL),
				target
		);
		assertEquals(1, target.resetCount, "loadout application resets stale inventory");
		assertEquals(
				List.of(
						"0=minecraft:iron_swordx1",
						"1=minecraft:bowx1",
						"2=minecraft:arrowx32",
						"3=minecraft:cooked_beefx16",
						"4=minecraft:shieldx1"
				),
				target.inventory,
				"PvP inventory entries are placed in deterministic slots"
		);
		assertEquals(
				List.of(
						"HEAD=minecraft:iron_helmetx1",
						"CHEST=minecraft:iron_chestplatex1",
						"LEGS=minecraft:iron_leggingsx1",
						"FEET=minecraft:iron_bootsx1"
				),
				target.armor,
				"PvP armor entries are equipped rather than left in inventory"
		);
		RecordingLoadoutTarget orderedTarget = new RecordingLoadoutTarget();
		new ScenarioLoadoutService().applyThenStart(
				ScenarioLoadoutPlan.forContestant(ScenarioCategory.SURVIVAL, AgentGameMode.SURVIVAL),
				orderedTarget,
				() -> orderedTarget.events.add("start")
		);
		assertEquals("start", orderedTarget.events.getLast(), "contestant starts only after its loadout is applied");
		return 4;
	}

	private static final class RecordingLoadoutTarget implements ScenarioLoadoutService.Target {
		private int resetCount;
		private final ArrayList<String> inventory = new ArrayList<>();
		private final ArrayList<String> armor = new ArrayList<>();
		private final ArrayList<String> events = new ArrayList<>();

		@Override
		public void reset() {
			resetCount++;
			inventory.clear();
			armor.clear();
			events.add("reset");
		}

		@Override
		public void putInventory(int slot, String itemId, int count) {
			inventory.add(slot + "=" + itemId + "x" + count);
			events.add("inventory");
		}

		@Override
		public void equip(ScenarioLoadoutPlan.ArmorSlot slot, String itemId, int count) {
			armor.add(slot + "=" + itemId + "x" + count);
			events.add("armor");
		}
	}

	private static int verifyBuiltInPresets() {
		List<ScenarioPreset> presets = ScenarioPresets.all();
		assertEquals(4, presets.size(), "built-in preset count");
		assertEquals(
				List.of(ScenarioCategory.SURVIVAL, ScenarioCategory.BUILDING, ScenarioCategory.PVP, ScenarioCategory.PARKOUR),
				presets.stream().map(ScenarioPreset::category).toList(),
				"preset category order"
		);
		assertEquals("The Last Valley", ScenarioPresets.require("last-valley").title(), "survival title");
		assertEquals("The Impossible Brief", ScenarioPresets.require("impossible-brief").title(), "building title");
		assertEquals("Citadel Collapse", ScenarioPresets.require("citadel-collapse").title(), "PvP title");
		assertEquals("The Thinking Tower", ScenarioPresets.require("thinking-tower").title(), "parkour title");
		assertEquals(1, ScenarioPresets.require("last-valley").minimumAgents(), "survival minimum");
		assertEquals(2, ScenarioPresets.require("citadel-collapse").minimumAgents(), "PvP minimum");
		assertEquals(16, ScenarioPresets.require("thinking-tower").maximumAgents(), "parkour maximum");
		for (ScenarioPreset preset : presets) {
			long scheduledTicks = preset.phases().stream().mapToLong(ScenarioPhase::durationTicks).sum();
			double totalWeight = preset.scoreRules().stream().mapToDouble(ScenarioScoreRule::weight).sum();
			assertEquals(preset.defaultDurationTicks(), scheduledTicks, preset.id() + " phase duration");
			assertDoubleEquals(100.0D, totalWeight, preset.id() + " score weight");
			assertTrue(!preset.landmarks().isEmpty(), preset.id() + " landmarks");
			assertTrue(!preset.presentationTags().isEmpty(), preset.id() + " presentation tags");
		}
		expectUnsupported(() -> presets.add(ScenarioPresets.require("last-valley")), "preset registry is immutable");
		expectUnsupported(
				() -> ScenarioPresets.require("last-valley").landmarks().add("unfair shortcut"),
				"preset metadata is immutable"
		);
		return 22;
	}

	private static int verifySessionConfigValidation() {
		ScenarioSessionConfig valid = config(
				ScenarioPresets.require("last-valley"),
				99L,
				123L,
				participants(2)
		);
		assertEquals(2, valid.participants().size(), "valid participant count");
		assertTrue(valid.deterministicEvents(), "deterministic events");
		expectFailure(
				() -> config(ScenarioPresets.require("citadel-collapse"), 1L, 2L, participants(1)),
				"AGENT_COUNT_OUT_OF_RANGE"
		);
		expectFailure(
				() -> new ScenarioSessionConfig(
						SESSION_ID,
						ScenarioPresets.require("last-valley"),
						1L,
						2L,
						ScenarioPresets.require("last-valley").defaultDurationTicks(),
						true,
						List.of(
								new ScenarioParticipant("agent-a", "First", Optional.empty()),
								new ScenarioParticipant("AGENT-A", "Duplicate", Optional.empty())
						),
						CREATED_AT_EPOCH_MS
				),
				"DUPLICATE_PARTICIPANT_ID"
		);
		expectFailure(
				() -> new ScenarioSessionConfig(
						SESSION_ID,
						ScenarioPresets.require("last-valley"),
						1L,
						2L,
						0L,
						true,
						participants(1),
						CREATED_AT_EPOCH_MS
				),
				"INVALID_DURATION"
		);
		return 5;
	}

	private static int verifyDeterministicSpawnAllocation() {
		ScenarioSpawnAllocator allocator = new ScenarioSpawnAllocator();
		ScenarioSessionConfig first = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(8));
		ScenarioSessionConfig same = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(8));
		ScenarioSessionConfig changed = config(ScenarioPresets.require("last-valley"), 92L, 7L, participants(8));
		List<ScenarioSpawn> firstAllocation = allocator.allocate(first);
		assertEquals(firstAllocation, allocator.allocate(same), "same seed allocation");
		assertTrue(!firstAllocation.equals(allocator.allocate(changed)), "different seed allocation");
		assertEquals(8, new HashSet<>(firstAllocation.stream().map(ScenarioSpawn::slotIndex).toList()).size(), "unique slots");
		assertEquals(
				List.of("agent-0", "agent-1", "agent-2", "agent-3", "agent-4", "agent-5", "agent-6", "agent-7"),
				firstAllocation.stream().map(ScenarioSpawn::participantId).sorted().toList(),
				"all participants allocated"
		);
		for (int count = 1; count <= 16; count++) {
			List<ScenarioSpawn> allocated = allocator.allocate(
					config(ScenarioPresets.require("thinking-tower"), 1_000L + count, 8L, participants(count))
			);
			assertEquals(count, allocated.size(), "allocation size " + count);
			assertEquals(count, new HashSet<>(allocated.stream().map(ScenarioSpawn::slotIndex).toList()).size(), "slot count " + count);
			assertTrue(
					allocated.stream().allMatch(spawn -> spawn.lane().startsWith("lane-")),
					"parkour lanes " + count
			);
		}
		return 52;
	}

	private static int verifyBuildingPlotSpawnAlignment() {
		ScenarioSpawnAllocator allocator = new ScenarioSpawnAllocator();
		List<Double> plotAxes = List.of(-36.0D, -12.0D, 12.0D, 36.0D);
		int assertions = 0;
		for (int count = 1; count <= 16; count++) {
			List<ScenarioSpawn> allocated = allocator.allocate(
					config(ScenarioPresets.require("impossible-brief"), 4_000L + count, 9L, participants(count))
			);
			assertEquals(
					count,
					new HashSet<>(allocated.stream().map(spawn -> spawn.x() + ":" + spawn.z()).toList()).size(),
					"building plot positions are unique " + count
			);
			assertTrue(
					allocated.stream().allMatch(spawn -> plotAxes.contains(spawn.x()) && plotAxes.contains(spawn.z())),
					"building contestants start at plot centers " + count
			);
			assertions += 2;
		}
		return assertions;
	}

	private static int verifyDeterministicDirectorEvents() {
		ScenarioEventDirector director = new ScenarioEventDirector();
		ScenarioSessionConfig first = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(4));
		ScenarioSessionConfig same = config(ScenarioPresets.require("last-valley"), 91L, 7L, participants(4));
		ScenarioSessionConfig changed = config(ScenarioPresets.require("last-valley"), 91L, 8L, participants(4));
		List<ScenarioDirectedEvent> firstSchedule = director.schedule(first);
		assertEquals(firstSchedule, director.schedule(same), "same event seed schedule");
		assertTrue(!firstSchedule.equals(director.schedule(changed)), "different event seed schedule");
		assertEquals(first.preset().dynamicEvents().size(), firstSchedule.size(), "all director events scheduled");
		assertEquals(
				new HashSet<>(first.preset().dynamicEvents()),
				new HashSet<>(firstSchedule.stream().map(ScenarioDirectedEvent::description).toList()),
				"director event descriptions preserved"
		);
		assertTrue(
				firstSchedule.stream().allMatch(event -> event.elapsedTick() >= 0L && event.elapsedTick() < first.durationTicks()),
				"director events stay within session"
		);
		for (int index = 0; index < firstSchedule.size(); index++) {
			assertEquals(index, firstSchedule.get(index).sequence(), "director sequence " + index);
		}
		return 10;
	}

	private static int verifySafeEventMarkers() {
		List<ScenarioDirectedEvent> schedule = new ScenarioEventDirector().schedule(
				config(ScenarioPresets.require("citadel-collapse"), 71L, 72L, participants(4))
		);
		List<ScenarioEventMarker> markers = schedule.stream().map(ScenarioEventMarker::forEvent).toList();
		assertEquals(
				markers.size(),
				new HashSet<>(markers).size(),
				"directed event markers use unique spectator-deck positions"
		);
		assertTrue(
				markers.stream().allMatch(marker ->
						marker.x() >= -6 && marker.x() <= 6
								&& marker.y() == 14
								&& marker.z() >= 67 && marker.z() <= 73),
				"directed event markers stay on the safe spectator deck"
		);
		assertEquals(
				new ScenarioEventMarker(-6, 14, 68),
				ScenarioEventMarker.forEvent(new ScenarioDirectedEvent(0, "first", "scouting", 100L, "First")),
				"first directed event marker is deterministic"
		);
		return 3;
	}

	private static int verifyRuntimeClockDispatch() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("last-valley"), 51L, 52L, participants(2))
		);
		session.markReady(0L);
		session.beginCountdown(0L);
		session.start(0L);
		ScenarioRuntimeClock clock = new ScenarioRuntimeClock(session);
		ArrayList<String> enteredPhases = new ArrayList<>();
		ArrayList<String> directedEvents = new ArrayList<>();
		int finishSignals = 0;
		for (long tick = 0L; tick <= session.config().durationTicks() + 1L; tick++) {
			ScenarioRuntimeClock.Update update = clock.tick();
			update.enteredPhase().ifPresent(phase -> enteredPhases.add(phase.id()));
			directedEvents.addAll(update.directedEvents().stream().map(ScenarioDirectedEvent::id).toList());
			if (update.finishedNow()) finishSignals++;
		}
		assertEquals(
				session.config().preset().phases().stream().map(ScenarioPhase::id).toList(),
				enteredPhases,
				"runtime clock enters every phase exactly once"
		);
		assertEquals(
				session.config().preset().dynamicEvents().size(),
				new HashSet<>(directedEvents).size(),
				"runtime clock dispatches every directed event exactly once"
		);
		assertEquals(1, finishSignals, "runtime clock finishes exactly once");
		assertEquals(ScenarioSessionState.FINISHED, session.state(), "runtime clock finishes the session at duration");
		assertEquals(
				"Scenario duration elapsed",
				session.completionReason().orElseThrow(),
				"runtime clock records the bounded completion reason"
		);
		return 5;
	}

	private static int verifyPhases() {
		ScenarioPreset survival = ScenarioPresets.require("last-valley");
		assertEquals("dawn", survival.phaseAt(0L).orElseThrow().id(), "first phase");
		assertEquals("forecast", survival.phaseAt(7_200L).orElseThrow().id(), "phase boundary");
		assertEquals("sunrise", survival.phaseAt(survival.defaultDurationTicks() - 1L).orElseThrow().id(), "last phase");
		assertEquals(Optional.empty(), survival.phaseAt(survival.defaultDurationTicks()), "after final phase");
		assertEquals(Optional.empty(), survival.phaseAt(-1L), "negative elapsed tick");
		return 5;
	}

	private static int verifyLifecycle() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("citadel-collapse"), 31L, 32L, participants(4))
		);
		assertEquals(ScenarioSessionState.PREPARING, session.state(), "initial state");
		expectFailure(() -> session.start(10L), "INVALID_SESSION_TRANSITION");
		session.markReady(20L);
		session.beginCountdown(30L);
		session.start(40L);
		session.pause(50L);
		expectFailure(() -> session.resume(49L), "STALE_EVENT_TICK");
		assertEquals(ScenarioSessionState.PAUSED, session.state(), "stale transition is atomic");
		expectFailure(() -> session.finish(49L, "stale finish"), "STALE_EVENT_TICK");
		assertEquals(Optional.empty(), session.completionReason(), "stale finish reason is atomic");
		session.resume(60L);
		session.finish(70L, "Last agent standing");
		assertEquals(ScenarioSessionState.FINISHED, session.state(), "finished state");
		int eventsAfterFinish = session.evidence().size();
		session.finish(80L, "duplicate finish");
		session.stop(90L, "duplicate stop");
		assertEquals(eventsAfterFinish, session.evidence().size(), "finish and stop idempotence");
		assertEquals("Last agent standing", session.completionReason().orElseThrow(), "completion reason retained");
		return 5;
	}

	private static int verifyScoringAndEvidence() {
		ScenarioSession session = runningSession();
		ScenarioEvent encounter = session.record(
				90L,
				ScenarioEventType.DIRECTOR_EVENT,
				Optional.of("agent-0"),
				Optional.of("minecraft:zombie"),
				"hostile-encounter",
				1.0D,
				"A zombie entered the participant's shelter",
				Map.of("light", "4")
		);
		assertEquals("minecraft:zombie", encounter.targetId().orElseThrow(), "non-agent evidence target");
		ScenarioScoreChange first = session.award(
				100L,
				"agent-0",
				"survival",
				12.5D,
				"Reached extraction alive",
				Map.of("health", "18")
		);
		session.award(110L, "agent-0", "food-security", 3.0D, "Secured renewable food", Map.of());
		assertDoubleEquals(15.5D, session.scores().get("agent-0"), "score total");
		assertEquals(2, session.scoreAudit().size(), "score audit count");
		assertEquals(first.evidenceSequence(), session.scoreAudit().getFirst().evidenceSequence(), "score evidence linkage");
		assertEquals("survival", session.scoreAudit().getFirst().ruleId(), "score rule");
		assertEquals("Reached extraction alive", session.scoreAudit().getFirst().reason(), "score reason");
		assertEquals("18", session.evidence().get(first.evidenceSequence()).attributes().get("health"), "evidence attributes");
		expectFailure(
				() -> session.award(120L, "agent-0", "not-a-rule", 1.0D, "Invalid", Map.of()),
				"UNKNOWN_SCORE_RULE"
		);
		expectFailure(
				() -> session.award(120L, "missing-agent", "survival", 1.0D, "Invalid", Map.of()),
				"UNKNOWN_PARTICIPANT"
		);
		expectUnsupported(() -> session.evidence().clear(), "evidence list is immutable");
		expectUnsupported(() -> session.scores().put("agent-0", 999.0D), "scores map is immutable");
		return 11;
	}

	private static int verifyReset() {
		ScenarioSession session = runningSession();
		session.award(100L, "agent-0", "survival", 2.0D, "Still alive", Map.of());
		session.stop(200L, "Producer stop");
		session.reset(300L);
		assertEquals(ScenarioSessionState.PREPARING, session.state(), "reset state");
		assertDoubleEquals(0.0D, session.scores().get("agent-0"), "reset score");
		assertEquals(1, session.evidence().size(), "reset evidence marker");
		int resetEvidenceCount = session.evidence().size();
		session.reset(400L);
		assertEquals(resetEvidenceCount, session.evidence().size(), "reset idempotence");
		return 4;
	}

	private static ScenarioSession runningSession() {
		ScenarioSession session = new ScenarioSession(
				config(ScenarioPresets.require("last-valley"), 51L, 52L, participants(2))
		);
		session.markReady(10L);
		session.beginCountdown(20L);
		session.start(30L);
		return session;
	}

	private static ScenarioSessionConfig config(
			ScenarioPreset preset,
			long worldSeed,
			long eventSeed,
			List<ScenarioParticipant> participants
	) {
		return new ScenarioSessionConfig(
				SESSION_ID,
				preset,
				worldSeed,
				eventSeed,
				preset.defaultDurationTicks(),
				true,
				participants,
				CREATED_AT_EPOCH_MS
		);
	}

	private static List<ScenarioParticipant> participants(int count) {
		ArrayList<ScenarioParticipant> participants = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			participants.add(new ScenarioParticipant(
					"agent-" + index,
					"Model " + index,
					Optional.of(index % 2 == 0 ? "amber" : "blue")
			));
		}
		return List.copyOf(participants);
	}

	private static void expectFailure(Runnable operation, String expectedCode) {
		try {
			operation.run();
			throw new AssertionError("Expected scenario failure " + expectedCode);
		} catch (ScenarioValidationException exception) {
			assertEquals(expectedCode, exception.code(), "failure code");
		}
	}

	private static void expectUnsupported(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) {
			throw new AssertionError(label + ": expected true");
		}
	}

	private static void assertDoubleEquals(double expected, double actual, String label) {
		if (Math.abs(expected - actual) > 0.000_001D) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
