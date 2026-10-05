package dev.agaminggod.arenaagents.world;

public final class LandmarkMutationLocalityVerification {
	public static void main(String[] args) {
		System.out.println("LandmarkMutationLocalityVerification assertions=" + verify());
	}

	public static int verify() {
		var revisions = new WorldMutationRevisions();
		// The old 512-block regions included (1000,1000) in this 257-block sight fan.
		long original = revisions.revision(256, 256, 257);
		revisions.recordMutation(1_000, 1_000);
		require(revisions.revision(256, 256, 257) == original, "distant mutation in old coarse region preserves landmark candidates");
		revisions.recordMutation(513, 513);
		require(revisions.revision(256, 256, 257) > original, "positive sight boundary still invalidates");
		long negative = revisions.revision(-256, -256, 257);
		revisions.recordMutation(-513, -513);
		require(revisions.revision(-256, -256, 257) > negative, "negative sight boundary still invalidates");
		long local = revisions.revision(0, 0, 9);
		revisions.recordMutation(128, 128);
		require(revisions.revision(0, 0, 9) == local, "chunk-scale navigation revisions stay local");
		// Sixteen disjoint maximum-size production sight footprints must fit without cache churn.
		var roster = new WorldMutationRevisions();
		long[] stamps = new long[16];
		for (int i = 0; i < stamps.length; i++) stamps[i] = roster.revision(i * 2_048, 0, 257);
		for (int i = 0; i < stamps.length; i++) require(roster.revision(i * 2_048, 0, 257) == stamps[i], "sixteen-agent landmark footprint stays stable");
		require(roster.retainedRegions() <= 256, "regional index remains bounded");
		return 21 + WorldMutationRevisionsVerification.verify();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
