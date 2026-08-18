package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleText;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Purpose-built, two-line agent selector with an explicit full-row selection state. */
public final class ConsoleSelectionRow extends AbstractWidget implements ConsoleFocusTarget {
	private static final int BORDER = 0xFF46515F;
	private static final int FILL = 0xFF222B36;
	private static final int HOVER_FILL = 0xFF2B3744;
	private static final int SELECTED_FILL = 0xFF35465A;
	private static final int TEXT = 0xFFF2F5F8;
	private static final int MUTED = 0xFFAEB8C4;
	private final Font font;
	private final Component badge;
	private final Component primary;
	private final Component secondary;
	private final Component status;
	private final String focusIdentity;
	private final boolean selected;
	private final boolean groupMode;
	private final int accent;
	private final Runnable onPress;

	public ConsoleSelectionRow(
			Font font, int x, int y, int width, int height,
			Component badge, Component primary, Component secondary, Component status, String focusIdentity,
			boolean selected, boolean groupMode, int accent, Runnable onPress
	) {
		super(x, y, width, height, Component.literal(
				(selected ? (groupMode ? "In group: " : "Selected: ") : "") + primary.getString()
						+ " | " + secondary.getString() + " | " + status.getString()));
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.badge = Objects.requireNonNull(badge, "badge must not be null");
		this.primary = Objects.requireNonNull(primary, "primary must not be null");
		this.secondary = Objects.requireNonNull(secondary, "secondary must not be null");
		this.status = Objects.requireNonNull(status, "status must not be null");
		this.focusIdentity = Objects.requireNonNull(focusIdentity, "focusIdentity must not be null");
		this.selected = selected;
		this.groupMode = groupMode;
		this.accent = accent;
		this.onPress = Objects.requireNonNull(onPress, "onPress must not be null");
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int fill = selected ? SELECTED_FILL : isHoveredOrFocused() ? HOVER_FILL : FILL;
		int outline = isFocused() ? ConsoleTheme.FOCUS : selected ? accent : BORDER;
		graphics.fill(getX(), getY(), getRight(), getBottom(), outline);
		int inset = isFocused() || selected ? 2 : 1;
		graphics.fill(getX() + inset, getY() + inset, getRight() - inset, getBottom() - inset, fill);

		int badgeSize = Math.min(24, getHeight() - 8);
		int badgeX = getX() + 6;
		int badgeY = getY() + (getHeight() - badgeSize) / 2;
		graphics.fill(badgeX, badgeY, badgeX + badgeSize, badgeY + badgeSize, selected ? accent : BORDER);
		graphics.fill(badgeX + 1, badgeY + 1, badgeX + badgeSize - 1, badgeY + badgeSize - 1,
				selected ? 0xFF202833 : 0xFF293442);
		ConsoleText.centered(graphics, font, groupMode && selected ? Component.literal("X") : badge,
				badgeX + badgeSize / 2, badgeY + (badgeSize - 9) / 2, selected ? accent : TEXT);

		int textX = badgeX + badgeSize + 8;
		int rightReserve = Math.max(72, ConsoleText.width(font, status) + 12);
		String primaryText = fit(primary.getString(), Math.max(24, getRight() - rightReserve - textX));
		ConsoleText.text(graphics, font, primaryText, textX, getY() + 7, TEXT);
		String secondaryText = fit(secondary.getString(), Math.max(24, getRight() - textX - 8));
		ConsoleText.text(graphics, font, secondaryText, textX, getY() + 21, MUTED);
		ConsoleText.text(graphics, font, selected ? Component.literal(groupMode ? "IN GROUP" : "SELECTED") : status,
				getRight() - rightReserve + 4, getY() + 7, selected ? accent : MUTED);
		if (selected) {
			ConsoleText.text(graphics, font, status, getRight() - rightReserve + 4, getY() + 21, MUTED);
		}
	}

	private String fit(String value, int available) {
		if (ConsoleText.width(font, value) <= available) return value;
		String suffix = "...";
		int end = value.length();
		int target = Math.max(1, available - ConsoleText.width(font, suffix));
		while (end > 0 && ConsoleText.width(font, value.substring(0, end)) > target) end--;
		return value.substring(0, end) + suffix;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubled) {
		if (active) onPress.run();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (active && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) {
			onPress.run();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}

	@Override
	public String consoleFocusIdentity() {
		return "agent-row:" + focusIdentity;
	}
}
