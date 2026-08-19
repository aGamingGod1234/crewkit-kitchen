package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.client.gui.AgentRosterGrid;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.control.AgentRosterEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** One concise agent portrait that emits semantic roster intents without owning roster state. */
public final class AgentRosterTile extends AbstractWidget implements ConsoleFocusTarget {
	private static final String ELLIPSIS = "…";
	private static final int FACE_MAXIMUM = 32;
	private static final int TEXT_GAP = 7;
	private static final int CHECK_RESERVE = 14;

	private final Font font;
	private final AgentRosterEntry entry;
	private final Identifier texture;
	private final int position;
	private final int total;
	private final boolean selected;
	private final AgentRosterGrid.Mode mode;
	private final BooleanSupplier contextFocused;
	private final Consumer<Intent> onIntent;

	public AgentRosterTile(
			Font font,
			AgentRosterEntry entry,
			AgentVisualIdentity.Resolved visual,
			int x,
			int y,
			int width,
			int height,
			int position,
			int total,
			boolean selected,
			AgentRosterGrid.Mode mode,
			BooleanSupplier contextFocused,
			Consumer<Intent> onIntent
	) {
		super(x, y, width, height, Component.literal(narrationText(entry, position, total, selected)));
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.entry = Objects.requireNonNull(entry, "entry must not be null");
		AgentVisualIdentity.Resolved checkedVisual = Objects.requireNonNull(
				visual, "visual identity must not be null");
		this.texture = Identifier.parse(checkedVisual.texturePath());
		if (position < 1 || total < position) {
			throw new IllegalArgumentException("Roster tile position must be within the filtered total");
		}
		this.position = position;
		this.total = total;
		this.selected = selected;
		this.mode = Objects.requireNonNull(mode, "mode must not be null");
		this.contextFocused = Objects.requireNonNull(contextFocused, "context focus must not be null");
		this.onIntent = Objects.requireNonNull(onIntent, "intent consumer must not be null");
	}

	@Override
	protected void extractWidgetRenderState(
			GuiGraphicsExtractor graphics,
			int mouseX,
			int mouseY,
			float partialTick
	) {
		int surface = selected ? ConsoleTheme.ROSTER_SELECTED_SURFACE
				: entry.selectable() ? ConsoleTheme.SURFACE : ConsoleTheme.ROSTER_UNAVAILABLE_SURFACE;
		if (isHovered() && !selected && entry.selectable()) {
			surface = ConsoleTheme.SURFACE_HOVER;
		}
		graphics.fill(getX(), getY(), getRight(), getBottom(), surface);
		graphics.outline(
				getX(), getY(), getWidth(), getHeight(),
				focusVisible(isFocused(), contextFocused.getAsBoolean())
						? ConsoleTheme.ROSTER_FOCUS : ConsoleTheme.BORDER
		);

		int portraitSize = Math.min(FACE_MAXIMUM, Math.max(16, getHeight() - 12));
		int portraitX = getX() + 6;
		int portraitY = getY() + (getHeight() - portraitSize) / 2;
		PlayerFaceExtractor.extractRenderState(
				graphics,
				texture,
				portraitX,
				portraitY,
				portraitSize,
				true,
				false,
				0xFFFFFFFF
		);

		int textX = portraitX + portraitSize + TEXT_GAP;
		int textRight = getRight() - 6 - (selected ? CHECK_RESERVE : 0);
		int availableTextWidth = Math.max(0, textRight - textX);
		String fittedName = ellipsize(entry.name(), availableTextWidth, value -> ConsoleText.width(font, value));
		int nameY = getY() + Math.max(5, (getHeight() - 22) / 2);
		ConsoleText.text(graphics, font, fittedName, textX, nameY, ConsoleTheme.TEXT);
		String fittedModel = ellipsize(
				entry.modelLabel(), availableTextWidth - 10, value -> ConsoleText.width(font, value));
		ConsoleText.text(graphics, font, fittedModel, textX + 10, nameY + 12, ConsoleTheme.MUTED);
		drawStateCue(graphics, textX, nameY + 14, entry.state());
		if (selected) {
			ConsoleText.text(graphics, font, "✓", getRight() - 13, nameY, ConsoleTheme.ACCENT);
		}
	}

	private static void drawStateCue(GuiGraphicsExtractor graphics, int x, int y, String state) {
		int color = stateColor(state);
		switch (stateCue(state)) {
			case BAR -> graphics.fill(x, y, x + 6, y + 2, color);
			case SQUARE -> graphics.outline(x, y - 1, 5, 5, color);
			case PAUSE -> {
				graphics.fill(x, y - 1, x + 2, y + 4, color);
				graphics.fill(x + 4, y - 1, x + 6, y + 4, color);
			}
			case CROSS -> {
				graphics.fill(x, y - 1, x + 2, y + 1, color);
				graphics.fill(x + 4, y - 1, x + 6, y + 1, color);
				graphics.fill(x + 2, y + 1, x + 4, y + 3, color);
			}
		}
	}

	private static StateCue stateCue(String state) {
		String value = state == null ? "" : state.toLowerCase(java.util.Locale.ROOT);
		if (value.contains("work") || value.contains("running")) return StateCue.BAR;
		if (value.contains("idle") || value.contains("ready")) return StateCue.SQUARE;
		if (value.contains("block") || value.contains("wait")) return StateCue.PAUSE;
		return StateCue.CROSS;
	}

