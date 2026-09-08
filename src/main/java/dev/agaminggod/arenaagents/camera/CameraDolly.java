package dev.agaminggod.arenaagents.camera;

import java.util.Comparator;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.BaseRailBlock;
import dev.agaminggod.arenaagents.server.GoalControl;

/** A physical camera carried by a vanilla rail dolly; its display block is synchronized and saved. */
public final class CameraDolly {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("arenaagents", "camera_dolly");
    public static Block CAMERA;
    public static Item ITEM;
    private static boolean registered;

    private static void registerAssets() {
        CAMERA = Registry.register(BuiltInRegistries.BLOCK, ID,
            new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ID)).strength(2).noOcclusion()));
        ITEM = Registry.register(BuiltInRegistries.ITEM, ID,
            new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).stacksTo(1)) {
                @Override public InteractionResult useOn(UseOnContext context) {
                    var level = context.getLevel();
                    var pos = context.getClickedPos();
                    var state = level.getBlockState(pos);
                    if (!state.is(BlockTags.RAILS)) return InteractionResult.FAIL;
                    if (!level.isClientSide()) {
                        double height = state.getBlock() instanceof BaseRailBlock rail
                                && state.getValue(rail.getShapeProperty()).isSlope() ? 0.5 : 0;
                        var cart = AbstractMinecart.createMinecart(level, pos.getX() + 0.5, pos.getY() + 0.0625 + height,
                                pos.getZ() + 0.5, EntityType.MINECART, EntitySpawnReason.SPAWN_ITEM_USE,
                                context.getItemInHand(), context.getPlayer());
                        if (cart == null) return InteractionResult.FAIL;
                        cart.setCustomDisplayBlockState(Optional.of(CAMERA.defaultBlockState()));
                        cart.setDisplayOffset(8);
                        cart.setCustomName(Component.literal("Camera Dolly"));
                        if (!level.addFreshEntity(cart)) return InteractionResult.FAIL;
                        context.getItemInHand().consume(1, context.getPlayer());
                    }
                    return InteractionResult.SUCCESS;
                }
            });
    }

    private CameraDolly() {}
    public static boolean isCamera(Entity entity) {
        return CAMERA != null && entity instanceof AbstractMinecart cart && cart.getDisplayBlockState().is(CAMERA);
    }
    public static synchronized void register() {
        if (registered) return;
        registerAssets();
        registered = true;
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !isCamera(entity)) return InteractionResult.PASS;
            if (player.isShiftKeyDown() && !entity.isRemoved()) {
                entity.discard();
                if (!player.getAbilities().instabuild) {
                    var recovered = new ItemStack(ITEM);
                    if (!player.getInventory().add(recovered)) player.drop(recovered, false);
                }
            }
            return InteractionResult.SUCCESS;
        });
    }
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> commands() {
        return Commands.literal("camera").requires(GoalControl::mayControl)
                .then(Commands.literal("kit").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    for (var stack : new ItemStack[]{new ItemStack(ITEM), new ItemStack(Items.RAIL, 64),
                            new ItemStack(Items.POWERED_RAIL, 16), new ItemStack(Items.LEVER, 8)}) {
                        if (!player.getInventory().add(stack)) player.drop(stack, false);
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Camera kit ready. Lay rails, place the camera on them, then right-click its viewfinder."), false);
                    return 1;
                }))
                .then(movementCommand("roll", false))
                .then(movementCommand("stop", true));
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> movementCommand(String name, boolean stop) {
        return Commands.literal(name).executes(context -> move(context.getSource(), stop, null, context.getSource().getPlayerOrException().getYRot()))
            .then(Commands.argument("camera", net.minecraft.commands.arguments.UuidArgument.uuid())
                .then(Commands.argument("yaw", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-180, 180))
                    .executes(context -> move(context.getSource(), stop,
                        net.minecraft.commands.arguments.UuidArgument.getUuid(context, "camera"),
                        com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "yaw")))));
    }
    private static int move(net.minecraft.commands.CommandSourceStack source, boolean stop, java.util.UUID selected, float yaw)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        AbstractMinecart cart;
        if (selected == null) {
            cart = player.level().getEntitiesOfClass(AbstractMinecart.class, player.getBoundingBox().inflate(32), CameraDolly::isCamera)
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        } else {
            var entity = source.getLevel().getEntity(selected);
            cart = isCamera(entity) && !entity.isRemoved() && entity.distanceToSqr(player) <= 128 * 128 ? (AbstractMinecart) entity : null;
        }
        if (cart == null) { source.sendFailure(Component.literal(selected == null ? "No camera dolly within 32 blocks." : "The selected camera is no longer loaded or is more than 128 blocks away.")); return 0; }
        cart.setDeltaMovement(stop ? net.minecraft.world.phys.Vec3.ZERO : net.minecraft.world.phys.Vec3.directionFromRotation(0, yaw).scale(0.2));
        cart.hurtMarked = true;
        source.sendSuccess(() -> Component.literal(stop ? "Camera dolly braked. Turn off powered rails to keep it stopped." : "Camera dolly rolling in your facing direction. Powered rails keep it moving."), false);
        return 1;
    }
}
