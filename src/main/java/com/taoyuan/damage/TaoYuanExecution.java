package com.taoyuan.damage;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.level.gameevent.GameEvent;

import com.taoyuan.TaoYuanMod;

/**
 * Forced execution: the mod's single route for kills that are meant to be unavoidable.
 *
 * <p>Where this mod passes judgment, that judgment is final. The only two things that can
 * save a player are a totem of undying and creative mode; everything else -- the
 * {@code pvp} gamerule, team friendly-fire settings, Resistance, difficulty scaling, the
 * post-hit invulnerability window -- is bypassed by construction.
 *
 * <h2>Why this does not call {@code hurtServer}</h2>
 *
 * <p>Vanilla's damage pipeline can refuse the hit in at least five independent places, all
 * verified against 1.21.11:
 *
 * <ul>
 *   <li>{@code ServerPlayer#hurtServer} (ServerPlayer.java:961) returns false when the
 *       attacker is a player and {@code canHarmPlayer} says no, which returns false
 *       whenever the {@code pvp} gamerule is off (ServerPlayer.java:975-980).</li>
 *   <li>{@code Player#canHarmPlayer} (Player.java:737) also refuses when both parties
 *       share a team that disallows friendly fire.</li>
 *   <li>{@code Player#hurtServer} (Player.java:694) refuses outright while
 *       {@code abilities.invulnerable} is set, and zeroes the damage on Peaceful
 *       (Player.java:702-704) because our damage types use {@code "scaling": "always"}.</li>
 *   <li>{@code LivingEntity#getDamageAfterMagicAbsorb} (LivingEntity.java:1782) reduces
 *       the damage to zero under Resistance V.</li>
 *   <li>{@code LivingEntity#hurtServer} (LivingEntity.java:1172) drops the hit entirely
 *       while {@code invulnerableTime} is still counting down.</li>
 * </ul>
 *
 * <p>Rather than fight each of those in turn, this class skips the pipeline: it sets health
 * to zero and calls {@link ServerPlayer#die} directly, which is the same terminal call
 * vanilla makes from {@code LivingEntity#hurtServer} (LivingEntity.java:1229).
 *
 * <p>The cost of skipping the pipeline is that {@code CombatTracker} never records an
 * entry, so {@code getDeathMessage} would fall back to {@code death.attack.generic}
 * (CombatTracker.java:87). {@code ServerPlayerDeathMessageMixin} supplies the correct
 * message for these kills.
 */
public final class TaoYuanExecution {
	private TaoYuanExecution() {
	}

	/**
	 * The outcome of an execution attempt, so callers can narrate the result.
	 */
	public enum Result {
		/** The player died. */
		KILLED,

		/** A totem of undying was consumed instead. */
		SAVED_BY_TOTEM,

		/** The player is ability-invulnerable (creative) and was spared. */
		SPARED_CREATIVE
	}

	/**
	 * Kills the player unless a totem or creative mode intervenes.
	 *
	 * <p>Creative is checked before the totem so a creative player never loses an item to a
	 * judgment they were never subject to.
	 *
	 * @param level      the level the player is actually in
	 * @param player     the target
	 * @param damageType the damage type to attribute the death to
	 * @return what actually happened
	 */
	public static Result execute(ServerLevel level, ServerPlayer player, ResourceKey<DamageType> damageType) {
		if (player.getAbilities().invulnerable) {
			TaoYuanMod.LOGGER.debug("Execution spared {}: ability-invulnerable (creative)",
					player.getName().getString());
			return Result.SPARED_CREATIVE;
		}

		if (consumeTotem(level, player)) {
			TaoYuanMod.LOGGER.debug("Execution of {} was blocked by a totem",
					player.getName().getString());
			return Result.SAVED_BY_TOTEM;
		}

		DamageSource source = source(level, damageType);

		// Publish the cause so the death-message mixin can report it, since bypassing the
		// damage pipeline means CombatTracker has nothing recorded.
		TaoYuanDeathContext.set(player, source);

		try {
			player.setHealth(0.0F);
			player.die(source);
		} finally {
			TaoYuanDeathContext.clear(player);
		}

		TaoYuanMod.LOGGER.debug("Executed {} with {}", player.getName().getString(),
				damageType.identifier());

		return Result.KILLED;
	}

	/**
	 * Consumes a death-protecting item from either hand, mirroring vanilla's own totem
	 * handling (LivingEntity.java:1339-1369).
	 *
	 * <p>Reimplemented rather than reused because
	 * {@code LivingEntity#checkTotemDeathProtection} is private, and because vanilla
	 * short-circuits it for damage types tagged {@code bypasses_invulnerability}
	 * (LivingEntity.java:1340) -- a tag these judgments may want later without thereby
	 * losing totem support.
	 *
	 * @return true when a totem was consumed and the player should survive
	 */
	private static boolean consumeTotem(ServerLevel level, ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack held = player.getItemInHand(hand);
			DeathProtection protection = held.get(DataComponents.DEATH_PROTECTION);

			if (protection == null) {
				continue;
			}

			ItemStack consumed = held.copy();
			held.shrink(1);

			player.awardStat(Stats.ITEM_USED.get(consumed.getItem()));
			CriteriaTriggers.USED_TOTEM.trigger(player, consumed);
			consumed.causeUseVibration(player, GameEvent.ITEM_INTERACT_FINISH);

			player.setHealth(1.0F);
			protection.applyEffects(consumed, player);

			// Byte 35 is the totem-activated entity event, same as vanilla
			// (LivingEntity.java:1365).
			level.broadcastEntityEvent(player, (byte) 35);

			return true;
		}

		return false;
	}

	/**
	 * Builds a damage source for one of this mod's damage types.
	 *
	 * <p>Deliberately attacker-less. Vanilla's {@code getLocalizedDeathMessage} switches to
	 * a {@code .item} translation key whenever the causing entity holds a renamed item
	 * (DamageSource.java:76), which would produce a missing key such as
	 * {@code death.attack.taoyuan.seven_sword.item}. With no attacker, the message stays on
	 * the plain key.
	 */
	public static DamageSource source(ServerLevel level, ResourceKey<DamageType> damageType) {
		Holder<DamageType> holder = level.registryAccess()
				.lookupOrThrow(Registries.DAMAGE_TYPE)
				.getOrThrow(damageType);

		return new DamageSource(holder);
	}
}
