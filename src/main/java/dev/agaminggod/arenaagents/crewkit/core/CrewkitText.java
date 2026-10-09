package dev.agaminggod.arenaagents.crewkit.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** SNBT text components, the board palette, and money formatting for display entities. */
public final class CrewkitText {
	public static final int WHITE = 0xF4F1EA;
	public static final int MUTED = 0x9AA0A6;
	public static final int GREEN = 0x3DDC84;
	public static final int AMBER = 0xFFB020;
	public static final int RED = 0xFF4D3D;
	public static final int BLUE = 0x6E8BFF;

	private CrewkitText() {}

	/** {@code {text:"...",color:"#RRGGBB",bold:1b}} */
	public static String of(String text, int rgb, boolean bold) {
		return "{text:\"" + escape(text) + "\",color:\"" + hex(rgb) + "\"" + (bold ? ",bold:1b" : "") + "}";
	}

	/** Join components into one line. */
	public static String join(String... parts) {
		return "[\"\"," + String.join(",", parts) + "]";
	}

	/**
	 * Short on-screen label for a failed/expired run, e.g. "RUN FAILED: ENOENT". Raw reasons can be long
	 * paths or stack text, so only an error code (or a short reason) is shown; callers log the full reason.
	 */
	public static String failureLabel(String event, String code, String reason) {
		String head = "expired".equals(event) ? "RUN EXPIRED" : "RUN FAILED";
		String tag = code == null ? "" : code.trim();
		String why = reason == null ? "" : reason.trim();
		if (tag.isEmpty()) {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(E[A-Z]{2,}|[A-Z][A-Z0-9]*_[A-Z0-9_]+)\\b").matcher(why);
			if (m.find()) tag = m.group(1);
			else if (!why.isEmpty() && why.length() <= 16) tag = why;
		}
		if (tag.length() > 16) tag = tag.substring(0, 16);
		return tag.isEmpty() ? head : head + ": " + tag.toUpperCase(Locale.ROOT);
	}

	public static String hex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	/** ARGB int for the text_display {@code background} field. */
	public static int argb(int alpha, int rgb) {
		return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
	}

	public static String escape(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
	}

	/** 134.5 SGD -> "S$134.50"; -12 SGD -> "-S$12.00"; other currencies use their code. */
	public static String money(double amount, String currency) {
		DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
		BigDecimal rounded = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
		String sign = rounded.signum() < 0 ? "-" : "";
		return sign + symbol(currency) + format.format(rounded.abs());
	}

	public static String symbol(String currency) {
		if (currency == null || currency.isBlank() || "SGD".equalsIgnoreCase(currency)) return "S$";
		if ("USD".equalsIgnoreCase(currency)) return "US$";
		return currency.toUpperCase(Locale.ROOT) + " ";
	}
}
