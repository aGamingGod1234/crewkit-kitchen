package dev.agaminggod.arenaagents.crewkit.director;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Camera marks for the CrewKit film, relative to the set origin (docs/crewkit/kitchen-layout.html).
 * Each mark is an eye position and a look-at point, so yaw and pitch follow the set if it moves.
 * Coordinates: x east, y up, z south. The room is 28 x 22 with the camera wall at z = 22.
 */
public final class CrewkitMarks {
	private CrewkitMarks() {}

	public record Mark(String id, String label, double ex, double ey, double ez, double tx, double ty, double tz) {
		/** Absolute pose {x, y, z, yaw, pitch} for a set origin. */
		public double[] pose(int ox, int oy, int oz) {
			double dx = tx - ex, dy = ty - ey, dz = tz - ez;
			double yaw = Math.toDegrees(Math.atan2(-dx, dz));
			double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			return new double[] {ox + ex, oy + ey, oz + ez, yaw, pitch};
		}
	}

	public static final Mark WIDE = new Mark("wide", "Wide: whole kitchen", 14.0, 5.6, 19.5, 14.0, 1.0, 9.5);
	public static final Mark LINE = new Mark("line", "Counter line: chef and head stack", 11.0, 3.2, 10.8, 8.0, 2.2, 5.5);
	public static final Mark BUDGET = new Mark("budget", "Budget board", 6.5, 4.2, 5.2, 6.5, 4.0, 0.5);
	public static final Mark GATE = new Mark("gate", "The pass: ticket and gate bars", 9.5, 2.9, 13.5, 9.5, 2.2, 8.5);
	public static final Mark QR = new Mark("qr", "QR on the pass", 9.5, 2.5, 11.2, 9.5, 2.3, 8.5);
	public static final Mark DOOR = new Mark("door", "Delivery door", 21.0, 3.0, 10.5, 25.5, 1.2, 6.0);
	public static final Mark PLATING = new Mark("plating", "Tables: plating per guest", 14.0, 4.2, 18.5, 14.0, 1.0, 13.0);
	public static final Mark BILL = new Mark("bill", "Bill board", 21.0, 4.2, 5.2, 21.0, 4.0, 0.5);

	/** Manual stepping order, matching the film's beat order. */
	public static final List<Mark> ORDER = List.of(WIDE, LINE, BUDGET, GATE, QR, DOOR, PLATING, BILL);

	public static Optional<Mark> byId(String id) {
		if (id == null) return Optional.empty();
		String key = id.strip().toLowerCase(Locale.ROOT);
		return ORDER.stream().filter(mark -> mark.id().equals(key)).findFirst();
	}

	public static int indexOf(String id) {
		for (int i = 0; i < ORDER.size(); i++) if (ORDER.get(i).id().equals(id)) return i;
		return -1;
	}
}
