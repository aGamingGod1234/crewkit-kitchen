package dev.agaminggod.arenaagents.client.control;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.LiveTaskViewData;
import dev.agaminggod.arenaagents.control.LiveTaskViewPayload;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent, read-only desktop views of the selected bot, drawn in the Minecraft style (game font, dirt
 * background, bevelled panels) and laid out to fit the window without scrollbars. Swing owns its widgets.
 */
public final class LiveAgentWindows {
	private static final Logger LOGGER = LoggerFactory.getLogger(LiveAgentWindows.class);
	/** Screen pixels per Minecraft GUI pixel, like the game's GUI scale 2. */
	private static final int SCALE = 2;
	private static final Color CARD = new Color(0, 0, 0, 150), CARD_DONE = new Color(20, 60, 25, 190),
			CARD_ACTIVE = new Color(70, 60, 10, 190), SELECTED = new Color(0xFFFFFF);
	private static volatile boolean planEnabled, terminalEnabled;
	private static volatile String selected = "", displayName = "Agent", provider = "";
	private static boolean initialized, wasConnected;
	private static int pollTicks;
	private static volatile long receivedAt;
	private static JFrame planFrame, terminalFrame;
	private static PlanView planView;
	private static TerminalView terminalView;
	private static Graph graph;
	private static String detailText = "";
	private static JsonObject latest = new JsonObject(); // EDT only
	private static String lastTerminal = "";
	private static LiveTaskViewPayload.Snapshot lastSnapshot;

	private record ParsedView(String json, JsonObject value) {
	}

	private static volatile ParsedView parsedView;
	private static volatile String waitingStatus = "Waiting for live task data";
	private static Path settingsPath;
	private static Timer refreshTimer;

	private LiveAgentWindows() {
	}

	private static synchronized void initialize() {
		if (initialized) return;
		initialized = true;
		try {
			Path file = settingsFile();
			Properties p = new Properties();
			if (Files.isRegularFile(file) && Files.size(file) < 4096) try (var in = Files.newInputStream(file)) { p.load(in); }
			planEnabled = Boolean.parseBoolean(p.getProperty("plan", "false"));
			terminalEnabled = Boolean.parseBoolean(p.getProperty("terminal", "false"));
			String id = p.getProperty("agent", "");
			if (!id.isBlank()) selected = UUID.fromString(id).toString();
		} catch (Exception e) {
			LOGGER.warn("Could not read live window settings", e);
		}
	}

	private static Path settingsFile() {
		if (settingsPath == null) settingsPath = FabricLoader.getInstance().getConfigDir().resolve("arenaagents-live-windows.properties");
		return settingsPath;
	}

	private static synchronized void save() {
		try {
			Properties p = new Properties();
			p.setProperty("plan", String.valueOf(planEnabled));
			p.setProperty("terminal", String.valueOf(terminalEnabled));
			p.setProperty("agent", selected);
			Path file = settingsFile();
			Files.createDirectories(file.getParent());
			try (var out = Files.newOutputStream(file)) { p.store(out, "Arena Agents local display settings"); }
		} catch (Exception e) {
			LOGGER.warn("Could not save live window settings", e);
		}
	}

	public static boolean planEnabled() { initialize(); return planEnabled; }

	public static boolean terminalEnabled() { initialize(); return terminalEnabled; }

	public static void select(AgentControlAgent agent) {
		initialize();
		String next = agent == null ? "" : agent.agentId();
		displayName = agent == null ? "Agent" : agent.displayName();
		provider = agent == null ? "" : agent.provider();
		if (!selected.equals(next)) {
			selected = next;
			receivedAt = 0;
			pollTicks = 0;
			save();
			clearView(agent == null ? "No agent selected" : "Waiting for live task data");
		}
	}

	private static void clearView(String status) {
		PlanItemIcons.clear();
		waitingStatus = status;
		receivedAt = 0;
		SwingUtilities.invokeLater(() -> { latest = new JsonObject(); lastSnapshot = null; parsedView = null; lastTerminal = ""; refresh(); });
	}

	public static boolean togglePlan(AgentControlAgent agent) { select(agent); planEnabled = !planEnabled; return applySettings(); }

	public static boolean toggleTerminal(AgentControlAgent agent) { select(agent); terminalEnabled = !terminalEnabled; return applySettings(); }

	private static boolean applySettings() {
		if (GraphicsEnvironment.isHeadless()) { planEnabled = false; terminalEnabled = false; save(); return false; }
		save();
		SwingUtilities.invokeLater(LiveAgentWindows::syncFrames);
		return true;
	}

