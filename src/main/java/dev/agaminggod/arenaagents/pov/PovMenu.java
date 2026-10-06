package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.inventory.MenuType;

/** Descriptor of the agent's open screen. An empty {@code menuType} means the player inventory screen. */
public record PovMenu(int containerId, int stateId, Optional<MenuType<?>> menuType, Component title) {
	public static final int MAX_TITLE_LENGTH = 256;
	public static final StreamCodec<RegistryFriendlyByteBuf, PovMenu> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PovMenu::containerId,
			ByteBufCodecs.VAR_INT, PovMenu::stateId,
			ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.MENU)), PovMenu::menuType,
			ComponentSerialization.TRUSTED_STREAM_CODEC, PovMenu::title,
			PovMenu::new
	);

	public PovMenu {
		if (containerId < 0) throw new IllegalArgumentException("containerId must not be negative");
		if (stateId < 0) throw new IllegalArgumentException("stateId must not be negative");
		menuType = Objects.requireNonNull(menuType, "menuType must not be null");
		title = Objects.requireNonNull(title, "title must not be null");
		if (title.getString().length() > MAX_TITLE_LENGTH) {
			throw new IllegalArgumentException("title exceeds " + MAX_TITLE_LENGTH + " characters");
		}
	}
}
