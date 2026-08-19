package com.taoyuan.item.custom;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An item whose name is rendered in bold dark red.
 *
 * <p>Setting {@code DataComponents.ITEM_NAME} through {@link Item.Properties} does not
 * work for this: the {@link Item} constructor calls
 * {@code buildAndValidateComponents(Component.translatable(descriptionId), ...)}, which
 * unconditionally {@code set}s {@code ITEM_NAME} to an unstyled component and therefore
 * discards any style configured on the properties (see {@code Item.java:132} and
 * {@code Item.java:628} in 1.21.11).
 *
 * <p>Overriding {@link #getName(ItemStack)} is the reliable route, because
 * {@code ItemStack#getItemName} delegates straight to it.
 */
public class BloodTitleItem extends Item {
	public BloodTitleItem(Properties properties) {
		super(properties);
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.translatable(this.descriptionId)
				.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);
	}
}
