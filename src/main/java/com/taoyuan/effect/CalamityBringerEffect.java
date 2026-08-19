package com.taoyuan.effect;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import com.taoyuan.TaoYuanMod;
import com.taoyuan.attachment.TaoYuanAttachments;

/**
 * 招灾 (Calamity Bringer).
 *
 * <p>Every 30 seconds the victim suffers a calamity: a pack of hostile mobs is summoned
 * around them, and for the following 10 seconds every nearby monster is forced to target
 * them.
 *
 * <p>The schedule is driven by an explicit countdown stored on the entity rather than by
 * arithmetic on the effect's remaining duration. Deriving it from the duration breaks for
 * infinite effects, where the duration is a constant {@code -1}.
 *
 * <p>The colour passed to {@code super} is Poison's ({@code 8889187}), which also drives
 * the ambient particles, because the two-argument {@link MobEffect} constructor derives
 * particles from the effect colour.
 */
public class CalamityBringerEffect extends MobEffect {
	/** How often a calamity strikes: 30 seconds at 20 ticks per second. */
	public static final int CALAMITY_INTERVAL_TICKS = 30 * 20;

	/** How long aggression stays riveted on the victim after a calamity: 10 seconds. */
	public static final int AGGRO_LOCK_TICKS = 10 * 20;

	/**
	 * Calamity counter at which the summoning mechanic gives way to the next stage.
	 *
	 * <p>Below this, waves summon mobs. At or above it, {@link #strikeLateStage} runs
	 * instead.
	 */
	public static final int LATE_STAGE_THRESHOLD = 10;

	/**
	 * Countdown value meaning "the wave scheduler is not running".
	 *
	 * <p>Distinct from a live countdown, which is always positive. Written once the counter
	 * reaches {@link #LATE_STAGE_THRESHOLD} and read by the HUD to tell "switched off"
	 * apart from "idle".
	 */
	public static final int INDICATOR_OFF = 0;

	/**
	 * Consecutive failed waves that force an early escalation.
	 *
	 * <p>Prevents a victim from opting out of the mechanic by sealing themselves into a
	 * one-block hole where nothing can ever spawn.
	 */
	private static final int FAILED_WAVES_BEFORE_ESCALATION = 2;

