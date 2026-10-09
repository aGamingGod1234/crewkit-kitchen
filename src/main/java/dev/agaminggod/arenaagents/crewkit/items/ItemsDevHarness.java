package dev.agaminggod.arenaagents.crewkit.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agaminggod.arenaagents.server.GoalControl;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Rehearsal harness for the head stack: /ckitems demo|add|remove|candidates|complete|reset feeds synthetic
 * contract events straight into ItemsFeature, and a server tick hook keeps it animating even before the
 * crewkit dispatcher exists (ItemsFeature de-duplicates ticks when both drive it).
 */
public final class ItemsDevHarness {
	private ItemsDevHarness() {}

	private static final AtomicBoolean INSTALLED = new AtomicBoolean();
	private static long seq;

	public static void install() {
		if (!INSTALLED.compareAndSet(false, true)) return;
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ItemsFeature feature = ItemsFeature.current();
			if (feature != null) feature.tick(server);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("ckitems").requires(GoalControl::mayControl)
						.then(Commands.literal("demo").executes(ItemsDevHarness::demo))
						.then(Commands.literal("add")
								.then(Commands.argument("mcItem", StringArgumentType.word())
										.then(Commands.argument("qty", IntegerArgumentType.integer(1, 99))
												.then(Commands.argument("realName", StringArgumentType.greedyString())
														.executes(ctx -> add(ctx, "dev-" + (++seq),
																StringArgumentType.getString(ctx, "realName"),
																StringArgumentType.getString(ctx, "mcItem"),
																IntegerArgumentType.getInteger(ctx, "qty"), 12.5, "Dev Merchant"))))))
						.then(Commands.literal("remove")
								.then(Commands.argument("id", StringArgumentType.word())
										.executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "id"), -1))
										.then(Commands.argument("qtyRemoved", IntegerArgumentType.integer(1, 99))
												.executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "id"), IntegerArgumentType.getInteger(ctx, "qtyRemoved"))))))
						.then(Commands.literal("candidates").executes(ItemsDevHarness::candidates))
						.then(Commands.literal("complete").executes(ctx -> {
							send(ctx, "completed", new JsonObject());
							ctx.getSource().sendSuccess(() -> Component.literal("consumeStack(): " + ItemsFeature.consumeStack()), false);
							return 1;
						}))
						.then(Commands.literal("status").executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.literal("stack: " + ItemsFeature.instance().snapshot()), false);
							return 1;
						}))
						.then(Commands.literal("reset").executes(ctx -> send(ctx, "reset", new JsonObject())))));
	}

	private static int send(CommandContext<CommandSourceStack> ctx, String event, JsonObject data) {
		ItemsFeature.instance().onEvent(ctx.getSource().getServer(), event, data, ++seq);
		return 1;
	}

	private static int add(CommandContext<CommandSourceStack> ctx, String id, String name, String mcItem, int qty, double price, String merchant) {
		JsonObject d = new JsonObject();
		d.addProperty("id", id);
		d.addProperty("realName", name);
		d.addProperty("merchant", merchant);
		d.addProperty("mcItem", mcItem);
		JsonObject p = new JsonObject();
		p.addProperty("amount", price);
		p.addProperty("currency", "SGD");
		d.add("unitPrice", p);
		d.addProperty("qty", qty);
		JsonArray seats = new JsonArray();
		seats.add("Ana");
		d.add("seats", seats);
		return send(ctx, "item_added", d);
	}

	private static int remove(CommandContext<CommandSourceStack> ctx, String id, int qtyRemoved) {
		JsonObject d = new JsonObject();
		d.addProperty("id", id);
		if (qtyRemoved > 0) d.addProperty("qtyRemoved", qtyRemoved);
		return send(ctx, "item_removed", d);
	}

	private static int demo(CommandContext<CommandSourceStack> ctx) {
		add(ctx, "nb", "Moleskine Classic Notebook, Large, Ruled", "minecraft:writable_book", 6, 28.9, "Kinokuniya SG");
		add(ctx, "pen", "Pilot G-2 Gel Pen 0.7mm (12-pack)", "minecraft:feather", 1, 19.5, "Popular Bookstore");
		add(ctx, "hdmi", "UGREEN 4K HDMI Cable 2m", "minecraft:lead", 6, 12.9, "Challenger");
		add(ctx, "water", "Pokka Mineral Water 500ml (24)", "minecraft:potion", 1, 11.2, "FairPrice");
		add(ctx, "cookie", "Famous Amos Chocolate Chip Cookies", "minecraft:cookie", 3, 6.5, "FairPrice");
		ctx.getSource().sendSuccess(() -> Component.literal("Queued 5 items (ids: nb pen hdmi water cookie). Try /ckitems remove hdmi"), false);
		return 1;
	}

	private static int candidates(CommandContext<CommandSourceStack> ctx) {
		JsonObject d = new JsonObject();
		d.addProperty("query", "hdmi cable");
		JsonArray options = new JsonArray();
		String[][] rows = {
				{"UGREEN 4K HDMI Cable 2m", "minecraft:lead", "12.90"},
				{"Belkin Ultra HD HDMI 2.1", "minecraft:lead", "39.00"},
				{"Generic HDMI Cable 1.5m", "minecraft:lead", "4.50"},
				{"Anker USB-C to HDMI Adapter", "minecraft:tripwire_hook", "24.90"},
		};
		for (String[] r : rows) {
			JsonObject o = new JsonObject();
			o.addProperty("realName", r[0]);
			o.addProperty("mcItem", r[1]);
			JsonObject p = new JsonObject();
			p.addProperty("amount", Double.parseDouble(r[2]));
			p.addProperty("currency", "SGD");
			o.add("price", p);
			options.add(o);
		}
		d.add("options", options);
		d.addProperty("chosenIndex", 0);
		return send(ctx, "candidates", d);
	}
}
