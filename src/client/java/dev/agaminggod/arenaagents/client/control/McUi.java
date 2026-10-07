package dev.agaminggod.arenaagents.client.control;

import com.mojang.blaze3d.platform.IconSet;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft look for the desktop windows: the game's bitmap font, dirt background, bevelled panels and
 * button sprite, all read from the vanilla client jar so the windows match the game without bundled art.
 */
final class McUi {
	private static final Logger LOGGER = LoggerFactory.getLogger(McUi.class);
	static final Color WHITE = new Color(0xFFFFFF), GRAY = new Color(0xAAAAAA), DARK_GRAY = new Color(0x555555),
			GREEN = new Color(0x55FF55), YELLOW = new Color(0xFFFF55), GOLD = new Color(0xFFAA00), AQUA = new Color(0x55FFFF),
			RED = new Color(0xFF5555), LIGHT_PURPLE = new Color(0xFF55FF), BLUE = new Color(0x5555FF);
	private static final int GLYPH = 8;
	private static BufferedImage font, dirt, button;
	private static final int[] widths = new int[256];
	private static List<Image> icons;
	private static boolean loaded;

	private McUi() {
	}

	private static synchronized void load() {
		if (loaded) return;
		loaded = true;
		font = read("assets/minecraft/textures/font/ascii.png");
		dirt = read("assets/minecraft/textures/block/dirt.png");
		button = read("assets/minecraft/textures/gui/sprites/widget/button.png");
		for (int code = 0; code < 256; code++) widths[code] = code == ' ' ? 4 : glyphWidth(code);
	}

	private static BufferedImage read(String path) {
		try (InputStream in = McUi.class.getClassLoader().getResourceAsStream(path)) {
			return in == null ? null : ImageIO.read(in);
		} catch (Exception exception) {
			LOGGER.debug("Could not read {}", path, exception);
			return null;
		}
	}

	/** Vanilla's own window icons (16 to 256 px); empty outside a running client. */
	static synchronized List<Image> windowIcons() {
		if (icons != null) return icons;
		ArrayList<Image> list = new ArrayList<>();
		try {
			for (var supplier : IconSet.RELEASE.getStandardIcons(Minecraft.getInstance().getVanillaPackResources())) {
				try (InputStream in = supplier.get()) {
					BufferedImage image = ImageIO.read(in);
					if (image != null) list.add(image);
				}
			}
		} catch (Throwable throwable) {
			LOGGER.debug("Minecraft window icons are unavailable", throwable);
		}
		icons = List.copyOf(list);
		return icons;
	}

	private static int glyphWidth(int code) {
		if (font == null) return 6;
		int cell = font.getWidth() / 16, scale = Math.max(1, cell / GLYPH), x0 = (code % 16) * cell, y0 = (code / 16) * cell;
		for (int column = cell - 1; column >= 0; column--) {
			for (int row = 0; row < cell; row++) {
				if ((font.getRGB(x0 + column, y0 + row) >>> 24) > 0) return column / scale + 2;
			}
		}
		return 0;
	}

	/** Width of text in game pixels (before scaling). */
	static int width(String text) {
		load();
		int total = 0;
		for (int i = 0; i < text.length(); i++) total += widths[glyphCode(text.charAt(i))];
		return Math.max(0, total - 1);
	}

	private static final java.nio.charset.Charset CP437 = java.nio.charset.Charset.forName("IBM437");

	/** ascii.png is laid out in code page 437 (as vanilla's bitmap provider is), not Latin-1. */
	private static int glyphCode(char character) {
		if (character < 128) return character;
		if (character == '·') return '|'; // the font's middle dot glyph is blank
		byte[] encoded = String.valueOf(character).getBytes(CP437);
		return encoded.length == 1 && encoded[0] != '?' ? encoded[0] & 0xFF : '?';
	}