	/**
	 * Whether this entity has been afflicted often enough to leave the summoning stage.
	 */
	public static boolean isLateStage(LivingEntity entity) {
		return entity.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNT) >= LATE_STAGE_THRESHOLD;
	}

	public CalamityBringerEffect() {
		super(MobEffectCategory.HARMFUL, 8889187);
	}

	/**
	 * Runs the effect logic on every server tick rather than on an interval, because the
	 * aggression lock has to be re-asserted continuously.
	 */
	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return true;
	}

	@Override
	public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
		// Peaceful disables the calamity entirely: no summons, no aggression lock, no failure
		// tracking, and the countdown is parked rather than advanced so switching back to a
		// harmful difficulty grants a full grace period instead of an immediate wave. The
		// affliction counter is unaffected -- it is incremented in onEffectAdded.
		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			entity.setAttached(TaoYuanAttachments.CALAMITY_COUNTDOWN, CALAMITY_INTERVAL_TICKS);
			return true;
		}

		// Past the threshold the summoning stage is over and the wave scheduler is shut down
		// outright, not merely hidden: the countdown is cleared to zero so nothing is being
		// timed, and the zero is what tells the HUD the indicator is off rather than idle.
		//
		// Should the counter come back down -- the flower can lower it, and the planned
		// sanity item will too -- the branch below re-arms it with a full interval, so the
		// scheduler resumes with a fresh grace period instead of firing immediately.
		if (isLateStage(entity)) {
			if (entity.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNTDOWN) != INDICATOR_OFF) {
				entity.setAttached(TaoYuanAttachments.CALAMITY_COUNTDOWN, INDICATOR_OFF);

				TaoYuanMod.LOGGER.debug("Calamity indicator shut down for {} (counter at {})",
						entity.getName().getString(),
						entity.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNT));
			}

			return true;
		}

		int stored = entity.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNTDOWN);

		// A stored zero means the countdown is not running: either it was shut down by the
		// late stage and the counter has since come back down, this is a world saved before
		// the countdown became persistent, or the attachment was lost for some other reason.
		// Re-arm rather than striking, so neither a reload nor a counter change can drop a
		// wave on a player the instant it happens.
		if (stored <= 0) {
			entity.setAttached(TaoYuanAttachments.CALAMITY_COUNTDOWN, CALAMITY_INTERVAL_TICKS);
			return true;
		}

		int countdown = stored - 1;

		if (countdown <= 0) {
			strike(level, entity);
			countdown = CALAMITY_INTERVAL_TICKS;
		}

		entity.setAttached(TaoYuanAttachments.CALAMITY_COUNTDOWN, countdown);

		// Ticks elapsed since the most recent wave.
		int sinceWave = CALAMITY_INTERVAL_TICKS - countdown;

		if (sinceWave <= AGGRO_LOCK_TICKS) {
			Calamity.lockAggression(level, entity);
		}

		return true;
	}

	/**
	 * Counts this affliction.
	 *
	 * <p>Uses {@code onEffectAdded} rather than {@code onEffectStarted} because
	 * {@code LivingEntity#addEffect} calls the latter on every application, including when
	 * an existing effect is merely refreshed, whereas this one runs only when the effect was
	 * not already present.
	 */
	@Override
	public void onEffectAdded(LivingEntity entity, int amplifier) {
		super.onEffectAdded(entity, amplifier);

		int total = entity.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNT) + 1;
		entity.setAttached(TaoYuanAttachments.CALAMITY_COUNT, total);

		TaoYuanMod.LOGGER.debug("{} has now been afflicted by calamity {} time(s)",
				entity.getName().getString(), total);
	}

	/**
	 * Arms the countdown.
	 *
	 * <p>Runs on every application, refreshes included, so extending the effect also resets
	 * the grace period before the next wave.
	 *
	 * <p>Does nothing past the threshold: the scheduler is shut down there, and arming it
	 * would leave the indicator briefly reporting a countdown that {@code applyEffectTick}
	 * is about to clear again.
	 */
	@Override
	public void onEffectStarted(LivingEntity entity, int amplifier) {
		if (isLateStage(entity)) {
			entity.setAttached(TaoYuanAttachments.CALAMITY_COUNTDOWN, INDICATOR_OFF);
			return;
		}

		// Start a full interval so the first wave lands 30 seconds in rather than instantly.
		entity.setAttached(TaoYuanAttachments.CALAMITY_COUNTDOWN, CALAMITY_INTERVAL_TICKS);
	}

	/**
	 * Runs one calamity wave, choosing which mechanic applies from the victim's counter.
	 */
	private static void strike(ServerLevel level, LivingEntity victim) {
		int count = victim.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_COUNT);

		if (count >= LATE_STAGE_THRESHOLD) {
			strikeLateStage(level, victim, count);
			return;
		}

		strikeSummoning(level, victim);
	}

	/**
	 * Early stage: summon a pack of hostile mobs.
	 *
	 * <p>A wave that finds nowhere to spawn counts as a failure. Two consecutive failures
	 * push the counter straight to {@link #LATE_STAGE_THRESHOLD}, so hiding in a sealed
	 * space brings the next stage forward instead of avoiding the mechanic.
	 */
	private static void strikeSummoning(ServerLevel level, LivingEntity victim) {
		Calamity.SummonResult result = Calamity.summon(level, victim);

		if (!result.spawned().isEmpty()) {
			victim.setAttached(TaoYuanAttachments.CALAMITY_FAILED_WAVES, 0);

			TaoYuanMod.LOGGER.debug("Calamity struck {}: summoned {} mob(s)",
					victim.getName().getString(), result.spawned().size());
			return;
		}

		int failures = victim.getAttachedOrCreate(TaoYuanAttachments.CALAMITY_FAILED_WAVES) + 1;

		if (failures >= FAILED_WAVES_BEFORE_ESCALATION) {
			// Snapshot the level and player view we need before handing off, because the
			// deferred task may run on a different tick.
			ServerLevel sl = level;
			ServerPlayer player = victim instanceof ServerPlayer p ? p : null;

			victim.setAttached(TaoYuanAttachments.CALAMITY_COUNT, LATE_STAGE_THRESHOLD);
			victim.setAttached(TaoYuanAttachments.CALAMITY_FAILED_WAVES, 0);

			TaoYuanMod.LOGGER.debug("{} blocked {} calamity wave(s) in a row; escalating to stage {}",
					victim.getName().getString(), failures, LATE_STAGE_THRESHOLD);

			// Hand off to the terror system. The decision (sky / underground / no-op) is
			// made there based on the victim's Y and whether the world is enclosed.
			if (player != null) {
				CalamityTerror.trigger(sl, player);
			}
			return;
		}

		victim.setAttached(TaoYuanAttachments.CALAMITY_FAILED_WAVES, failures);

		TaoYuanMod.LOGGER.debug("Calamity found nowhere to spawn around {} ({} failure(s) in a row)",
				victim.getName().getString(), failures);
	}

	/**
	 * Late stage: reserved for the mechanic that replaces summoning once the victim has been
	 * afflicted {@link #LATE_STAGE_THRESHOLD} times. Not yet implemented.
	 */
	private static void strikeLateStage(ServerLevel level, LivingEntity victim, int count) {
		TaoYuanMod.LOGGER.debug("Late-stage calamity due for {} (counter at {}), not yet implemented",
				victim.getName().getString(), count);
	}
}
