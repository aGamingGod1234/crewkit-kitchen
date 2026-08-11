package dev.agaminggod.arenaagents.client.render;

import dev.agaminggod.arenaagents.agent.CodexAgentEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;

public final class CodexAgentRenderer extends HumanoidMobRenderer<
		CodexAgentEntity,
		CodexAgentRenderState,
		HumanoidModel<CodexAgentRenderState>
> {
	public static final int SKIN_VARIANT_COUNT = 4;

	private static final float SHADOW_RADIUS = 0.5F;
	private static final Identifier[] CODEX_TEXTURES = {
		texture("codex_agent_cyan.png"),
		texture("codex_agent_violet.png"),
		texture("codex_agent_emerald.png"),
		texture("codex_agent_amber.png")
	};
	private static final Identifier[] GEMINI_TEXTURES = {
		texture("gemini_agent_blue.png"),
		texture("gemini_agent_red.png"),
		texture("gemini_agent_yellow.png"),
		texture("gemini_agent_green.png")
	};
	private static final Identifier[] KIMI_TEXTURES = {
		texture("kimi_agent_moon.png"),
		texture("kimi_agent_ice.png"),
		texture("kimi_agent_orchid.png"),
		texture("kimi_agent_solar.png")
	};

	public CodexAgentRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), SHADOW_RADIUS);
	}

	@Override
	public CodexAgentRenderState createRenderState() {
		return new CodexAgentRenderState();
	}

	@Override
	public void extractRenderState(CodexAgentEntity entity, CodexAgentRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.setProvider(entity.getProvider());
		state.setSkinVariant(entity.getSkinVariant());
	}

	@Override
	public Identifier getTextureLocation(CodexAgentRenderState state) {
		return textureFor(state.provider(), state.skinVariant());
	}

	public static Identifier textureFor(String provider, int skinVariant) {
		Identifier[] textures = switch (provider) {
			case "gemini" -> GEMINI_TEXTURES;
			case "kimi" -> KIMI_TEXTURES;
			default -> CODEX_TEXTURES;
		};
		return textures[Math.floorMod(skinVariant, SKIN_VARIANT_COUNT)];
	}

	private static Identifier texture(String fileName) {
		return Identifier.fromNamespaceAndPath("arenaagents", "textures/entity/" + fileName);
	}
}
