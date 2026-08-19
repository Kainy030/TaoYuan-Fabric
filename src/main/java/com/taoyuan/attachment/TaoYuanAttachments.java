package com.taoyuan.attachment;

import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import com.taoyuan.TaoYuanMod;

/**
 * Per-entity data added by this mod.
 */
public final class TaoYuanAttachments {
	/**
	 * How many times an entity has been afflicted by 招灾 (Calamity Bringer).
	 *
	 * <p>Incremented once per application of the effect, not per calamity wave. Persists
	 * across restarts and survives death, so it can later drive escalating consequences.
	 *
	 * <p>Synced to the owning player purely so the debug HUD can display it; nothing on the
	 * client depends on it otherwise.
	 */
	public static final AttachmentType<Integer> CALAMITY_COUNT = AttachmentRegistry.create(
			TaoYuanMod.id("calamity_count"),
			builder -> builder
					.initializer(() -> 0)
					.persistent(Codec.INT)
					.copyOnDeath()
					.syncWith(ByteBufCodecs.INT, AttachmentSyncPredicate.targetOnly())
	);

	/**
	 * Ticks remaining until the next calamity wave.
	 *
	 * <p>Counted down by the server so the cadence has a single authoritative source, and
	 * synced to the owning player so the HUD indicator can read it directly instead of
	 * trying to re-derive the schedule from the effect's duration.
	 *
	 * <p>Persistent, and deliberately so: the effect itself survives a reload, so a
	 * countdown that did not would reset to zero and fire a wave the instant the world
	 * loaded, dropping a pack of mobs on a player who has had no chance to react.
	 * {@code copyOnDeath} is intentionally absent -- respawning should start a fresh
	 * countdown rather than resume mid-cycle.
	 */
	public static final AttachmentType<Integer> CALAMITY_COUNTDOWN = AttachmentRegistry.create(
			TaoYuanMod.id("calamity_countdown"),
			builder -> builder
					.initializer(() -> 0)
					.persistent(Codec.INT)
					.syncWith(ByteBufCodecs.INT, AttachmentSyncPredicate.targetOnly())
	);

	/**
	 * Consecutive calamity waves that found nowhere to spawn.
	 *
	 * <p>Reset to zero as soon as a wave succeeds. Used to detect a victim who is walling
	 * themselves in: two failures in a row escalates them straight to the threshold, rather
	 * than letting them sit out the mechanic indefinitely.
	 *
	 * <p>Persistent so the escalation cannot be dodged by reloading between waves.
	 */
	public static final AttachmentType<Integer> CALAMITY_FAILED_WAVES = AttachmentRegistry.create(
			TaoYuanMod.id("calamity_failed_waves"),
			builder -> builder
					.initializer(() -> 0)
					.persistent(Codec.INT)
	);

	/**
	 * Lifetime tally of "good" kills a player has made (villager, iron golem).
	 *
	 * <p>Persistent so the seven-sword terror can judge the player's moral ledger across
	 * logins, deaths and reloads. Synced to the owning player for HUD/debug visibility.
	 */
	public static final AttachmentType<Integer> FRIENDLY_KILLS = AttachmentRegistry.create(
			TaoYuanMod.id("friendly_kills"),
			builder -> builder
					.initializer(() -> 0)
					.persistent(Codec.INT)
					.copyOnDeath()
					.syncWith(ByteBufCodecs.INT, AttachmentSyncPredicate.targetOnly())
	);

	private TaoYuanAttachments() {
	}

	/**
	 * Triggers class loading, and therefore registration. Called from the mod initializer.
	 */
	public static void initialize() {
		TaoYuanMod.LOGGER.info("Registered attachments under {}", TaoYuanMod.MOD_ID);
	}
}