	public static void tick(Minecraft client) {
		initialize();
		boolean connected = client.player != null && client.level != null && client.getConnection() != null;
		if (!connected) { if (wasConnected) disconnect(); return; }
		if (!wasConnected) { wasConnected = true; pollTicks = 0; }
		if (!planEnabled && !terminalEnabled || selected.isBlank()) return;
		var roster = AgentControlClient.snapshot().orElse(null);
		if (roster == null) return;
		if (!roster.canControl()) {
			if (!waitingStatus.equals("Operator permission is required")) clearView("Operator permission is required");
			SwingUtilities.invokeLater(LiveAgentWindows::disposeFrames);
			return;
		}
		var agent = roster.agents().stream().filter(a -> a.agentId().equals(selected)).findFirst().orElse(null);
		if (agent == null) {
			if (!waitingStatus.equals("Selected agent is no longer available")) clearView("Selected agent is no longer available");
			return;
		}
		displayName = agent.displayName();
		provider = agent.provider();
		if (--pollTicks > 0) return;
		pollTicks = 10;
		if (!GraphicsEnvironment.isHeadless()) SwingUtilities.invokeLater(LiveAgentWindows::syncFrames);
		if (ClientPlayNetworking.canSend(LiveTaskViewPayload.Request.TYPE)) ClientPlayNetworking.send(new LiveTaskViewPayload.Request(UUID.fromString(selected)));
	}

	public static void accept(LiveTaskViewPayload.Snapshot packet) {
		if (!packet.agentId().toString().equals(selected)) return;
		// Cached replay still carries immediate online/offline status. Reuse only an
		// identical, previously validated full body; events/usage/freshness are included.
		// Keep validation of changed bodies off the Swing event thread.
		ParsedView cached = parsedView;
		final JsonObject parsed;
		if (cached != null && packet.json().equals(cached.json())) parsed = cached.value();
		else try {
			parsed = LiveTaskViewData.parse(packet.json());
			parsedView = new ParsedView(packet.json(), parsed);
		} catch (RuntimeException e) {
			LOGGER.warn("Rejected invalid live task display data", e);
			return;
		}
		SwingUtilities.invokeLater(() -> {
			if (!packet.agentId().toString().equals(selected)) return;
			if (parsed.size() > 0 && latest.size() > 0) {
				long revision = parsed.get("goalRevision").getAsLong(), current = latest.get("goalRevision").getAsLong();
				if (revision < current || revision == current && parsed.get("generatedAt").getAsLong() < latest.get("generatedAt").getAsLong()) return;
			}
			// The server replays its cache while awaiting the coordinator; replay is not fresh coordinator data.
			if (lastSnapshot == null || !parsed.equals(latest)) receivedAt = System.currentTimeMillis();
			waitingStatus = "Waiting for live task data";
			latest = parsed;
			lastSnapshot = packet;
			refresh();
		});
	}

	public static void disconnect() {
		PlanItemIcons.clear();
		wasConnected = false;
		receivedAt = 0;
		pollTicks = 0;
		waitingStatus = "Disconnected from Minecraft";
		SwingUtilities.invokeLater(() -> { latest = new JsonObject(); lastSnapshot = null; parsedView = null; lastTerminal = ""; disposeFrames(); });
	}

	private static void disposeFrames() {
		if (planFrame != null) planFrame.dispose();
		if (terminalFrame != null) terminalFrame.dispose();
		planFrame = null; terminalFrame = null; graph = null; planView = null; terminalView = null;
		if (refreshTimer != null) refreshTimer.stop();
	}

	private static void syncFrames() {
		if (planEnabled && planFrame == null) createPlan();
		if (!planEnabled && planFrame != null) { planFrame.dispose(); planFrame = null; graph = null; planView = null; PlanItemIcons.clear(); }
		if (terminalEnabled && terminalFrame == null) createTerminal();
		if (!terminalEnabled && terminalFrame != null) { terminalFrame.dispose(); terminalFrame = null; terminalView = null; }
		if (refreshTimer == null) refreshTimer = new Timer(1000, e -> refresh());
		if (planFrame != null || terminalFrame != null) { if (!refreshTimer.isRunning()) refreshTimer.start(); }
		else refreshTimer.stop();
		refresh();
	}