	/** Draws text with the vanilla drop shadow; scale is screen pixels per game pixel. */
	static void text(Graphics2D g, String text, int x, int y, Color color, int scale) {
		load();
		Color shadow = new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4);
		draw(g, text, x + scale, y + scale, shadow, scale);
		draw(g, text, x, y, color, scale);
	}

	private static void draw(Graphics2D g, String text, int x, int y, Color color, int scale) {
		if (font == null) {
			g.setColor(color);
			g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 8 * scale));
			g.drawString(text, x, y + 7 * scale);
			return;
		}
		BufferedImage atlas = tinted(color);
		int cell = font.getWidth() / 16, size = GLYPH * scale;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		int cursor = x;
		for (int i = 0; i < text.length(); i++) {
			int code = glyphCode(text.charAt(i));
			if (code != ' ') {
				int sx = (code % 16) * cell, sy = (code / 16) * cell;
				g.drawImage(atlas, cursor, y, cursor + size, y + size, sx, sy, sx + cell, sy + cell, null);
			}
			cursor += widths[code] * scale;
		}
	}

	private static final java.util.Map<Integer, BufferedImage> TINTS = new java.util.LinkedHashMap<>(16, .75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Integer, BufferedImage> eldest) {
			return size() > 32;
		}
	};

	/** The font atlas recoloured once per colour, so text draws as image blits. */
	private static synchronized BufferedImage tinted(Color color) {
		return TINTS.computeIfAbsent(color.getRGB() | 0xFF000000, rgb -> {
			BufferedImage copy = new BufferedImage(font.getWidth(), font.getHeight(), BufferedImage.TYPE_INT_ARGB);
			for (int py = 0; py < font.getHeight(); py++) for (int px = 0; px < font.getWidth(); px++) {
				int alpha = font.getRGB(px, py) >>> 24;
				if (alpha > 0) copy.setRGB(px, py, (alpha << 24) | (rgb & 0xFFFFFF));
			}
			return copy;
		});
	}

	/** Greedy word wrap in game pixels; long words are split. */
	static List<String> wrap(String text, int maxWidth) {
		ArrayList<String> lines = new ArrayList<>();
		for (String paragraph : text.split("\n", -1)) {
			String line = "";
			for (String word : paragraph.split(" ")) {
				while (width(word) > maxWidth && word.length() > 1) {
					int cut = word.length() - 1;
					while (cut > 1 && width(word.substring(0, cut)) > maxWidth) cut--;
					if (!line.isEmpty()) { lines.add(line); line = ""; }
					lines.add(word.substring(0, cut));
					word = word.substring(cut);
				}
				String next = line.isEmpty() ? word : line + " " + word;
				if (!line.isEmpty() && width(next) > maxWidth) { lines.add(line); line = word; } else line = next;
			}
			lines.add(line);
		}
		return lines;
	}

	/** Shortens text with "..." so it fits maxWidth game pixels. */
	static String ellipsize(String text, int maxWidth) {
		if (width(text) <= maxWidth) return text;
		String cut = text;
		while (!cut.isEmpty() && width(cut + "...") > maxWidth) cut = cut.substring(0, cut.length() - 1);
		return cut + "...";
	}

	/** Vanilla options-screen background: dirt tiled at 4x and darkened. */
	static void dirtBackground(Graphics2D g, int width, int height, float brightness) {
		load();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		if (dirt != null) {
			int tile = 64;
			for (int y = 0; y < height; y += tile) for (int x = 0; x < width; x += tile) g.drawImage(dirt, x, y, tile, tile, null);
		} else {
			g.setColor(new Color(0x3b2a1c));
			g.fillRect(0, 0, width, height);
		}
		g.setColor(new Color(0, 0, 0, Math.round((1 - brightness) * 255)));
		g.fillRect(0, 0, width, height);
	}

	/** A dark inset panel with the vanilla two-tone bevel (like list and slot backgrounds). */
	static void panel(Graphics2D g, int x, int y, int width, int height, Color fill, Color border) {
		g.setColor(fill);
		g.fillRect(x, y, width, height);
		g.setColor(new Color(0, 0, 0, 160));
		g.fillRect(x, y, width, 2);
		g.fillRect(x, y, 2, height);
		g.setColor(new Color(255, 255, 255, 40));
		g.fillRect(x, y + height - 2, width, 2);
		g.fillRect(x + width - 2, y, 2, height);
		if (border != null) {
			g.setColor(border);
			g.drawRect(x, y, width - 1, height - 1);
			g.drawRect(x + 1, y + 1, width - 3, height - 3);
		}
	}

	/** Vanilla button sprite, nine-sliced with a 3 px border at the given scale. */
	static void button(Graphics2D g, int x, int y, int width, int height, int scale) {
		load();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		if (button == null) {
			panel(g, x, y, width, height, new Color(0x6f6f6f), Color.BLACK);
			return;
		}
		int b = 3, sw = button.getWidth(), sh = button.getHeight(), d = b * scale;
		int[][] cols = {{0, b, x, d}, {b, sw - b, x + d, width - 2 * d}, {sw - b, sw, x + width - d, d}};
		int[][] rows = {{0, b, y, d}, {b, sh - b, y + d, height - 2 * d}, {sh - b, sh, y + height - d, d}};
		for (int[] c : cols) for (int[] r : rows) {
			if (c[3] <= 0 || r[3] <= 0) continue;
			g.drawImage(button, c[2], r[2], c[2] + c[3], r[2] + r[3], c[0], r[0], c[1], r[1], null);
		}
	}
}
