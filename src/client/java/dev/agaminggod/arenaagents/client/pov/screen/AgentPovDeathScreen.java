package dev.agaminggod.arenaagents.client.pov.screen;

import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.PovDeath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-looking death screen for the viewed agent; it closes when a state arrives without a death. */
public final class AgentPovDeathScreen extends Screen {
	private static final int BUTTON_DELAY_TICKS = 20;
	private static final int RETRY_TICKS = 60;
	private static final Component WAITING = Component.literal("Waiting for respawn");
	private Component message;
	private boolean canRespawn;
	private boolean takeover;
	private int ticks;
	private int respawnCooldown;
	private int leaveCooldown;
	private Button respawnButton;
	private Button leaveButton;

	AgentPovDeathScreen(String agentName, PovDeath death, boolean takeover) {
		super(Component.literal((agentName == null || agentName.isBlank() ? "Agent" : agentName) + " died"));
		this.message = death.message();
		this.canRespawn = death.canRespawn();
		this.takeover = takeover;
	}

	void update(PovDeath death, boolean takeover) {
		boolean layoutChanged = canRespawn != death.canRespawn() || this.takeover != takeover;
		message = death.message();
		canRespawn = death.canRespawn();
		this.takeover = takeover;
		if (layoutChanged) rebuildWidgets();
	}

	@Override
	protected void init() {
		int x = width / 2 - 100;
		int y = height / 4 + 72;
		respawnButton = null;
		if (showsRespawn()) {
			respawnButton = addRenderableWidget(Button.builder(Component.translatable("deathScreen.respawn"), button -> respawn())
					.bounds(x, y, 200, 20).build());
			y += 24;
		}
		leaveButton = addRenderableWidget(Button.builder(Component.literal("Leave"), button -> leave())
				.bounds(x, y, 200, 20).build());
		updateButtons();
	}

	@Override
	public void tick() {
		super.tick();
		ticks++;
		if (respawnCooldown > 0) respawnCooldown--;
		if (leaveCooldown > 0) leaveCooldown--;
		updateButtons();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fillGradient(0, 0, width, height, 0x60500000, 0xA0803030);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.pose().pushMatrix();
		graphics.pose().scale(2.0F, 2.0F);
		graphics.centeredText(font, title, width / 4, 30, 0xFFFFFFFF);
		graphics.pose().popMatrix();
		if (message != null) graphics.centeredText(font, message, width / 2, 85, 0xFFFFFFFF);
		if (!showsRespawn()) graphics.centeredText(font, WAITING, width / 2, 100, 0xFFA0A0A0);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private boolean showsRespawn() {
		return takeover && canRespawn;
	}

	private void respawn() {
		// Routed through the server's verified respawn path, never a raw respawn packet for the agent.
		OperatorInputSender.sendAction(OperatorAction.RESPAWN, 0, 0, 0);
		respawnCooldown = RETRY_TICKS;
		updateButtons();
	}

	private void leave() {
		leaveCooldown = RETRY_TICKS;
		PovClient.requestExit();
		updateButtons();
	}

	private void updateButtons() {
		boolean ready = ticks >= BUTTON_DELAY_TICKS;
		if (respawnButton != null) respawnButton.active = ready && respawnCooldown == 0;
		if (leaveButton != null) leaveButton.active = ready && leaveCooldown == 0;
	}
}
