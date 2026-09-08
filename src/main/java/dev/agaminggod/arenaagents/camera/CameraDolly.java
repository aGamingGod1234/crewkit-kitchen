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

/** Physical tripod camera registration, placement, and server-authoritative controls. */
public final class CameraDolly {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("arenaagents", "camera_dolly");
    public static Block CAMERA;
    public static Item ITEM;
    private static boolean registered;

    public static Block HEAD, TRIPOD, COLUMN;
    public static EntityType<CameraRig> RIG;
    private static Block modelBlock(String name) {
        var id = Identifier.fromNamespaceAndPath("arenaagents", name);
        return Registry.register(BuiltInRegistries.BLOCK, id, new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id)).strength(2).noOcclusion()));
    }
    private static void registerAssets() {
        CAMERA = modelBlock("camera_dolly");
        HEAD = modelBlock("camera_head"); TRIPOD = modelBlock("camera_tripod"); COLUMN = modelBlock("camera_column");
        var rigId = Identifier.fromNamespaceAndPath("arenaagents", "camera_rig");
        RIG = Registry.register(BuiltInRegistries.ENTITY_TYPE, rigId,
            EntityType.Builder.<CameraRig>of(CameraRig::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.9f, 2.7f).clientTrackingRange(10).updateInterval(1).build(ResourceKey.create(Registries.ENTITY_TYPE, rigId)));
        ITEM = Registry.register(BuiltInRegistries.ITEM, ID,
            new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).stacksTo(1)) {
                @Override public InteractionResult useOn(UseOnContext context) {
                    if (context.getClickedFace() != net.minecraft.core.Direction.UP) return InteractionResult.FAIL;
                    var level = context.getLevel();
                    var hit = context.getClickLocation();
                    var camera = new CameraRig(RIG, level);
                    camera.setPos(hit.x, hit.y + 0.01, hit.z);
                    camera.aim(context.getRotation(), 0);
                    if (!level.noCollision(camera)) return InteractionResult.FAIL;
                    if (!level.isClientSide()) {
                        if (!level.addFreshEntity(camera)) return InteractionResult.FAIL;
                        context.getItemInHand().consume(1, context.getPlayer());
                    }
                    return InteractionResult.SUCCESS;
                }
            });
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("arenaagents", "cameras"),
            net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder()
                .title(Component.literal("Cameras")).icon(() -> new ItemStack(ITEM))
                .displayItems((parameters, output) -> output.accept(ITEM)).build());
    }

    private CameraDolly() {}
    public static boolean isCamera(Entity entity) {
        return entity instanceof CameraRig || CAMERA != null && entity instanceof AbstractMinecart cart && cart.getDisplayBlockState().is(CAMERA);
    }
    public static synchronized void register() {
        if (registered) return;
        registerAssets();
        registered = true;
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() || !isCamera(entity)) return InteractionResult.PASS;
            if (!player.mayBuild()) return InteractionResult.FAIL;
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
                    var stack = new ItemStack(ITEM);
                    if (!player.getInventory().add(stack)) player.drop(stack, false);
                    context.getSource().sendSuccess(() -> Component.literal("Camera ready. Place it on the floor, then right-click to open its controls."), false);
                    return 1;
                }))
                .then(rigControls())
                .then(movementCommand("roll", false))
                .then(movementCommand("stop", true));
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> rigControls() {
        return Commands.literal("rig").then(Commands.argument("camera", net.minecraft.commands.arguments.UuidArgument.uuid())
            .then(Commands.argument("operation", com.mojang.brigadier.arguments.StringArgumentType.word())
            .then(Commands.argument("value", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(-360, 360))
            .executes(context -> {
                var source = context.getSource(); var player = source.getPlayerOrException();
                var entity = source.getLevel().getEntity(net.minecraft.commands.arguments.UuidArgument.getUuid(context, "camera"));
                if (!(entity instanceof CameraRig rig) || rig.isRemoved() || rig.distanceToSqr(player) > 128 * 128) {
                    source.sendFailure(Component.literal("Camera is out of reach.")); return 0;
                }
                float value = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(context, "value");
                if (!Float.isFinite(value)) { source.sendFailure(Component.literal("Use a finite camera adjustment.")); return 0; }
                switch (com.mojang.brigadier.arguments.StringArgumentType.getString(context, "operation")) {
                    case "height" -> rig.lensHeight(rig.lensHeight() + value);
                    case "pan" -> rig.aim(rig.getYRot() + value, rig.getXRot());
                    case "tilt" -> rig.aim(rig.getYRot(), rig.getXRot() + value);
                    case "yaw" -> rig.aim(value, rig.getXRot());
                    case "pitch" -> rig.aim(rig.getYRot(), value);
                    case "drive" -> rig.drive(rig.getYRot() + value, 0.08f);
                    case "stop" -> rig.drive(0, 0);
                    default -> { source.sendFailure(Component.literal("Unknown camera control.")); return 0; }
                }
                return 1;
            }))));
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
        CameraRig rig = selected == null ? player.level().getEntitiesOfClass(CameraRig.class, player.getBoundingBox().inflate(32)).stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null)
            : source.getLevel().getEntity(selected) instanceof CameraRig found ? found : null;
        if (rig != null && rig.distanceToSqr(player) <= 128 * 128) { rig.drive(yaw, stop ? 0 : 0.08f); return 1; }
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
