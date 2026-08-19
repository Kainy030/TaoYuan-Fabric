package com.taoyuan.damage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import org.jspecify.annotations.Nullable;

/**
 * Carries the cause of a forced execution across the {@link ServerPlayer#die} call.
 *
 * <p>{@link TaoYuanExecution} deliberately bypasses the damage pipeline, which means
 * {@code CombatTracker} never sees a {@code recordDamage} call. Without that,
 * {@code CombatTracker#getDeathMessage} takes its empty-entries branch and reports
 * {@code death.attack.generic} (CombatTracker.java:87), losing the custom message entirely.
 *
 * <p>{@code ServerPlayerDeathMessageMixin} reads this to restore the correct message. The
 * value is only ever set for the duration of one {@code die} call, and
 * {@link TaoYuanExecution} clears it in a {@code finally} block so a throwing death handler
 * cannot leave a stale entry behind.
 *
 * <p>Keyed by UUID rather than by entity so a leaked entry can never retain a
 * {@code ServerPlayer}. Concurrent because entity death can be driven from more than one
 * thread across dimensions, even though the common case is the single server thread.
 */
public final class TaoYuanDeathContext {
	private static final Map<UUID, DamageSource> ACTIVE = new ConcurrentHashMap<>();

	private TaoYuanDeathContext() {
	}

	static void set(ServerPlayer player, DamageSource source) {
		ACTIVE.put(player.getUUID(), source);
	}

	static void clear(ServerPlayer player) {
		ACTIVE.remove(player.getUUID());
	}

	/**
	 * The damage source of the forced execution currently killing this player, or null when
	 * the death did not come from this mod.
	 */
	public static @Nullable DamageSource current(ServerPlayer player) {
		return ACTIVE.get(player.getUUID());
	}
}
