package com.taoyuan.item;

import java.util.List;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

import com.taoyuan.TaoYuanMod;
import com.taoyuan.item.custom.TaoYuanFlowerItem;

/**
 * Items added by this mod.
 */
public final class TaoYuanItems {
	/**
	 * 终焉之花 (TaoYuan Flower).
	 *
	 * <p>A plain placeholder item with no behaviour or interactions. It exists so the mod
	 * has something to register, and so the creative tab actually shows up: vanilla hides
	 * a {@code Type.CATEGORY} tab while it is empty (see
	 * {@code CreativeModeTab#shouldDisplay}).
	 *
	 * <p>The bold dark red name comes from {@link BloodTitleItem}, which overrides
	 * {@code getName}. Styling it via the {@code ITEM_NAME} component does not survive
	 * item construction -- see that class for the details.
	 */
	public static final Item TAOYUAN_FLOWER = register(
			"taoyuan_flower",
			TaoYuanFlowerItem::new,
			new Item.Properties()
					.component(
							DataComponents.LORE,
							// ItemLore's single-argument constructor merges a default
							// dark-purple italic style, but Style#applyTo keeps values that
							// are already set, so the explicit colour below wins.
							new ItemLore(List.of(
									Component.translatable("item.taoyuan.taoyuan_flower.lore")
											.withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC)
							))
					)
	);

	private TaoYuanItems() {
	}

	/**
	 * Creates an item, registers it under this mod's namespace and returns it.
	 *
	 * @param name        the item's path, e.g. {@code "taoyuan_flower"}
	 * @param itemFactory constructs the item from its properties, usually {@code Item::new}
	 * @param properties  the item's properties; its id is filled in here
	 */
	public static <T extends Item> T register(String name, Function<Item.Properties, T> itemFactory, Item.Properties properties) {
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, TaoYuanMod.id(name));

		// 1.21.11 requires the id to be set on the properties before construction.
		T item = itemFactory.apply(properties.setId(itemKey));

		return Registry.register(BuiltInRegistries.ITEM, itemKey, item);
	}

	/**
	 * Triggers class loading (thus registration) and adds the items to the mod's creative
	 * tab. Called from the mod initializer.
	 */
	public static void initialize() {
		ItemGroupEvents.modifyEntriesEvent(TaoYuanItemGroups.TAOYUAN_GROUP_KEY)
				.register(entries -> entries.accept(TAOYUAN_FLOWER));

		TaoYuanMod.LOGGER.info("Registered {} item(s)", 1);
	}
}
