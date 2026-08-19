package com.taoyuan.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import com.taoyuan.TaoYuanMod;
import com.taoyuan.attachment.TaoYuanAttachments;
import com.taoyuan.effect.CalamityBringerEffect;
import com.taoyuan.effect.TaoYuanEffects;

/**
 * Debug read-out in the top right corner: time until the next calamity, and how many times
 * the player has been afflicted in total.
 *
 * <p>This is a development aid, so it is drawn unconditionally rather than only while the
 * effect is active. Remove the registration in {@code TaoYuanModClient} to hide it.
 */
public final class CalamityIndicator {
	private static final int MARGIN = 4;

	/** Vertical gap between the two lines; the vanilla font is 9px tall. */
	private static final int LINE_HEIGHT = 10;

	/** Blood red, matching the effect's presentation elsewhere in the mod. */
	private static final int TEXT_COLOUR = 0xFFAA0000;

	private CalamityIndicator() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				TaoYuanMod.id("calamity_indicator"),
				CalamityIndicator::render
		);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;

		if (player == null) {
			return;
		}

		drawRightAligned(graphics, client, buildIndicatorLine(player), MARGIN);
		drawRightAligned(graphics, client, buildCounterLine(player), MARGIN + LINE_HEIGHT);
	}

	/**
	 * Line one: seconds until the next wave, or why no countdown is running.
	 */
	private static Component buildIndicatorLine(Player player) {
		// Past the threshold the server shuts the wave scheduler down entirely, so this is a
		// distinct state from "idle" and says so.
		if (CalamityBringerEffect.isLateStage(player)) {
			return Component.translatable("hud.taoyuan.calamity_indicator.disabled")
					.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);
		}

		boolean afflicted = player.hasEffect(TaoYuanEffects.CALAMITY_BRINGER);
		boolean peaceful = player.level().getDifficulty() == Difficulty.PEACEFUL;

		// Without the effect, or on Peaceful where the server parks the countdown, there is
		// no meaningful number to show.
		if (!afflicted || peaceful) {
			return Component.translatable("hud.taoyuan.calamity_indicator.idle")
					.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);
		}

		// Counted down and synced by the server; no client-side schedule guessing.
		int countdown = player.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNTDOWN);

		// A zero means the server has not armed the countdown yet, which happens for a tick
		// or so right after loading in.
		if (countdown <= 0) {
			countdown = CalamityBringerEffect.CALAMITY_INTERVAL_TICKS;
		}

		// Round up so the display reaches "1" rather than sitting on "0" for a full second.
		int seconds = (countdown + 19) / 20;

		return Component.translatable("hud.taoyuan.calamity_indicator", seconds)
				.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);
	}

	/**
	 * Line two: how many times this player has ever been afflicted.
	 */
	private static Component buildCounterLine(Player player) {
		int count = player.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNT);

		return Component.translatable("hud.taoyuan.calamity_counter", count)
				.withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_RED);
	}

	private static void drawRightAligned(GuiGraphics graphics, Minecraft client, Component text, int y) {
		int x = graphics.guiWidth() - client.font.width(text) - MARGIN;
		graphics.drawString(client.font, text, x, y, TEXT_COLOUR);
	}
}
