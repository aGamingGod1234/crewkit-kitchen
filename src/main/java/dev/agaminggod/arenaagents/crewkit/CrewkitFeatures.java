package dev.agaminggod.arenaagents.crewkit;

import java.util.List;

/**
 * Registry of kitchen features, built once so each feature keeps its state.
 * Order matters: the director stays last so it reacts after the scene has updated.
 */
public final class CrewkitFeatures {
	private CrewkitFeatures() {}

	private static volatile List<CrewkitFeature> features;

	public static List<CrewkitFeature> all() {
		List<CrewkitFeature> local = features;
		if (local == null) {
			synchronized (CrewkitFeatures.class) {
				if (features == null) {
					features = List.of(new CrewkitFeature[] {
						new dev.agaminggod.arenaagents.crewkit.core.BoardsFeature(),
						new dev.agaminggod.arenaagents.crewkit.items.ItemsFeature(),
						new dev.agaminggod.arenaagents.crewkit.flow.FlowFeature(),
						new dev.agaminggod.arenaagents.crewkit.flow.ReceiptFeature(),
						new dev.agaminggod.arenaagents.crewkit.cast.CastFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.soldout.SoldOutFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.guests.GuestChatterFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.party.CelebrationFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.pigeons.ReapPigeonFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.busy.KitchenRushFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.ticker.QuoteTickerFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.chaos.KitchenChaosFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.music.LobbyMusicFeature(),
						new dev.agaminggod.arenaagents.crewkit.fun.printer.ReceiptPrinterFeature(),
						new dev.agaminggod.arenaagents.crewkit.director.DirectorFeature(),
					});
				}
				local = features;
			}
		}
		return local;
	}
}
