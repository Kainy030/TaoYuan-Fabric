package com.taoyuan.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import com.taoyuan.TaoYuanMod;

/**
 * Creative mode tabs ("item groups") added by this mod.
 *
 * <p>The tab title is styled bold dark red via {@link ChatFormatting}; the visible text
 * itself comes from the {@code itemGroup.taoyuan} translation key, so it reads "TaoYuan"
 * in English and "桃源" in Simplified Chinese.
 */
public final class TaoYuanItemGroups {
	/**
	 * Registry key for the mod's main creative tab.
	 *
	 * <p>Kept separate from the tab instance because {@code ItemGroupEvents} is keyed by
	 * {@link ResourceKey}, which is how other classes will add their items to this tab.
	 */
	public static final ResourceKey<CreativeModeTab> TAOYUAN_GROUP_KEY = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB,
			TaoYuanMod.id("taoyuan")
	);

	/**
	 * The mod's main creative tab.
	 *
	 * <p>Note that a creative tab icon must be an {@link ItemStack} -- Minecraft renders a
	 * real in-game item here, so {@code assets/taoyuan/icon.png} cannot be used (that PNG is
	 * the mod's Mod Menu icon).
	 *
	 * <p>The icon is supplied lazily, so referencing {@link TaoYuanItems} here does not
	 * force it to load while this class is still being initialised.
	 */
	public static final CreativeModeTab TAOYUAN_GROUP = FabricItemGroup.builder()
			.title(Component.translatable("itemGroup.taoyuan")
					.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED))
			.icon(() -> new ItemStack(TaoYuanItems.TAOYUAN_FLOWER))
			.build();

	private TaoYuanItemGroups() {
	}

	/**
	 * Registers every creative tab declared here. Called from the mod initializer.
	 */
	public static void initialize() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAOYUAN_GROUP_KEY, TAOYUAN_GROUP);

		TaoYuanMod.LOGGER.info("Registered creative mode tab: {}", TAOYUAN_GROUP_KEY.identifier());
	}
}
