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

/** Flat command-console control used instead of Minecraft's generic stone button. */
public final class ConsoleButton extends AbstractWidget implements ConsoleFocusTarget {
	public enum Tone {
		SECONDARY,
		PRIMARY,
		DANGER
	}
	private static final int IDLE_BORDER = 0xFF46515F;
	private static final int IDLE_FILL = 0xFF222B36;
	private static final int HOVER_FILL = 0xFF2D3947;
	private static final int DISABLED_FILL = 0xFF1B2129;
	private static final int DISABLED_TEXT = 0xFF6E7884;
	private static final int TEXT = 0xFFF2F5F8;
	private final Font font;
	private final Runnable onPress;
	private final int accent;
	private final boolean selected;
	private final Tone tone;

	public ConsoleButton(
			Font font,
			int x,
			int y,
			int width,
			int height,
			Component message,
			boolean selected,
			int accent,
			Runnable onPress
	) {
		this(font, x, y, width, height, message, selected, accent, Tone.SECONDARY, onPress);
	}

	public ConsoleButton(
			Font font,
			int x,
			int y,
			int width,
			int height,
			Component message,
			boolean selected,
			int accent,
			Tone tone,
			Runnable onPress
	) {
		super(x, y, width, height, Objects.requireNonNull(message, "message must not be null"));
		this.font = Objects.requireNonNull(font, "font must not be null");
		this.onPress = Objects.requireNonNull(onPress, "onPress must not be null");
		this.selected = selected;
		this.accent = accent;
		this.tone = Objects.requireNonNull(tone, "tone must not be null");
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int border = isFocused() ? ConsoleTheme.FOCUS
				: tone == Tone.DANGER ? ConsoleTheme.ERROR
				: tone == Tone.PRIMARY || selected ? accent : IDLE_BORDER;
		int fill = !active ? DISABLED_FILL
				: tone == Tone.PRIMARY ? (isHoveredOrFocused() ? 0xFFFFD27A : accent)
				: tone == Tone.DANGER ? (isHoveredOrFocused() ? 0xFF573038 : 0xFF44242B)
				: selected ? 0xFF313943 : isHoveredOrFocused() ? HOVER_FILL : IDLE_FILL;
		graphics.fill(getX(), getY(), getRight(), getBottom(), border);
		int inset = isFocused() || selected ? 2 : 1;
		graphics.fill(getX() + inset, getY() + inset, getRight() - inset, getBottom() - inset, fill);
		int text = active ? (tone == Tone.PRIMARY ? ConsoleTheme.TRACK : TEXT) : DISABLED_TEXT;
		ConsoleText.centered(graphics, font, fittedMessage(), getX() + getWidth() / 2,
				getY() + (getHeight() - 9) / 2, text);
	}

	private Component fittedMessage() {
		String value = getMessage().getString();
		int available = Math.max(8, getWidth() - 10);
		if (ConsoleText.width(font, value) <= available) return getMessage();
		String suffix = "...";
		int end = value.length();
		int target = Math.max(1, available - ConsoleText.width(font, suffix));
		while (end > 0 && ConsoleText.width(font, value.substring(0, end)) > target) end--;
		return Component.literal(value.substring(0, end) + suffix);
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
		return "button:" + getMessage().getString();
	}
}
