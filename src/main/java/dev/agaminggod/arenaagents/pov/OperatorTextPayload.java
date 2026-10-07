package dev.agaminggod.arenaagents.pov;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Takeover text the operator typed into a vanilla screen for the agent's body: an anvil name, sign lines or book
 * pages. The server rebuilds the vanilla packet and hands it to the agent's own connection, so vanilla's handlers,
 * filtering and limits decide; the bounds here are the ones the vanilla packet codecs enforce.
 */
public record OperatorTextPayload(
		long sessionId,
		int sequence,
		Kind kind,
		BlockPos pos,
		int value,
		List<String> lines,
		Optional<String> title
) implements CustomPacketPayload {
	/** ServerboundRenameItemPacket reads a plain (32767) string; AnvilMenu then applies its own name limit. */
	public static final int MAX_RENAME_LENGTH = 32767;
	/** ServerboundSignUpdatePacket: four lines of at most 384 characters. */
	public static final int SIGN_LINES = 4;
	public static final int MAX_SIGN_LINE_LENGTH = 384;
	/** ServerboundEditBookPacket: at most 100 pages of 1024 characters and a 32 character title. */
	public static final int MAX_BOOK_PAGES = 100;
	public static final int MAX_BOOK_PAGE_LENGTH = 1024;
	public static final int MAX_BOOK_TITLE_LENGTH = 32;
	/** Room for 100 maximal pages in UTF-8 plus framing; larger payloads are refused before decoding. */
	public static final int MAX_ENCODED_BYTES = 512 * 1024;

	public enum Kind {
		RENAME_ITEM,
		SIGN_UPDATE,
		EDIT_BOOK
	}

	public static final Type<OperatorTextPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "operator_text")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, OperatorTextPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, OperatorTextPayload::sessionId,
			ByteBufCodecs.VAR_INT, OperatorTextPayload::sequence,
			PovPayloads.enumCodec(Kind.values()), OperatorTextPayload::kind,
			BlockPos.STREAM_CODEC, OperatorTextPayload::pos,
			ByteBufCodecs.VAR_INT, OperatorTextPayload::value,
			ByteBufCodecs.stringUtf8(MAX_RENAME_LENGTH).apply(ByteBufCodecs.list(MAX_BOOK_PAGES)), OperatorTextPayload::lines,
			ByteBufCodecs.stringUtf8(MAX_BOOK_TITLE_LENGTH).apply(ByteBufCodecs::optional), OperatorTextPayload::title,
			OperatorTextPayload::new
	);

	public OperatorTextPayload {
		if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
		kind = Objects.requireNonNull(kind, "kind must not be null");
		pos = Objects.requireNonNull(pos, "pos must not be null").immutable();
		lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
		title = Objects.requireNonNull(title, "title must not be null");
		switch (kind) {
			case RENAME_ITEM -> {
				if (lines.size() != 1 || lines.get(0).length() > MAX_RENAME_LENGTH || title.isPresent()) {
					throw new IllegalArgumentException("RENAME_ITEM carries exactly one name");
				}
			}
			case SIGN_UPDATE -> {
				if (lines.size() != SIGN_LINES || title.isPresent()) {
					throw new IllegalArgumentException("SIGN_UPDATE carries exactly four lines");
				}
				for (String line : lines) {
					if (line.length() > MAX_SIGN_LINE_LENGTH) {
						throw new IllegalArgumentException("sign line exceeds " + MAX_SIGN_LINE_LENGTH + " characters");
					}
				}
			}
			case EDIT_BOOK -> {
				if (lines.size() > MAX_BOOK_PAGES) throw new IllegalArgumentException("book exceeds " + MAX_BOOK_PAGES + " pages");
				for (String page : lines) {
					if (page.length() > MAX_BOOK_PAGE_LENGTH) {
						throw new IllegalArgumentException("book page exceeds " + MAX_BOOK_PAGE_LENGTH + " characters");
					}
				}
				if (title.filter(text -> text.length() > MAX_BOOK_TITLE_LENGTH).isPresent()) {
					throw new IllegalArgumentException("book title exceeds " + MAX_BOOK_TITLE_LENGTH + " characters");
				}
			}
		}
	}

	public static OperatorTextPayload rename(long sessionId, int sequence, String name) {
		return new OperatorTextPayload(sessionId, sequence, Kind.RENAME_ITEM, BlockPos.ZERO, 0, List.of(name), Optional.empty());
	}

	public static OperatorTextPayload sign(long sessionId, int sequence, BlockPos pos, boolean front, List<String> lines) {
		return new OperatorTextPayload(sessionId, sequence, Kind.SIGN_UPDATE, pos, front ? 1 : 0, lines, Optional.empty());
	}

	public static OperatorTextPayload book(long sessionId, int sequence, int slot, List<String> pages, Optional<String> title) {
		return new OperatorTextPayload(sessionId, sequence, Kind.EDIT_BOOK, BlockPos.ZERO, slot, pages, title);
	}

	@Override
	public Type<OperatorTextPayload> type() {
		return TYPE;
	}
}
