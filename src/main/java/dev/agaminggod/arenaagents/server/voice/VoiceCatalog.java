package dev.agaminggod.arenaagents.server.voice;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Director choices use Fish Official original voices; legacy IDs still load. */
public final class VoiceCatalog {
	public record Choice(String id, String label) {}
	private static final List<Choice> CHOICES = List.of(
			new Choice("voice.laura.v1", "Laura - deep and warm, female"),
			new Choice("voice.adrian.v1", "Adrian - deep and measured, male"),
			new Choice("voice.sarah.v1", "Sarah - gentle and sincere, female"),
			new Choice("voice.ethan.v1", "Ethan - clear and calm, male"),
			new Choice("voice.selene.v1", "Selene - soft and calm, female"),
			new Choice("voice.jordan.v1", "Jordan - confident and measured, male"));
	private static final List<String> LEGACY_IDS = List.of(
			"voice.moss.v1", "voice.flint.v1", "voice.ember.v1", "voice.wren.v1", "voice.cedar.v1",
			"voice.sable.v1", "voice.quill.v1", "voice.rook.v1", "voice.juniper.v1", "voice.vale.v1",
			"voice.kestrel.v1", "voice.sol.v1", "voice.reed.v1", "voice.nova.v1", "voice.ash.v1", "voice.piper.v1");

	private VoiceCatalog() {}
	public static List<Choice> choices() { return CHOICES; }
	public static List<String> selectableIds() {
		return Stream.concat(Stream.of(VoiceProfile.DEFAULT_PROFILE_ID), CHOICES.stream().map(Choice::id)).toList();
	}
	public static boolean accepts(String id) { return selectableIds().contains(id) || LEGACY_IDS.contains(id); }
	public static String label(String id) {
		if (VoiceProfile.DEFAULT_PROFILE_ID.equals(id)) return "Character default";
		return CHOICES.stream().filter(choice -> choice.id().equals(id)).map(Choice::label)
				.findFirst().orElse("Saved voice - " + id);
	}

	public static String defaultFor(String name, String appearance) {
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.contains("astra")) return "voice.laura.v1";
		if (lower.contains("fable")) return "voice.adrian.v1";
		if (lower.contains("grok")) return "voice.jordan.v1";
		if (lower.contains("gemini")) return "voice.ethan.v1";
		if (lower.contains("kimi")) return "voice.sarah.v1";
		return switch (appearance.toLowerCase(Locale.ROOT)) {
			case "claude" -> "voice.adrian.v1";
			case "cursor" -> "voice.jordan.v1";
			case "gemini" -> "voice.ethan.v1";
			case "kimi" -> "voice.sarah.v1";
			default -> "voice.laura.v1";
		};
	}
}
