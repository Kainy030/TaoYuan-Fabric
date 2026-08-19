package com.taoyuan.item.custom;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import com.taoyuan.TaoYuanMod;
import com.taoyuan.attachment.TaoYuanAttachments;

/**
 * 终焉之花 (TaoYuan Flower).
 *
 * <p>Display behaviour ({@link BloodTitleItem#getName}) lives on the parent so the
 * blood-red title still shows. This subclass adds the debug interaction: right click
 * bumps the calamity counter by one, Shift+right click pulls it back by one. The
 * interaction only exists in the development build; remove it once 招灾 has a real
 * progression.
 */
public class TaoYuanFlowerItem extends BloodTitleItem {
	public TaoYuanFlowerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player user, InteractionHand hand) {
		if (user.isShiftKeyDown()) {
			adjustCounter(user, -1);
		} else {
			adjustCounter(user, +1);
		}

		return InteractionResult.SUCCESS_SERVER;
	}

	/**
	 * Bumps the affliction counter and tells the user where it landed.
	 *
	 * <p>The counter is clamped at zero so accidental Shift+right clicks cannot drive it
	 * negative and confuse downstream logic.
	 */
	private static void adjustCounter(Player user, int delta) {
		int current = user.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNT);
		int next = Math.max(0, current + delta);

		user.setAttached(TaoYuanAttachments.CALAMITY_COUNT, next);

		Component message = Component.translatable("message.taoyuan.calamity_counter_changed", current, next)
				.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);

		user.displayClientMessage(message, true);
		TaoYuanMod.LOGGER.debug("{} adjusted calamity counter {} -> {}", user.getName().getString(), current, next);
	}
}
