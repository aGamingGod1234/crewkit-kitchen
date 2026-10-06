package dev.agaminggod.arenaagents.mixin;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Brigadier has no removeChild; /spectate replaces the vanilla literal through these maps. */
@Mixin(value = CommandNode.class, remap = false)
public interface CommandNodeAccessor {
	@Accessor("children")
	Map<String, CommandNode<?>> arenaagents$children();

	@Accessor("literals")
	Map<String, LiteralCommandNode<?>> arenaagents$literals();

	@Accessor("arguments")
	Map<String, ArgumentCommandNode<?, ?>> arenaagents$arguments();
}