	private static JFrame frame(String name, int width, int height, boolean plan) {
		JFrame f = new JFrame(name);
		f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		f.setSize(width, height);
		f.setMinimumSize(new Dimension(640, 420));
		f.setLocationByPlatform(true);
		f.setAutoRequestFocus(false);
		List<java.awt.Image> icons = McUi.windowIcons();
		if (!icons.isEmpty()) f.setIconImages(icons);
		f.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				if ((plan ? planFrame : terminalFrame) != f) return;
				if (plan) planEnabled = false; else terminalEnabled = false;
				save();
				syncFrames();
			}
		});
		return f;
	}

	private static void createPlan() {
		planFrame = frame("Agent plan | " + displayName, 1180, 720, true);
		graph = new Graph();
		planView = new PlanView(graph);
		planFrame.setContentPane(planView);
		planFrame.setVisible(true);
	}

	private static void createTerminal() {
		terminalFrame = frame("Live terminal | " + displayName, 960, 620, false);
		terminalView = new TerminalView();
		terminalFrame.setContentPane(terminalView);
		terminalFrame.setVisible(true);
		lastTerminal = "";
	}

	private static String state() {
		String state = lastSnapshot == null ? waitingStatus : lastSnapshot.status();
		if (lastSnapshot != null && lastSnapshot.online() && latest.size() > 0 && receivedAt > 0 && System.currentTimeMillis() - receivedAt > 4000) state = "Live data is stale; waiting for coordinator";
		return state;
	}

	private static void refresh() {
		String state = state();
		if (planFrame != null) {
			planFrame.setTitle("Agent plan | " + displayName);
			graph.setData(latest);
			planView.repaint();
		}
		if (terminalFrame != null) {
			terminalFrame.setTitle("Live terminal | " + displayName);
			StringBuilder text = new StringBuilder(state).append('\n');
			if (latest.has("events")) for (var value : latest.getAsJsonArray("events")) {
				var e = value.getAsJsonObject();
				text.append('[').append(string(e, "stage", "event").replace("live_", "")).append("] ").append(string(e, "message", "")).append('\n');
			}
			if (!text.toString().equals(lastTerminal)) {
				lastTerminal = text.toString();
				terminalView.contentChanged();
			}
			terminalView.repaint();
		}
	}

	/** Current terminal text (status line plus events), for tests and accessibility. */
	static String terminalText() {
		return lastTerminal;
	}

	static String detailText() {
		return detailText;
	}

	private static String goalTitle() {
		String prefix = latest.has("verified") && latest.get("verified").getAsBoolean() ? "Verified goal: "
				: latest.has("active") && !latest.get("active").getAsBoolean() ? "Last task: " : "Goal: ";
		return prefix + string(latest, "goal", "No detailed plan yet");
	}

	private static String usageText(JsonObject root) {
		List<String> lines = new ArrayList<>();
		if (root.has("usage") && !root.get("usage").isJsonNull()) {
			var u = root.getAsJsonObject("usage");
			String uncached = tokenNumber(u, "uncachedInputTokens");
			if (uncached.equals("?") && hasToken(u, "inputTokens") && hasToken(u, "cachedInputTokens") && u.get("cachedInputTokens").getAsLong() <= u.get("inputTokens").getAsLong())
				uncached = tokenNumber(u.get("inputTokens").getAsLong() - u.get("cachedInputTokens").getAsLong());
			lines.add("Thread totals · Input " + tokenNumber(u, "inputTokens") + " (includes cache) · Uncached " + uncached + " · Cached " + cachedTokens(u, "inputTokens", "cachedInputTokens") + " · Output " + tokenNumber(u, "outputTokens"));
			if (hasToken(u, "lastInputTokens"))
				lines.add("Latest model input " + tokenNumber(u, "lastInputTokens") + " (includes cache) · Uncached " + tokenNumber(u, "lastUncachedInputTokens") + " · Cached " + cachedTokens(u, "lastInputTokens", "lastCachedInputTokens") + " · Output " + tokenNumber(u, "lastOutputTokens"));
			else if (hasToken(u, "intervalInputTokens"))
				lines.add("Between usage updates · Input " + tokenNumber(u, "intervalInputTokens") + " (includes cache) · Uncached " + tokenNumber(u, "intervalUncachedInputTokens") + " · Cached " + tokenNumber(u, "intervalCachedInputTokens") + " · Output " + tokenNumber(u, "intervalOutputTokens"));
			if (hasToken(u, "observedElapsedMs") && u.get("observedElapsedMs").getAsLong() > 0 && (hasToken(u, "inputTokensPerMinute") || hasToken(u, "outputTokensPerMinute")))
				lines.add("Tokens/min (last " + String.format(Locale.ROOT, "%.1f", u.get("observedElapsedMs").getAsLong() / 1000d) + " s sample) · Input " + tokenNumber(u, "inputTokensPerMinute") + " · Uncached " + tokenNumber(u, "uncachedInputTokensPerMinute") + " · Cached " + tokenNumber(u, "cachedInputTokensPerMinute") + " · Output " + tokenNumber(u, "outputTokensPerMinute"));
			else lines.add("Rate pending two timed usage updates");
		} else lines.add("Usage unavailable");
		String allowanceText = "";
		if (root.has("allowance") && !root.get("allowance").isJsonNull()) {
			var a = root.getAsJsonObject("allowance");
			for (String k : List.of("primary", "secondary")) if (a.has(k)) {
				var w = a.getAsJsonObject(k);
				allowanceText += (allowanceText.isEmpty() ? "" : " · ") + "Shared " + (hasToken(w, "windowDurationMins") ? tokenNumber(w, "windowDurationMins") + " min" : k) + ": " + String.format(Locale.ROOT, "%.1f", Math.max(0, 100 - w.get("usedPercent").getAsDouble())) + "% left";
			}
		}
		lines.add((allowanceText.isEmpty() ? "" : allowanceText + " · ") + "Recent events; long payloads may be truncated");
		return "<html>" + String.join("<br>", lines) + "</html>";
	}

	private static List<String> usageLines(JsonObject root) {
		String html = usageText(root);
		return List.of(html.substring("<html>".length(), html.length() - "</html>".length()).split("<br>"));
	}

	private static boolean hasToken(JsonObject object, String key) { var value = object.get(key); return value != null && !value.isJsonNull(); }

	private static String tokenNumber(JsonObject object, String key) { return hasToken(object, key) ? tokenNumber(object.get(key).getAsLong()) : "?"; }

	private static String tokenNumber(long value) { return String.format(Locale.ROOT, "%,d", value); }

	private static String cachedTokens(JsonObject usage, String inputKey, String cachedKey) {
		String result = tokenNumber(usage, cachedKey);
		if (hasToken(usage, inputKey) && hasToken(usage, cachedKey)) {
			long input = usage.get(inputKey).getAsLong(), cached = usage.get(cachedKey).getAsLong();
			if (input > 0 && cached <= input) result += " (" + String.format(Locale.ROOT, "%.1f", cached / (double) input * 100) + "%)";
		}
		return result;
	}

	private static String string(JsonObject o, String key, String fallback) { var e = o.get(key); return e == null || e.isJsonNull() ? fallback : e.getAsString(); }

	static void iconsChanged() { SwingUtilities.invokeLater(() -> { if (graph != null) graph.repaint(); }); }

	private static boolean complete(JsonObject s) { return string(s, "status", "").equals("complete"); }

	private static Color nodeColor(JsonObject s) {
		String status = string(s, "status", "");
		return status.equals("complete") ? McUi.GREEN : status.equals("lost") ? McUi.RED : status.equals("active") ? McUi.YELLOW : McUi.GRAY;
	}

	private static Color stateColor(String state) {
		String lower = state.toLowerCase(Locale.ROOT);
		if (lower.startsWith("connected")) return McUi.GREEN;
		if (lower.contains("offline") || lower.contains("stale") || lower.contains("permission") || lower.contains("no longer")) return McUi.RED;
		return McUi.GRAY;
	}

	/** Provider name colours match the chat prefixes the mod already uses. */
	private static Color providerColor() {
		return switch (provider.toLowerCase(Locale.ROOT)) {
			case "codex" -> McUi.AQUA;
			case "gemini", "antigravity" -> McUi.LIGHT_PURPLE;
			case "claude" -> McUi.GOLD;
			default -> McUi.WHITE;
		};
	}

	private static Color stageColor(String stage) {
		return switch (stage) {
			case "lifecycle" -> McUi.AQUA;
			case "planner", "decision" -> McUi.GOLD;
			case "provider" -> McUi.LIGHT_PURPLE;
			case "agent_message", "say", "chat" -> McUi.GREEN;
			case "retry" -> McUi.YELLOW;
			case "error", "failure" -> McUi.RED;
			case "tool", "action" -> McUi.BLUE.brighter();
			default -> McUi.GRAY;
		};
	}

	private static Graphics2D prepare(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		return g;
	}

	/** Header, step map, detail column and legend in one painted surface that always fits the window. */
	private static final class PlanView extends JPanel {
		private static final int HEADER = 30, FOOTER = 16, PAD = 6, DETAIL = 150; // GUI pixels
		private final Graph graph;

		PlanView(Graph graph) {
			super(null);
			this.graph = graph;
			add(graph);
		}

		@Override
		public void doLayout() {
			int w = getWidth() / SCALE, h = getHeight() / SCALE;
			int detail = Math.min(DETAIL, Math.max(110, w / 3));
			graph.setBounds(PAD * SCALE, (HEADER + PAD) * SCALE, (w - detail - PAD * 3) * SCALE, (h - HEADER - FOOTER - PAD * 2) * SCALE);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = prepare(graphics);
			int w = getWidth(), h = getHeight(), s = SCALE;
			McUi.dirtBackground(g, w, h, .25f);
			// Header bar like a vanilla screen title strip.
			g.setColor(new Color(0, 0, 0, 120));
			g.fillRect(0, 0, w, HEADER * s);
			g.setColor(new Color(255, 255, 255, 40));
			g.fillRect(0, HEADER * s, w, s);
			int titleWidth = w / s - PAD * 2;
			McUi.text(g, McUi.ellipsize(goalTitle(), titleWidth), PAD * s, 6 * s, McUi.WHITE, s);
			String state = state();
			String sub = state + " · Revision " + string(latest, "revision", "0") + " · " + displayName;
			McUi.text(g, McUi.ellipsize(sub, titleWidth), PAD * s, 18 * s, stateColor(state), s);
			// Detail column.
			int detailW = Math.min(DETAIL, Math.max(110, w / s / 3));
			int dx = w / s - detailW - PAD, dy = HEADER + PAD, dh = h / s - HEADER - FOOTER - PAD * 2;
			McUi.panel(g, dx * s, dy * s, detailW * s, dh * s, new Color(0, 0, 0, 150), null);
			int line = dy + 6, maxLines = (dh - 12) / 10;
			List<String> lines = McUi.wrap(detailText, detailW - 12);
			for (int i = 0; i < lines.size() && i < maxLines; i++) {
				String text = i == maxLines - 1 && lines.size() > maxLines ? McUi.ellipsize(lines.get(i) + "...", detailW - 12) : lines.get(i);
				McUi.text(g, text, (dx + 6) * s, line * s, i == 0 ? McUi.WHITE : McUi.GRAY, s);
				line += 10;
			}
			// Legend.
			int fy = h / s - FOOTER + 4, fx = PAD;
			for (var entry : List.of(Map.entry("Complete / known", McUi.GREEN), Map.entry("Active", McUi.YELLOW), Map.entry("Lost", McUi.RED), Map.entry("Pending", McUi.GRAY))) {
				g.setColor(entry.getValue());
				g.fillRect(fx * s, (fy + 1) * s, 6 * s, 6 * s);
				McUi.text(g, entry.getKey(), (fx + 9) * s, fy * s, McUi.GRAY, s);
				fx += 14 + McUi.width(entry.getKey());
			}
			String note = "Plan is advisory; the agent owns decisions.";
			int noteX = w / s - PAD - McUi.width(note);
			if (noteX > fx) McUi.text(g, note, noteX * s, fy * s, McUi.DARK_GRAY.brighter(), s);
			g.dispose();
		}
	}

	/** Step map: laid out by dependency depth and scaled to fit its bounds, so it never needs scrolling. */
	private static final class Graph extends JPanel {
		private static final int CARD_W = 120, CARD_H = 46, GAP_X = 24, GAP_Y = 14, BAND_GAP = 26; // GUI pixels
		private static final double MAX_FIT = 2.5;
		private final Map<String, JsonObject> steps = new LinkedHashMap<>();
		private final Map<String, Point> positions = new HashMap<>();
		private final Map<String, Point> grid = new HashMap<>(); // dependency column, row within column
		private int columns = 1, rowsPerBand = 1, bands = 1;
		private String selectedStep = "", signature = null, taskIdentity = "";
		private int logicalWidth = CARD_W, logicalHeight = CARD_H;
		private double fit = 1;
		private int offsetX, offsetY;

		Graph() {
			setOpaque(false);
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					String hit = stepAt(e.getX(), e.getY());
					if (hit != null) { selectedStep = hit; showDetail(); repaint(); getParent().repaint(); }
				}
			});
		}

		void setData(JsonObject root) {
			String identity = string(root, "goalRevision", "") + ":" + string(root, "goal", "");
			if (!identity.equals(taskIdentity)) { taskIdentity = identity; selectedStep = ""; }
			String next = root.has("plan") ? root.get("plan").toString() : "";
			if (next.equals(signature)) { showDetail(); return; }
			signature = next;
			steps.clear();
			positions.clear();
			if (root.has("plan") && !root.get("plan").isJsonNull())
				for (var v : root.getAsJsonObject("plan").getAsJsonArray("steps")) { var s = v.getAsJsonObject(); steps.put(s.get("id").getAsString(), s); }
			PlanItemIcons.retain(steps.values().stream().map(PlanItemIcons::itemId).collect(java.util.stream.Collectors.toSet()));
			Map<String, Integer> levels = new HashMap<>();
			for (int pass = 0; pass < steps.size(); pass++) for (var entry : steps.entrySet()) {
				if (levels.containsKey(entry.getKey())) continue;
				int level = 0;
				boolean ready = true;
				for (var dependency : entry.getValue().getAsJsonArray("dependsOn")) {
					Integer parent = levels.get(dependency.getAsString());
					if (parent == null) { ready = false; break; }
					level = Math.max(level, parent + 1);
				}
				if (ready) levels.put(entry.getKey(), level);
			}
			Map<Integer, Integer> rows = new HashMap<>();
			grid.clear();
			columns = 1;
			rowsPerBand = 1;
			for (var entry : steps.entrySet()) {
				int column = levels.getOrDefault(entry.getKey(), 0), row = rows.getOrDefault(column, 0);
				rows.put(column, row + 1);
				grid.put(entry.getKey(), new Point(column, row));
				columns = Math.max(columns, column + 1);
				rowsPerBand = Math.max(rowsPerBand, row + 1);
			}
			layoutBands(1);
			showDetail();
			repaint();
		}

		/** Splits dependency columns into stacked bands, so a long plan uses the window's height. */
		private void layoutBands(int count) {
			bands = Math.max(1, Math.min(count, columns));
			int perBand = (columns + bands - 1) / bands, margin = bands > 1 ? GAP_X / 2 + 2 : 0; // room for wrap-around connectors
			int bandHeight = rowsPerBand * CARD_H + (rowsPerBand - 1) * GAP_Y;
			positions.clear();
			for (var entry : grid.entrySet()) {
				int column = entry.getValue().x, band = column / perBand;
				positions.put(entry.getKey(), new Point(margin + (column % perBand) * (CARD_W + GAP_X), band * (bandHeight + BAND_GAP) + entry.getValue().y * (CARD_H + GAP_Y)));
			}
			logicalWidth = margin + perBand * CARD_W + (perBand - 1) * GAP_X;
			logicalHeight = bands * bandHeight + (bands - 1) * BAND_GAP;
		}

		private void computeFit() {
			double available = Math.max(1, getWidth()), availableH = Math.max(1, getHeight());
			// Pick the band count that draws cards largest; cap the scale so short plans don't balloon.
			int best = 1;
			double bestFit = 0;
			for (int count = 1; count <= Math.min(4, columns); count++) {
				layoutBands(count);
				double candidate = Math.min(MAX_FIT, Math.min(available / logicalWidth, availableH / logicalHeight));
				if (candidate > bestFit + 0.01) { bestFit = candidate; best = count; }
			}
			layoutBands(best);
			fit = bestFit;
			offsetX = (int) ((available - logicalWidth * fit) / 2);
			offsetY = (int) Math.max(0, (availableH - logicalHeight * fit) / 2);
		}

		private String stepAt(int x, int y) {
			computeFit();
			double lx = (x - offsetX) / fit, ly = (y - offsetY) / fit;
			for (var entry : positions.entrySet()) {
				Point p = entry.getValue();
				if (lx >= p.x && lx < p.x + CARD_W && ly >= p.y && ly < p.y + CARD_H) return entry.getKey();
			}
			return null;
		}

		/** Screen centre of a step, for tests. */
		Point centerOf(String id) {
			computeFit();
			Point p = positions.get(id);
			return p == null ? null : new Point((int) (offsetX + (p.x + CARD_W / 2.0) * fit), (int) (offsetY + (p.y + CARD_H / 2.0) * fit));
		}

		void showDetail() {
			var s = steps.get(selectedStep);
			if (s == null) {
				detailText = steps.isEmpty()
						? "No detailed plan yet.\n\nThe agent can publish one with taskPlan. The final goal is still tracked on its own."
						: "Select a step.\n\nInventory: current possessions\nWorld: remembered structure\nMilestone: historical progress\nManual: agent-reported plan step";
				return;
			}
			String kind = string(s, "kind", "");
			String freshness = "";
			if (kind.equals("world") && latest.has("lastObserved")) {
				var seen = latest.getAsJsonObject("lastObserved").get(selectedStep);
				if (seen != null && !seen.isJsonNull()) freshness = "\nLast observed: " + java.time.Instant.ofEpochMilli(seen.getAsLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0);
			}
			detailText = string(s, "label", "") + "\n\nState: " + string(s, "status", "") + "\nEvidence: " + kind + freshness + "\n\n" + string(s, "detail", "") + "\n\n"
					+ (kind.equals("inventory") ? "Rechecked against current inventory. Death can invalidate this requirement."
					: kind.equals("world") ? "Retained after death. Known from an observation; recheck when relying on it."
					: "Agent-reported advisory progress. Final goal verification is separate.");
		}

		@Override
		protected void paintComponent(Graphics original) {
			Graphics2D g = prepare(original);
			if (steps.isEmpty()) {
				String waiting = "Waiting for the agent's plan";
				McUi.text(g, waiting, (getWidth() - McUi.width(waiting) * SCALE) / 2, getHeight() / 2 - 8, McUi.GRAY, SCALE);
				g.dispose();
				return;
			}
			computeFit();
			g.translate(offsetX, offsetY);
			g.scale(fit, fit);
			// Connectors: pixel-style elbows, green once both ends are complete.
			for (var entry : steps.entrySet()) for (var dependency : entry.getValue().getAsJsonArray("dependsOn")) {
				Point from = positions.get(dependency.getAsString()), to = positions.get(entry.getKey());
				if (from == null || to == null) continue;
				g.setColor(complete(entry.getValue()) && complete(steps.get(dependency.getAsString())) ? new Color(0x2f8f3f) : new Color(0x6b6b6b));
				int x1 = from.x + CARD_W, y1 = from.y + CARD_H / 2, x2 = to.x, y2 = to.y + CARD_H / 2, mid = x1 + GAP_X / 2;
				if (x2 > x1) {
					g.fillRect(x1, y1 - 1, mid - x1, 2);
					g.fillRect(mid - 1, Math.min(y1, y2) - 1, 2, Math.abs(y2 - y1) + 2);
					g.fillRect(mid, y2 - 1, x2 - mid - 3, 2);
				} else {
					// Wraps to a lower band: right, down into the gap above the target band, left, then down in.
					int channel = to.y - BAND_GAP / 2, entryX = x2 - GAP_X / 2;
					g.fillRect(x1, y1 - 1, mid - x1, 2);
					g.fillRect(mid - 1, y1 - 1, 2, channel - y1 + 2);
					g.fillRect(entryX - 1, channel - 1, mid - entryX + 2, 2);
					g.fillRect(entryX - 1, channel - 1, 2, y2 - channel + 2);
					g.fillRect(entryX, y2 - 1, x2 - entryX - 3, 2);
				}
				g.fillPolygon(new int[]{x2, x2 - 4, x2 - 4}, new int[]{y2, y2 - 3, y2 + 3}, 3);
			}
			for (var entry : steps.entrySet()) {
				var s = entry.getValue();
				Point p = positions.get(entry.getKey());
				Color color = nodeColor(s);
				Color fill = complete(s) ? CARD_DONE : color.equals(McUi.YELLOW) ? CARD_ACTIVE : CARD;
				McUi.panel(g, p.x, p.y, CARD_W, CARD_H, fill, entry.getKey().equals(selectedStep) ? SELECTED : color.darker());
				var icon = PlanItemIcons.image(s);
				int textX = p.x + 6;
				if (icon != null) { g.drawImage(icon, p.x + 5, p.y + 5, 16, 16, null); textX = p.x + 24; }
				List<String> title = McUi.wrap(string(s, "label", ""), p.x + CARD_W - 5 - textX);
				for (int i = 0; i < Math.min(2, title.size()); i++) {
					String text = i == 1 && title.size() > 2 ? McUi.ellipsize(title.get(1) + "...", p.x + CARD_W - 5 - textX) : title.get(i);
					McUi.text(g, text, textX, p.y + 5 + i * 10, McUi.WHITE, 1);
				}
				String kind = string(s, "kind", ""), status = string(s, "status", "");
				String label = status.equals("complete") ? (kind.equals("world") ? "Known" : kind.equals("inventory") ? "In inventory" : "Complete")
						: status.equals("lost") ? "Lost: recovery needed" : status.equals("active") ? "In progress" : "Pending";
				McUi.text(g, McUi.ellipsize(label, CARD_W - 12), p.x + 6, p.y + CARD_H - 13, color, 1);
			}
			g.dispose();
		}
	}

	/** Chat-style event log: newest at the bottom, wraps to the window, wheel scrolls back without a scrollbar. */
	private static final class TerminalView extends JPanel {
		private static final int HEADER = 30, PAD = 6, LINE = 10; // GUI pixels
		private int scrollBack; // lines scrolled up from the newest

		TerminalView() {
			addMouseWheelListener((MouseWheelEvent e) -> { scrollBack = Math.max(0, scrollBack - e.getWheelRotation() * 3); repaint(); });
		}

		void contentChanged() {
			// Following the newest line (scrollBack 0) keeps following; a reader scrolled back stays put.
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = prepare(graphics);
			int s = SCALE, w = getWidth() / s, h = getHeight() / s;
			McUi.dirtBackground(g, getWidth(), getHeight(), .2f);
			// Header: agent name in its provider colour and the connection state.
			g.setColor(new Color(0, 0, 0, 120));
			g.fillRect(0, 0, getWidth(), HEADER * s);
			g.setColor(new Color(255, 255, 255, 40));
			g.fillRect(0, HEADER * s, getWidth(), s);
			McUi.text(g, McUi.ellipsize(displayName, w - PAD * 2), PAD * s, 6 * s, providerColor(), s);
			String state = state();
			McUi.text(g, McUi.ellipsize(state + " · " + McUi.ellipsize(string(latest, "goal", "No task"), 400), w - PAD * 2), PAD * s, 18 * s, stateColor(state), s);
			// Footer: token usage.
			List<String> usage = usageLines(latest);
			int footerH = usage.size() * LINE + PAD;
			int footerY = h - footerH;
			g.setColor(new Color(0, 0, 0, 120));
			g.fillRect(0, footerY * s, getWidth(), footerH * s);
			for (int i = 0; i < usage.size(); i++) McUi.text(g, McUi.ellipsize(usage.get(i), w - PAD * 2), PAD * s, (footerY + 3 + i * LINE) * s, McUi.GRAY, s);
			// Log body in a chat-like translucent box.
			int top = HEADER + PAD, bottom = footerY - PAD;
			McUi.panel(g, PAD * s, top * s, (w - PAD * 2) * s, (bottom - top) * s, new Color(0, 0, 0, 140), null);
			List<Line> lines = lines(w - PAD * 2 - 10);
			int capacity = Math.max(1, (bottom - top - 8) / LINE);
			scrollBack = Math.min(scrollBack, Math.max(0, lines.size() - capacity));
			int end = lines.size() - scrollBack, start = Math.max(0, end - capacity);
			int y = top + 4;
			for (int i = start; i < end; i++) {
				Line line = lines.get(i);
				int x = PAD + 5;
				if (!line.tag().isEmpty()) {
					McUi.text(g, line.tag(), x * s, y * s, line.tagColor(), s);
					x += McUi.width(line.tag()) + 4;
				}
				McUi.text(g, line.text(), x * s, y * s, McUi.WHITE, s);
				y += LINE;
			}
			if (scrollBack > 0) {
				String more = "Scrolled back " + scrollBack + " lines";
				McUi.text(g, more, (w - PAD - 6 - McUi.width(more)) * s, (bottom - LINE) * s, McUi.YELLOW, s);
			}
			g.dispose();
		}

		private record Line(String tag, Color tagColor, String text) {
		}

		private List<Line> lines(int width) {
			List<Line> out = new ArrayList<>();
			if (!latest.has("events")) {
				out.add(new Line("", McUi.GRAY, "No events yet."));
				return out;
			}
			for (var value : latest.getAsJsonArray("events")) {
				var e = value.getAsJsonObject();
				String stage = string(e, "stage", "event").replace("live_", "");
				String tag = "[" + stage + "]";
				int textWidth = Math.max(40, width - McUi.width(tag) - 4);
				List<String> wrapped = McUi.wrap(string(e, "message", ""), textWidth);
				for (int i = 0; i < wrapped.size(); i++) out.add(new Line(i == 0 ? tag : "", stageColor(stage), wrapped.get(i)));
			}
			return out;
		}
	}
}
