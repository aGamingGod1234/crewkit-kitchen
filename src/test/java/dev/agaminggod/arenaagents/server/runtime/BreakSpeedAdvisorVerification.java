package dev.agaminggod.arenaagents.server.runtime;

import java.util.Objects;

public final class BreakSpeedAdvisorVerification {
	private BreakSpeedAdvisorVerification() {
	}

	public static int verify() {
		assertEquals(60, BreakSpeedAdvisor.breakTicks(1.0F, 2.0F, true), "a log by hand takes 3 s");
		assertEquals(15, BreakSpeedAdvisor.breakTicks(4.0F, 2.0F, true), "a stone axe takes a log in 0.75 s");
		assertEquals(15, BreakSpeedAdvisor.breakTicks(1.0F, 0.5F, true), "dirt by hand takes 0.75 s");
		assertEquals(150, BreakSpeedAdvisor.breakTicks(1.0F, 1.5F, false), "stone by hand without the right tool takes 7.5 s");
		assertEquals(23, BreakSpeedAdvisor.breakTicks(2.0F, 1.5F, true), "a wooden pickaxe takes stone in 1.15 s");
		assertEquals(1, BreakSpeedAdvisor.breakTicks(1.0F, 0.0F, true), "an instant block takes one tick");
		assertEquals(-1, BreakSpeedAdvisor.breakTicks(1.0F, -1.0F, true), "bedrock never breaks");
		assertEquals(1, BreakSpeedAdvisor.breakTicks(30.0F, 1.0F, true), "a fast tool never takes less than one tick");
		BreakSpeedAdvisor.Option hand = new BreakSpeedAdvisor.Option("hand", 0, 60);
		BreakSpeedAdvisor.Option axe = new BreakSpeedAdvisor.Option("stone_axe", 3, 15);
		assertEquals("faster tool in inventory: hand (held) 60 ticks, stone_axe slot 3: 15 ticks",
				BreakSpeedAdvisor.note(hand, axe), "the note names both times");
		assertEquals(null, BreakSpeedAdvisor.note(axe, hand), "a slower alternative is not mentioned");
		assertEquals(null, BreakSpeedAdvisor.note(hand, new BreakSpeedAdvisor.Option("bone", 2, 60)), "a tie is not mentioned");
		assertEquals(null, BreakSpeedAdvisor.note(hand, null), "no alternative adds nothing");
		return 12;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
