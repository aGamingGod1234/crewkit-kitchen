package dev.agaminggod.arenaagents.client.gui.widget;

import dev.agaminggod.arenaagents.client.gui.ConsoleFocusTarget;
import dev.agaminggod.arenaagents.client.gui.ConsoleTheme;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A selectable popup list. The host routes popup input before other controls and draws it last. */
public final class ConsoleDropdown<T> extends AbstractWidget implements ConsoleFocusTarget {
	private static final int OPTION_HEIGHT = 22;
	private final Font font;
	private final String label;
	private final List<T> values;
	private final Function<T, String> formatter;
	private final Consumer<T> changed;
	private final int viewportWidth;
	private final int viewportHeight;
	private int selected;
	private int highlighted;
	private int first;
	private boolean open;

	public ConsoleDropdown(Font font, int x, int y, int width, int height, String label,
			List<T> values, T initial, Function<T, String> formatter, Consumer<T> changed,
			int viewportWidth, int viewportHeight) {
		super(x, y, width, height, Component.empty());
		this.font = font;
		this.label = label;
		this.values = List.copyOf(values);
		this.selected = values.indexOf(initial);
		if (selected < 0) throw new IllegalArgumentException("Dropdown selection must exist");
		this.highlighted = selected;
		this.formatter = formatter;
		this.changed = changed;
		this.viewportWidth = viewportWidth;
		this.viewportHeight = viewportHeight;
		updateMessage();
	}

	public boolean isOpen() { return open && visible && active; }
	public void close() { open = false; }
	private int rows() { return Math.min(values.size(), Math.max(1, (viewportHeight - 16) / OPTION_HEIGHT)); }
	private int popupWidth() { return Math.min(Math.max(getWidth(), 330), Math.max(1, viewportWidth - 16)); }
	private int popupX() { return Math.max(8, Math.min(getX(), viewportWidth - popupWidth() - 8)); }
	private int popupY() { return Math.max(8, Math.min(getBottom() + 2, viewportHeight - rows() * OPTION_HEIGHT - 8)); }
	private void reveal() { first = Math.clamp(first, Math.max(0, highlighted - rows() + 1), highlighted); }
	private void updateMessage() { setMessage(Component.literal(label + ": " + formatter.apply(values.get(selected)))); }

	@Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(getX(), getY(), getRight(), getBottom(), isFocused() ? ConsoleTheme.FOCUS : ConsoleTheme.BORDER);
		graphics.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, ConsoleTheme.SURFACE);
		graphics.text(font, font.plainSubstrByWidth(formatter.apply(values.get(selected)), getWidth() - 25), getX() + 6, getY() + (height - 9) / 2, ConsoleTheme.TEXT, false);
		graphics.text(font, open ? "^" : "v", getRight() - 13, getY() + (height - 9) / 2, ConsoleTheme.MUTED, false);
		if (isHovered()) graphics.setTooltipForNextFrame(font, getMessage(), mouseX, mouseY);
	}

	public void renderPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!isOpen()) return;
		graphics.nextStratum();
		int x = popupX(), y = popupY(), width = popupWidth();
		graphics.fill(x - 1, y - 1, x + width + 1, y + rows() * OPTION_HEIGHT + 1, ConsoleTheme.FOCUS);
		for (int row = 0; row < rows(); row++) {
			int index = first + row;
			boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y + row * OPTION_HEIGHT && mouseY < y + (row + 1) * OPTION_HEIGHT;
			graphics.fill(x, y + row * OPTION_HEIGHT, x + width, y + (row + 1) * OPTION_HEIGHT,
					hover || index == highlighted ? ConsoleTheme.SURFACE_HOVER : ConsoleTheme.SURFACE);
			String text = (index == selected ? "* " : "  ") + formatter.apply(values.get(index));
			graphics.text(font, font.plainSubstrByWidth(text, width - 12), x + 5, y + row * OPTION_HEIGHT + 7, ConsoleTheme.TEXT, false);
			if (hover) graphics.setTooltipForNextFrame(font, Component.literal(text.strip()), mouseX, mouseY);
		}
		if (rows() < values.size()) {
			int track = rows() * OPTION_HEIGHT;
			int thumb = Math.max(8, track * rows() / values.size());
			int offset = (track - thumb) * first / (values.size() - rows());
			graphics.fill(x + width - 3, y + offset, x + width - 1, y + offset + thumb, ConsoleTheme.MUTED);
		}
	}

	@Override public void onClick(MouseButtonEvent event, boolean doubled) {
		open = !open;
		highlighted = selected;
		reveal();
	}

	/** Consumes even outside clicks so closing the list cannot trigger an underlying action. */
	public boolean popupClick(MouseButtonEvent event) {
		if (!isOpen()) return false;
		int row = (int) Math.floor((event.y() - popupY()) / OPTION_HEIGHT);
		if (event.button() == 0 && event.x() >= popupX() && event.x() < popupX() + popupWidth() && row >= 0 && row < rows()) select(first + row);
		close();
		return true;
	}

	public boolean popupScroll(double vertical) {
		if (!isOpen()) return false;
		if (vertical != 0) first = Math.clamp(first + (vertical > 0 ? -1 : 1), 0, values.size() - rows());
		return true;
	}

	private void select(int index) {
		selected = index;
		highlighted = index;
		close();
		updateMessage();
		changed.accept(values.get(selected));
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (!active || !visible) return false;
		int key = event.key();
		if (key == GLFW.GLFW_KEY_ESCAPE && open) { close(); return true; }
		if (key == GLFW.GLFW_KEY_TAB) { close(); return false; }
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
			if (open) select(highlighted); else { open = true; highlighted = selected; reveal(); }
			return true;
		}
		if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) {
			open = true;
			highlighted = key == GLFW.GLFW_KEY_HOME ? 0 : key == GLFW.GLFW_KEY_END ? values.size() - 1
					: Math.clamp(highlighted + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, values.size() - 1);
			reveal();
			return true;
		}
		return false;
	}

	@Override protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
		output.add(NarratedElementType.HINT, Component.literal(open ? formatter.apply(values.get(highlighted)) + ". Use arrows to browse, Enter to select, Escape to cancel." : "Press Enter to open choices."));
	}
	@Override public String consoleFocusIdentity() { return "dropdown:" + label; }
}
