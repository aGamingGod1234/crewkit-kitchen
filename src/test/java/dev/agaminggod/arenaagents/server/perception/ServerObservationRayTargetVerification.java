package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ServerObservationRayTargetVerification {
	private ServerObservationRayTargetVerification() {
	}

	public static int verify() {
		AtomicBoolean blockLookupCalled = new AtomicBoolean();
		BlockHitResult miss = BlockHitResult.miss(Vec3.ZERO, Direction.NORTH, BlockPos.ZERO);

		JsonObject rayTarget = ServerObservationCollector.rayTarget(miss, position -> {
			blockLookupCalled.set(true);
			return "minecraft:stone";
		});

		assertEquals("{\"type\":\"miss\"}", rayTarget.toString(), "miss ray target contains only its type");
		assertFalse(blockLookupCalled.get(), "miss ray target does not resolve a block");
		try {
			return 2 + LandmarkCoverageFixture.verify();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not execute sparse landmark fixture", exception);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertFalse(boolean condition, String label) {
		if (condition) throw new AssertionError(label);
	}

	// Constructor-free world/player fixtures execute vanilla clipping and the real collector.
	private static final class LandmarkCoverageFixture {
		static sun.misc.Unsafe unsafe;
		static Method rawMethod, sampleMethod;
		static int assertions;
		static int verify() throws Exception {
			assertions = 0;
			net.minecraft.SharedConstants.tryDetectVersion();
			net.minecraft.server.Bootstrap.bootStrap();
			Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true); unsafe = (sun.misc.Unsafe) f.get(null);
			verifyAirRaySkipEquivalence();
			rawMethod = ServerObservationCollector.class.getDeclaredMethod("rawSpatialObservation", ServerLevel.class, ServerPlayer.class, BlockPos.class);
			sampleMethod = ServerObservationCollector.class.getDeclaredMethod("visibleSurfaceCandidates", ServerLevel.class, ServerPlayer.class, BlockPos.class);
			rawMethod.setAccessible(true); sampleMethod.setAccessible(true);
			checkCase("vertical_gap", new BlockPos(2,60,0), false, true, false);
			checkCase("within_cube_control", new BlockPos(2,61,0), true, false, true);
			checkCase("outside_sphere_control", new BlockPos(6,60,0), false, true, false);
			checkCase("above_local_scan", new BlockPos(2,68,0), false, true, true);
			checkCase("local_corner_preserved", new BlockPos(6,64,6), true, true, false);
			return assertions;
		}

		static void verifyAirRaySkipEquivalence() throws Exception {
			SparseWorld world = new SparseWorld();
			FixtureLevel contextLevel = (FixtureLevel) unsafe.allocateInstance(FixtureLevel.class);
			contextLevel.world = world;
			contextLevel.loaded = true;
			FixturePlayer contextPlayer = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
			contextPlayer.fixtureLevel = contextLevel;
			set(contextPlayer, "position", new Vec3(0.5D, 64.0D, 0.5D));
			set(contextPlayer, "blockPosition", new BlockPos(0, 64, 0));
			set(contextPlayer, "eyeHeight", 1.62F);
			BlockState[] shapes = {
					Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
					Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(),
					Blocks.END_PORTAL.defaultBlockState(), Blocks.END_GATEWAY.defaultBlockState(),
					Blocks.TORCH.defaultBlockState(), Blocks.GLASS.defaultBlockState()
			};
			for (int index = 0; index < shapes.length; index++) {
				world.states.put(new BlockPos(0, 64, index - 4), shapes[index]);
			}
			Random random = new Random(0xA17C1A1L);
			for (int index = 0; index < 350; index++) {
				BlockPos position = new BlockPos(random.nextInt(49) - 24, random.nextInt(21) + 56,
						random.nextInt(49) - 24);
				world.states.put(position, shapes[random.nextInt(shapes.length)]);
			}
			int raysWithNonAir = 0;
			int raysWithoutNonAir = 0;
			for (int index = 0; index < 5_000; index++) {
				Vec3 start = new Vec3(random.nextDouble() * 48.0D - 24.0D,
						random.nextDouble() * 22.0D + 55.5D, random.nextDouble() * 48.0D - 24.0D);
				Vec3 end = new Vec3(random.nextDouble() * 48.0D - 24.0D,
						random.nextDouble() * 22.0D + 55.5D, random.nextDouble() * 48.0D - 24.0D);
				ClipContext context = new ClipContext(start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, contextPlayer);
				BlockHitResult vanilla = world.clip(context);
				boolean candidate = ServerObservationCollector.rayContainsNonAir(start, end, context,
						position -> !world.getBlockState(position).isAir());
				if (candidate) {
					raysWithNonAir++;
					assertSameBlockHit(vanilla, world.clip(context), "ray (" + index + ") with a non-air cell");
				} else {
					raysWithoutNonAir++;
					if (vanilla.getType() != HitResult.Type.MISS) {
						throw new AssertionError("air-only ray skipped a vanilla block hit at sample " + index);
					}
				}
			}
			if (raysWithNonAir < 500 || raysWithoutNonAir < 500) {
				throw new AssertionError("random rays did not cover both skip and clip paths: nonAir="
						+ raysWithNonAir + ", air=" + raysWithoutNonAir);
			}
		}

		static void assertSameBlockHit(BlockHitResult expected, BlockHitResult actual, String label) {
			assertEquals(expected.getType(), actual.getType(), label + " hit type");
			if (expected.getType() != HitResult.Type.BLOCK) return;
			assertEquals(expected.getBlockPos(), actual.getBlockPos(), label + " hit position");
			assertEquals(expected.getDirection(), actual.getDirection(), label + " hit face");
			assertEquals(expected.getLocation(), actual.getLocation(), label + " hit location");
		}
		static void checkCase(String name, BlockPos target, boolean expectRaw, boolean expectSample, boolean expectReach) throws Exception {
			SparseWorld world = new SparseWorld();
			world.states.put(new BlockPos(0,63,0), Blocks.STONE.defaultBlockState());
			world.states.put(target, Blocks.DIAMOND_ORE.defaultBlockState());
			FixtureLevel level = (FixtureLevel) unsafe.allocateInstance(FixtureLevel.class);
			level.world = world; level.loaded = true;
			FixturePlayer player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
			player.fixtureLevel = level;
			set(player, "position", new Vec3(.5,64,.5)); set(player, "blockPosition", new BlockPos(0,64,0));
			set(player, "eyeHeight", 1.62F); set(player,"xo",.5D); set(player,"yo",64D); set(player,"zo",.5D);
			Vec3 delta = Vec3.atCenterOf(target).subtract(player.getEyePosition());
			float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, Math.hypot(delta.x,delta.z)));
			float yaw = (float) Math.toDegrees(Math.atan2(-delta.x,delta.z));
			set(player, "xRot",pitch); set(player,"yRot",yaw); player.xRotO=pitch; player.yRotO=yaw; player.yHeadRot=yaw; player.yHeadRotO=yaw;
			Vec3 dir = Vec3.directionFromRotation(pitch,yaw);
			BlockHitResult fullHit = world.clip(new ClipContext(player.getEyePosition(),player.getEyePosition().add(dir.scale(256)),ClipContext.Block.VISUAL,ClipContext.Fluid.ANY,player));
			ok(fullHit.getType()==HitResult.Type.BLOCK && fullHit.getBlockPos().equals(target), name+" center ray hits target before support");
			ok(ObservationVisibility.canSeeBlock(level,player,target),name+" real view and line-of-sight admit target");
			RawSpatialObservation raw = (RawSpatialObservation)rawMethod.invoke(null,level,player,player.blockPosition());
			boolean rawPresent = raw.blocks().stream().anyMatch(c->player.blockPosition().offset(c.x(),c.y(),c.z()).equals(target));
			List<?> samples=(List<?>)sampleMethod.invoke(null,level,player,player.blockPosition());
			boolean samplePresent=contains(samples,target,player.blockPosition());
			ok(rawPresent==expectRaw,name+" local scan presence");
			ok(samplePresent==expectSample,name+" landmark presence");
			boolean inReach=player.isWithinBlockInteractionRange(target,0);
			ok(inReach==expectReach,name+" vanilla interaction AABB range");
			HitResult pick=player.pick(player.blockInteractionRange(),0.0F,false);
			boolean pickTarget=pick.getType()==HitResult.Type.BLOCK && ((BlockHitResult)pick).getBlockPos().equals(target);
			ok(pickTarget==expectReach,name+" vanilla interaction pick");
			var collector=(ServerObservationCollector)unsafe.allocateInstance(ServerObservationCollector.class);
			JsonObject query;
			Method emit=ServerObservationCollector.class.getDeclaredMethod("landmarks",ServerLevel.class,ServerPlayer.class,ObservationVisibility.Frame.class,List.class,int.class);
			emit.setAccessible(true);
			var entries=(com.google.gson.JsonArray)emit.invoke(null,level,player,ObservationVisibility.frame(level,player),samples,Integer.MAX_VALUE);
			boolean focusedPresent=entries.asList().stream().map(v->v.getAsJsonObject()).anyMatch(v->v.get("x").getAsInt()==target.getX() && v.get("y").getAsInt()==target.getY() && v.get("z").getAsInt()==target.getZ());
			ok(focusedPresent==expectSample,name+" production landmark emitter used by focused inspection");
			if (!expectReach) {
		query=new JsonObject();query.addProperty("section","block");query.addProperty("x",target.getX());query.addProperty("y",target.getY());query.addProperty("z",target.getZ());
		try { collector.collectInspection(player,query); throw new AssertionError(name+" focused block should reject range"); }
		catch(dev.agaminggod.arenaagents.agent.AgentDomainException e) {ok(e.code().equals("TARGET_OUT_OF_RANGE"),name+" focus block cannot recover");}
			}
			int before=level.clips; level.loaded=false;
			ok(((List<?>)sampleMethod.invoke(null,level,player,player.blockPosition())).isEmpty(),name+" unloaded control returns no samples");
			ok(level.clips==before,name+" unloaded control does not clip");
		}
		static boolean contains(List<?> samples,BlockPos target,BlockPos center)throws Exception {
			for(Object sample:samples){Class<?> type=sample.getClass(); Method x=type.getDeclaredMethod("x"),y=type.getDeclaredMethod("y"),z=type.getDeclaredMethod("z");x.setAccessible(true);y.setAccessible(true);z.setAccessible(true);if(center.offset((int)x.invoke(sample),(int)y.invoke(sample),(int)z.invoke(sample)).equals(target))return true;}return false;
		}
		static void set(Object target,String name,Object value)throws Exception {Field f=Entity.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
		static void ok(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
		static final class FixturePlayer extends ServerPlayer {
			FixtureLevel fixtureLevel;
			FixturePlayer(){super(null,null,null,null);}
			@Override public ServerLevel level(){return fixtureLevel;}
			@Override public boolean isDescending(){return false;}
			@Override public ItemStack getMainHandItem(){return ItemStack.EMPTY;}
			@Override public double blockInteractionRange(){return 4.5;}
		}
		static final class FixtureLevel extends ServerLevel {
			SparseWorld world; boolean loaded; int clips;
			FixtureLevel(){super(null,null,null,null,null,null,false,0L,List.of(),false);}
			@Override public long getGameTime(){return 0L;}
			@Override public boolean hasChunkAt(BlockPos p){return loaded;}
			@Override public BlockState getBlockState(BlockPos p){return world.getBlockState(p);}
			@Override public FluidState getFluidState(BlockPos p){return world.getFluidState(p);}
			@Override public BlockHitResult clip(ClipContext context){clips++; return world.clip(context);}
		}
		static final class SparseWorld implements BlockGetter {
			final Map<BlockPos,BlockState> states=new HashMap<>();
			@Override public BlockEntity getBlockEntity(BlockPos p){return null;}
			@Override public BlockState getBlockState(BlockPos p){return states.getOrDefault(p,Blocks.AIR.defaultBlockState());}
			@Override public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
			@Override public int getHeight(){return 384;}
			@Override public int getMinY(){return -64;}
		}
}







}
