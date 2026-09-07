package dev.agaminggod.arenaagents.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerInvoker {
	@Invoker("restartClientLoadTimerAfterRespawn")
	void arenaagents$restartClientLoadTimerAfterRespawn();
}
