package dev.agaminggod.arenaagents.server;

import java.util.*;
import dev.agaminggod.arenaagents.agent.AgentId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/** Real reservation and stop paths, without a running world or network. */
public final class DirectorTakeVerification {
	@SuppressWarnings("unchecked")
	public static int verify() {
		try {
			MinecraftServer server=allocate(net.minecraft.client.server.IntegratedServer.class);
			server.setPlayerList(allocate(EmptyPlayers.class));
			var field=DirectorTakeRuntime.class.getDeclaredField("RUNS");field.setAccessible(true);
			var runs=(Map<MinecraftServer,Map<UUID,DirectorTakeRuntime.Run>>)field.get(null);
			var mark=new SkitPlacement("minecraft:overworld",0,64,0,0,0);
			var a=AgentId.random();var b=AgentId.random();var c=AgentId.random();
			var firstTrack=new DirectorTake.Track(a,"intro","",mark);var secondTrack=new DirectorTake.Track(b,"intro","",mark);
			var script=new SkitScript("intro",a.toString(),List.of(new SkitStep(0,mark)));
			var take=new DirectorTake("Scene",List.of(firstTrack,secondTrack),"",0);
			var firstOwner=UUID.randomUUID();var secondOwner=UUID.randomUUID();
			var first=new DirectorTakeRuntime.Run(firstOwner,take,List.of(new DirectorTakeRuntime.Track(firstTrack,script,null),new DirectorTakeRuntime.Track(secondTrack,script,null)),60,"minecraft:overworld");
			var thirdTrack=new DirectorTake.Track(c,"intro","",mark);
			var second=new DirectorTakeRuntime.Run(secondOwner,new DirectorTake("Other",List.of(thirdTrack),"",0),List.of(new DirectorTakeRuntime.Track(thirdTrack,script,null)),60,"minecraft:overworld");
			runs.put(server,new HashMap<>(Map.of(firstOwner,first,secondOwner,second)));
			try {
				reject(() -> DirectorTakeRuntime.requireUnreserved(server,a),"cast is reserved during countdown");
				DirectorTakeRuntime.requireUnreserved(server,AgentId.random());
				first.started=true;
				reject(() -> DirectorTakeRuntime.requireUnreserved(server,b),"cast remains reserved throughout playback");
				DirectorTakeRuntime.stopActor(server,a);
				check(!runs.get(server).containsKey(firstOwner) && runs.get(server).containsKey(secondOwner),"stopping one actor cancels its whole take and preserves another operator's take");
				DirectorTakeRuntime.requireUnreserved(server,a);DirectorTakeRuntime.requireUnreserved(server,b);
				DirectorTakeRuntime.stopActor(server,a);
				check(runs.get(server).size()==1,"repeated stop is harmless");
				DirectorTakeRuntime.stopAll(server);
				check(!runs.containsKey(server),"disabling Director releases every pending or active take");
				DirectorTakeRuntime.requireUnreserved(server,c);
				return 5;
			} finally { DirectorTakeRuntime.stopAll(server); }
		} catch(ReflectiveOperationException error) {throw new AssertionError(error);}
	}
	private static final class EmptyPlayers extends PlayerList {
		private EmptyPlayers(){super(null,null,null,null);}
		@Override public ServerPlayer getPlayer(UUID id){return null;}
	}
	private static <T>T allocate(Class<T> type)throws ReflectiveOperationException {
		var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return type.cast(((sun.misc.Unsafe)f.get(null)).allocateInstance(type));
	}
	private static void reject(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
	private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
