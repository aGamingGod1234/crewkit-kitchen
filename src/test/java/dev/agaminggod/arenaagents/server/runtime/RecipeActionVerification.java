package dev.agaminggod.arenaagents.server.runtime;

import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.Recipe;

public final class RecipeActionVerification {
	private RecipeActionVerification() {
	}

	public static int verify() {
		assertEquals("minecraft:stick", AdvancedInteractionService.canonicalRecipeId("minecraft:sticks"),
				"singularizes the vanilla stick recipe alias");
		assertEquals("minecraft:oak_planks", AdvancedInteractionService.canonicalRecipeId("minecraft:oak_planks"),
				"preserves canonical recipe IDs");

		ServerRecipeBook recipeBook = new ServerRecipeBook((key, displays) -> {
		});
		ResourceKey<Recipe<?>> recipeKey = ResourceKey.create(
				Registries.RECIPE,
				Identifier.parse("minecraft:wooden_pickaxe")
		);
		assertFalse(recipeBook.contains(recipeKey), "recipe starts locked");
		assertTrue(AdvancedInteractionService.ensureRecipeUnlocked(recipeBook, recipeKey),
				"unlocks a requested recipe");
		assertTrue(recipeBook.contains(recipeKey), "requested recipe is now available");
		assertFalse(AdvancedInteractionService.ensureRecipeUnlocked(recipeBook, recipeKey),
				"does not report a second unlock");
		return 6;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