	private static int stateColor(String state) {
		return switch (stateCue(state)) {
			case BAR -> ConsoleTheme.SUCCESS;
			case SQUARE -> ConsoleTheme.MUTED;
			case PAUSE -> ConsoleTheme.ACCENT;
			case CROSS -> ConsoleTheme.ERROR;
		};
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubled) {
		if (!active) return;
		for (IntentType type : clickIntentTypes(mode, event.hasShiftDown(), doubled)) {
			emit(type);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!active) return super.keyPressed(event);
		Optional<IntentType> intent = keyIntent(event.key(), event.isSelectAll(), mode);
		if (intent.isEmpty()) return super.keyPressed(event);
		emit(intent.orElseThrow());
		return true;
	}

	@Override
	public void setFocused(boolean focused) {
		boolean wasFocused = isFocused();
		super.setFocused(focused);
		if (shouldEmitFocus(wasFocused, focused, contextFocused.getAsBoolean())) {
			onIntent.accept(new Intent(IntentType.FOCUS, entry.id()));
		}
	}

	public static boolean shouldEmitFocus(
			boolean wasFocused,
			boolean focused,
			boolean contextAlreadyFocused
	) {
		return focused && !wasFocused && !contextAlreadyFocused;
	}

	public static boolean focusVisible(boolean keyboardFocused, boolean contextFocused) {
		return keyboardFocused || contextFocused;
	}

	private void emit(IntentType type) {
		if (type == IntentType.FOCUS && contextFocused.getAsBoolean()) return;
		onIntent.accept(new Intent(type, entry.id()));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, narrationText(entry, position, total, selected));
	}

	@Override
	public String consoleFocusIdentity() {
		return focusIdentity(entry.id());
	}

	public static String focusIdentity(String exactId) {
		return "agent-tile:" + Objects.requireNonNull(exactId, "agent ID must not be null");
	}

	public static String narrationText(
			AgentRosterEntry entry,
			int position,
			int total,
			boolean selected
	) {
		Objects.requireNonNull(entry, "entry must not be null");
		if (position < 1 || total < position) {
			throw new IllegalArgumentException("Roster narration position must be within the filtered total");
		}
		StringBuilder narration = new StringBuilder()
				.append("Agent ").append(position).append(" of ").append(total).append(". ")
				.append(entry.name()).append(". ")
				.append(entry.modelLabel()).append(". ")
				.append(entry.state()).append(". ")
				.append(selected ? "Selected." : "Not selected.");
		if (!entry.selectable() && !entry.unavailableReason().isEmpty()) {
			narration.append(" Unavailable: ").append(entry.unavailableReason()).append('.');
		}
		return narration.toString();
	}

	public static String ellipsize(String value, int availableWidth, ToIntFunction<String> measure) {
		Objects.requireNonNull(value, "value must not be null");
		Objects.requireNonNull(measure, "measure must not be null");
		if (availableWidth <= 0) return "";
		if (measure.applyAsInt(value) <= availableWidth) return value;
		int suffixWidth = measure.applyAsInt(ELLIPSIS);
		if (suffixWidth > availableWidth) return "";
		int end = value.length();
		while (end > 0 && measure.applyAsInt(value.substring(0, end)) + suffixWidth > availableWidth) {
			end = value.offsetByCodePoints(0, value.codePointCount(0, end) - 1);
		}
		return value.substring(0, end) + ELLIPSIS;
	}

	public static List<IntentType> clickIntentTypes(
			AgentRosterGrid.Mode mode,
			boolean shiftDown,
			boolean doubled
	) {
		Objects.requireNonNull(mode, "mode must not be null");
		if (doubled) return List.of(IntentType.FOCUS, IntentType.OPEN);
		if (mode == AgentRosterGrid.Mode.FOCUS_ONLY) return List.of(IntentType.FOCUS);
		return List.of(IntentType.FOCUS, shiftDown ? IntentType.SELECT_RANGE : IntentType.TOGGLE);
	}

	public static Optional<IntentType> keyIntent(
			int key,
			boolean platformSelectAll,
			AgentRosterGrid.Mode mode
	) {
		Objects.requireNonNull(mode, "mode must not be null");
		if (platformSelectAll) {
			return mode == AgentRosterGrid.Mode.MULTI_SELECT
					? Optional.of(IntentType.SELECT_ALL) : Optional.empty();
		}
		return switch (key) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> Optional.of(IntentType.OPEN);
			case GLFW.GLFW_KEY_SPACE -> mode == AgentRosterGrid.Mode.MULTI_SELECT
					? Optional.of(IntentType.TOGGLE) : Optional.empty();
			case GLFW.GLFW_KEY_LEFT -> Optional.of(IntentType.MOVE_LEFT);
			case GLFW.GLFW_KEY_RIGHT -> Optional.of(IntentType.MOVE_RIGHT);
			case GLFW.GLFW_KEY_UP -> Optional.of(IntentType.MOVE_UP);
			case GLFW.GLFW_KEY_DOWN -> Optional.of(IntentType.MOVE_DOWN);
			default -> Optional.empty();
		};
	}

	public enum IntentType {
		FOCUS,
		OPEN,
		TOGGLE,
		SELECT_RANGE,
		SELECT_ALL,
		MOVE_LEFT,
		MOVE_RIGHT,
		MOVE_UP,
		MOVE_DOWN
	}

	public record Intent(IntentType type, String agentId) {
		public Intent {
			Objects.requireNonNull(type, "intent type must not be null");
			Objects.requireNonNull(agentId, "agent ID must not be null");
		}
	}

	private enum StateCue {
		BAR,
		SQUARE,
		PAUSE,
		CROSS
	}
}
