package dev.agaminggod.arenaagents.crewkit.director;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Camera marks for the CrewKit film, relative to the set origin (docs/crewkit/SET-ZONES.md).
 * Each mark is built from the subject's bounding box and a view direction: the eye backs off along
 * that direction until every box corner sits inside the frustum with a 10% margin, so shots never
 * crop the action. Coordinates: x east, y up, z south. Room interior x 0..27, z 0..21, beams at y=7.
 */
public final class CrewkitMarks {
	private CrewkitMarks() {}

	/** Vertical FOV the marks are fitted for (Minecraft default 70) and the recording aspect. */
	public static final double FIT_VFOV = 70.0, FIT_ASPECT = 16.0 / 9.0, MARGIN = 0.9;
	/** How far each shot starts behind its fitted eye; the hold drifts in by this much. */
	public static final double DRIFT = 0.3;
	/** Eyes stay in this box: inside the walls and under the ceiling beams. */
	public static final double MIN_X = 1, MAX_X = 26, MIN_Z = 1, MAX_Z = 20, MAX_Y = 6.3, MIN_Y = 1.6;

	public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
		double cx() { return (x0 + x1) / 2; }
		double cy() { return (y0 + y1) / 2; }
		double cz() { return (z0 + z1) / 2; }
	}

	/**
	 * A shot: fitted eye (e*), look-at (t*), and the drift start eye (s*), which is the fitted eye pulled
	 * back by DRIFT so the whole hold stays framed.
	 */
	public record Mark(String id, String label, Box subject, double ex, double ey, double ez, double tx, double ty, double tz,
			double sx, double sy, double sz) {
		/** Absolute pose {x, y, z, yaw, pitch} at the fitted eye (where the hold drift ends). */
		public double[] pose(int ox, int oy, int oz) { return poseAt(ex, ey, ez, ox, oy, oz); }
		/** Absolute pose at the drift start (where the glide lands). */
		public double[] startPose(int ox, int oy, int oz) { return poseAt(sx, sy, sz, ox, oy, oz); }
		private double[] poseAt(double x, double y, double z, int ox, int oy, int oz) {
			double dx = tx - x, dy = ty - y, dz = tz - z;
			double yaw = Math.toDegrees(Math.atan2(-dx, dz));
			double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			return new double[] {ox + x, oy + y, oz + z, yaw, pitch};
		}
		public double[] eye() { return new double[] {sx, sy, sz}; }
	}

	/** Builds a mark: look along (fx, fy, fz) at the box centre from the closest distance that frames the box. */
	static Mark fit(String id, String label, Box b, double fx, double fy, double fz) {
		double[] f = norm(fx, fy, fz);
		// Demo framing: sit closer than the full-fit distance so the action fills the frame (wide shots stay full).
		double dist = fitDistance(b, f, FIT_VFOV, FIT_ASPECT) * (id.startsWith("wide") || id.equals("celebrate") ? 1.0 : 0.72);
		double cx = b.cx(), cy = b.cy(), cz = b.cz();
		double ex = cx - f[0] * dist, ey = cy - f[1] * dist, ez = cz - f[2] * dist;
		double sx = ex - f[0] * DRIFT, sy = ey - f[1] * DRIFT, sz = ez - f[2] * DRIFT;
		return new Mark(id, label, b, clampX(ex), clampY(ey), clampZ(ez), cx, cy, cz, clampX(sx), clampY(sy), clampZ(sz));
	}

	/** Smallest eye distance from the box centre along forward f so all eight corners fit with MARGIN. */
	static double fitDistance(Box b, double[] f, double vfov, double aspect) {
		double[][] basis = basis(f);
		double tv = Math.tan(Math.toRadians(vfov / 2)) * MARGIN, th = Math.tan(Math.toRadians(vfov / 2)) * aspect * MARGIN;
		double d = 0.5;
		for (double[] c : corners(b)) {
			double[] r = {c[0] - b.cx(), c[1] - b.cy(), c[2] - b.cz()};
			double fwd = dot(r, f), right = dot(r, basis[0]), up = dot(r, basis[1]);
			d = Math.max(d, Math.abs(right) / th - fwd);
			d = Math.max(d, Math.abs(up) / tv - fwd);
		}
		return d;
	}

	/** True if every subject corner is inside the frustum (no margin) from eye looking at target. */
	public static boolean fits(Box b, double ex, double ey, double ez, double tx, double ty, double tz, double vfov, double aspect) {
		double[] f = norm(tx - ex, ty - ey, tz - ez);
		double[][] basis = basis(f);
		double tv = Math.tan(Math.toRadians(vfov / 2)), th = tv * aspect;
		for (double[] c : corners(b)) {
			double[] r = {c[0] - ex, c[1] - ey, c[2] - ez};
			double fwd = dot(r, f);
			if (fwd <= 0.05) return false;
			if (Math.abs(dot(r, basis[0])) > fwd * th || Math.abs(dot(r, basis[1])) > fwd * tv) return false;
		}
		return true;
	}

	/** Solid props a glide must not pass through (pass, ticker, chef stack, counter, hood, tables). */
	static final List<Box> OBSTACLES = List.of(
			new Box(2, 0, 7.8, 16.6, 5.7, 9.9),    // the pass: ticket rail, gate bars, QR card, ticker, printer, busy sign
			new Box(7, 0, 5.4, 10, 6.5, 7.9),      // chef and head stack
			new Box(2, 0, 2.5, 15, 2.3, 4.5),      // counter
			new Box(12, 0, 1, 15, 6.2, 4.5),       // hood and flue
			new Box(3, 0, 10.8, 25, 2.7, 15.3));   // tables A-C and seated guests

	/** Mid-room waypoints for glides whose straight path would clip a prop. */
	public static final List<double[]> WAYPOINTS = List.of(
			new double[] {18.5, 4.2, 9.0}, new double[] {14.0, 6.2, 8.8}, new double[] {14.0, 5.6, 17.5}, new double[] {20.0, 5.0, 12.0},
			new double[] {18.5, 6.2, 6.5}, new double[] {4.0, 6.2, 8.8}, new double[] {11.0, 6.2, 5.0}, new double[] {5.0, 6.2, 5.0});

	/** True if the segment between two relative eye points stays clear of every obstacle. */
	public static boolean clear(double[] a, double[] b) {
		for (Box o : OBSTACLES) if (hits(o, a, b)) return false;
		return true;
	}

	/** Waypoints (zero, one or two) that make a clear path from a to b, or null if nothing helps. */
	public static List<double[]> route(double[] a, double[] b) {
		if (clear(a, b)) return List.of();
		for (double[] w : WAYPOINTS) if (clear(a, w) && clear(w, b)) return List.of(w);
		for (double[] w : WAYPOINTS) for (double[] v : WAYPOINTS)
			if (w != v && clear(a, w) && clear(w, v) && clear(v, b)) return List.of(w, v);
		return null;
	}

	private static boolean hits(Box o, double[] a, double[] b) {
		double t0 = 0, t1 = 1;
		double[] lo = {o.x0, o.y0, o.z0}, hi = {o.x1, o.y1, o.z1};
		for (int i = 0; i < 3; i++) {
			double d = b[i] - a[i];
			if (Math.abs(d) < 1e-9) { if (a[i] < lo[i] || a[i] > hi[i]) return false; continue; }
			double u = (lo[i] - a[i]) / d, v = (hi[i] - a[i]) / d;
			t0 = Math.max(t0, Math.min(u, v));
			t1 = Math.min(t1, Math.max(u, v));
			if (t0 > t1) return false;
		}
		return true;
	}

	// Subject boxes come from the features that draw them (boards, pass, ticker, printer, busy sign, party, guests).
	public static final Mark WIDE = fit("wide", "Wide: whole kitchen and both boards",
			new Box(2, 1, 1.05, 26.5, 6.7, 9.9), 0, -0.22, -1);
	public static final Mark BRIEF = fit("brief", "Brief: ticket and seated guests",
			new Box(3.2, 1, 8.6, 16.6, 4.4, 15.3), -0.9, -0.55, -0.7);
	public static final Mark LINE = fit("line", "Line: chef, head stack, bubble, candidate fan",
			new Box(7.4, 1.2, 5.8, 9.6, 6.2, 7.3), -1, -0.08, 0.15);
	public static final Mark BUDGET = fit("budget", "Budget board",
			new Box(2, 1.8, 1.05, 11, 6.7, 1.1), 0, 0, -1);
	public static final Mark QUOTES = fit("quotes", "Quotes: ticker, printer, jar, busy sign",
			new Box(4, 1.5, 8.1, 16, 5.6, 8.9), 0, -0.05, -1);
	public static final Mark GATE = fit("gate", "Gate: bars, sign and ticket",
			new Box(3.5, 1, 8.6, 13.5, 4.6, 9.8), 0, -0.2, -1);
	public static final Mark QR = fit("qr", "QR card, label and hourglass",
			new Box(7.6, 1.8, 8.9, 12.8, 5.7, 9.2), 0, -0.02, -1);
	public static final Mark DOOR = fit("door", "Delivery door: bag drop and chef",
			new Box(24.2, 1, 4.8, 27, 3.3, 7.1), 1, -0.3, -0.35);
	public static final Mark PLATING = fit("plating", "Plating: tables A and B, plates and guests",
			new Box(3.2, 1, 10.8, 16.8, 4.6, 15.3), -0.9, -0.5, -0.8);
	public static final Mark BILL = fit("bill", "Bill board",
			new Box(16.5, 1.6, 1.05, 26.5, 6.7, 1.1), 0, 0, -1);
	public static final Mark CELEBRATE = fit("celebrate", "Celebrate: banner over the tables",
			new Box(3.2, 1, 10.8, 16.8, 6.2, 15.3), -0.9, -0.3, -0.75);
	public static final Mark GUESTS_A = fit("guests_a", "Guests at table A, low",
			new Box(3.4, 1, 10.9, 8.6, 4.5, 15.1), 0.2, 0.06, -1);
	public static final Mark GUESTS_B = fit("guests_b", "Guests at table B, low",
			new Box(11.4, 1, 10.9, 16.6, 4.5, 15.1), -0.2, 0.06, -1);
	public static final Mark GUESTS_WIDE = fit("guests_wide", "Dining room, three-quarter",
			new Box(3.2, 1, 10.8, 16.8, 4.4, 15.3), 0.9, -0.4, -0.8);

	public static final Mark PASS_PAY = fit("pass_pay", "Stablecoin skit: guest and chef at the pass",
			new Box(7.8, 1, 6.6, 11.2, 4.2, 10.6), 0.75, -0.3, -0.9);
	public static final Mark BROKER = fit("broker", "Stablecoin skit: money broker nook",
			new Box(2, 1, 17, 5.6, 4.4, 20), -0.85, -0.3, 0.35);

	/** Manual stepping order, matching the film's beat order. */
	public static final List<Mark> ORDER = List.of(WIDE, BRIEF, LINE, QUOTES, BUDGET, GATE, PASS_PAY, BROKER, QR, DOOR, PLATING, CELEBRATE, BILL,
			GUESTS_A, GUESTS_B, GUESTS_WIDE);

	public static Optional<Mark> byId(String id) {
		if (id == null) return Optional.empty();
		String key = id.strip().toLowerCase(Locale.ROOT);
		return ORDER.stream().filter(mark -> mark.id().equals(key)).findFirst();
	}

	public static int indexOf(String id) {
		for (int i = 0; i < ORDER.size(); i++) if (ORDER.get(i).id().equals(id)) return i;
		return -1;
	}

	/** Glide length in ticks between two marks: 25..40, longer for longer moves. */
	public static int moveTicks(String fromId, String toId) {
		var a = byId(fromId);
		var b = byId(toId);
		if (a.isEmpty() || b.isEmpty()) return 30;
		double[] p = a.get().eye(), q = b.get().eye();
		double d = Math.sqrt(sq(p[0] - q[0]) + sq(p[1] - q[1]) + sq(p[2] - q[2]));
		return (int) Math.round(Math.min(40, 25 + d * 1.1));
	}

	private static double sq(double v) { return v * v; }
	private static double clampX(double v) { return Math.max(MIN_X, Math.min(MAX_X, v)); }
	private static double clampY(double v) { return Math.max(MIN_Y, Math.min(MAX_Y, v)); }
	private static double clampZ(double v) { return Math.max(MIN_Z, Math.min(MAX_Z, v)); }
	private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
	private static double[] norm(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);
		return new double[] {x / l, y / l, z / l};
	}
	private static double[][] basis(double[] f) {
		double[] right = norm(-f[2], 0, f[0]);
		double[] up = {right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0]};
		if (up[1] < 0) up = new double[] {-up[0], -up[1], -up[2]};
		return new double[][] {right, up};
	}
	private static double[][] corners(Box b) {
		double[][] c = new double[8][];
		int i = 0;
		for (double x : new double[] {b.x0, b.x1}) for (double y : new double[] {b.y0, b.y1}) for (double z : new double[] {b.z0, b.z1})
			c[i++] = new double[] {x, y, z};
		return c;
	}

	/** Framing check: prints each mark's eye, look-at, and whether the subject fits from both drift ends. */
	public static void main(String[] args) {
		System.out.println("mark | eye | look-at | fits(fitted) | fits(drift start)");
		for (Mark m : ORDER) {
			boolean a = fits(m.subject(), m.ex, m.ey, m.ez, m.tx, m.ty, m.tz, FIT_VFOV, FIT_ASPECT);
			boolean s = fits(m.subject(), m.sx, m.sy, m.sz, m.tx, m.ty, m.tz, FIT_VFOV, FIT_ASPECT);
			System.out.printf(Locale.ROOT, "%s | (%.2f, %.2f, %.2f) | (%.2f, %.2f, %.2f) | %s | %s%n", m.id(), m.ex, m.ey, m.ez, m.tx, m.ty, m.tz, a, s);
		}
		for (Mark a : ORDER) for (Mark b : ORDER) if (a != b && route(a.eye(), b.eye()) == null)
			System.out.println("no clear route " + a.id() + " -> " + b.id());
	}
}
