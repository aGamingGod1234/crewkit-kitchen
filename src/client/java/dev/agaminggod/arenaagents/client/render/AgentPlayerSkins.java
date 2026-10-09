package dev.agaminggod.arenaagents.client.render;

import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.client.control.AgentClientRoster;
import dev.agaminggod.arenaagents.client.control.DirectorClientState;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** One verified skin source for player bodies and profile-based UI such as Facebar. */
public final class AgentPlayerSkins {
	private AgentPlayerSkins() {
	}

	public static Optional<PlayerSkin> forProfile(GameProfile profile) {
		String name = profile.name();
		Optional<PlayerSkin> chef = crewkitChef(name);
		if (chef.isPresent()) return chef;
		AgentControlAgent agent = AgentClientRoster.agentForPlayer(name).orElse(null);
		AgentVisualIdentity.Resolved identity;
		if (agent != null) {
			if (!profile.id().equals(AgentIdentity.offlinePlayerUuid(agent.playerName()))) return Optional.empty();
			identity = AgentVisualIdentity.resolve(agent.provider(), agent.model(), agent.skinVariant());
		} else {
			var actor = DirectorClientState.snapshot().stream().flatMap(value -> value.actors().stream())
					.filter(value -> value.playerName().equals(name)
							&& profile.id().equals(AgentIdentity.offlinePlayerUuid(value.playerName())))
					.findFirst();
			if (actor.isPresent()) {
				String appearance = actor.orElseThrow().appearance();
				identity = appearance.equals("claude")
						? AgentVisualIdentity.resolve("gemini", "claude-sonnet-4-6", 0)
						: AgentVisualIdentity.resolveProviderFallback(appearance, 0);
			} else {
				// Old transport names can identify a body before its first roster arrives.
				AgentIdentity.SkinIdentity legacy = AgentIdentity.skinForPlayerName(name).orElse(null);
				if (legacy == null || !profile.id().equals(AgentIdentity.offlinePlayerUuid(name))) return Optional.empty();
				identity = legacy.modelFamily().isBlank()
						? AgentVisualIdentity.resolveProviderFallback(legacy.provider(), legacy.variant())
						: AgentVisualIdentity.resolveFamily(legacy.provider(), legacy.modelFamily(), legacy.variant());
			}
		}
		Identifier texture = CodexAgentRenderer.textureFor(identity);
		ClientAsset.Texture body = new ClientAsset.ResourceTexture(texture, texture);
		return Optional.of(new PlayerSkin(body, null, null, PlayerModelType.WIDE, false));
	}

	/**
	 * CrewKit Kitchen: whoever the server puts on the "crewkit_chef" scoreboard team wears the chef skin.
	 * Team membership is synced to every client, so this works on dedicated and integrated servers alike,
	 * and it is resolved per call so assigning the chef mid-session re-skins the body at once.
	 */
	private static Optional<PlayerSkin> crewkitChef(String name) {
		var level = Minecraft.getInstance().level;
		if (level == null || name == null) return Optional.empty();
		var team = level.getScoreboard().getPlayersTeam(name);
		if (team == null || !team.getName().equals(AgentVisualIdentity.CREWKIT_CHEF_SKIN)) return Optional.empty();
		return AgentVisualIdentity.specialTexturePath(AgentVisualIdentity.CREWKIT_CHEF_SKIN).map(path -> {
			Identifier texture = Identifier.parse(path);
			return new PlayerSkin(new ClientAsset.ResourceTexture(texture, texture), null, null, PlayerModelType.WIDE, false);
		});
	}
}
