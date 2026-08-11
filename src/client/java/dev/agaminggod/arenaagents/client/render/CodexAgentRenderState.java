package dev.agaminggod.arenaagents.client.render;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

public final class CodexAgentRenderState extends HumanoidRenderState {
	private String provider = "codex";
	private int skinVariant;

	public String provider() {
		return provider;
	}

	void setProvider(String provider) {
		this.provider = provider;
	}

	public int skinVariant() {
		return skinVariant;
	}

	void setSkinVariant(int skinVariant) {
		this.skinVariant = Math.floorMod(skinVariant, CodexAgentRenderer.SKIN_VARIANT_COUNT);
	}
}
