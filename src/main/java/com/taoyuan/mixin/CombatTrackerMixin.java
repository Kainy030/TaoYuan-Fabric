package com.taoyuan.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.taoyuan.damage.TaoYuanDeathContext;

/**
 * Restores the death message for this mod's forced executions.
 *
 * <p>{@code TaoYuanExecution} kills by calling {@code setHealth(0)} and {@code die(...)}
 * directly, which is what makes those judgments unavoidable -- but it also means the damage
 * never passes through {@code LivingEntity#actuallyHurt}, so {@code CombatTracker} never
 * receives a {@code recordDamage} call. With no entries recorded,
 * {@link CombatTracker#getDeathMessage} takes its first branch and returns
 * {@code death.attack.generic} (CombatTracker.java:87-89), which would show
 * "Player died" instead of the mod's own message.
 *
 * <p>Injecting here rather than at the {@code ServerPlayer#die} call site keeps the fix in
 * one place: the same tracker is consulted for the packet sent to the dying player, for the
 * broadcast to everyone else, and for the team-visibility variants
 * (ServerPlayer.java:865-888).
 */
@Mixin(CombatTracker.class)
public abstract class CombatTrackerMixin {
	@Shadow
	@Final
	private LivingEntity mob;

	/**
	 * Supplies the correct message while one of this mod's executions is in progress.
	 *
	 * <p>Deliberately narrow: it only fires when {@code TaoYuanDeathContext} has an entry
	 * for this exact player, which is only ever true inside a single
	 * {@code TaoYuanExecution#execute} call. Every other death, including ordinary deaths
	 * from the mod's own damage types dealt through the normal pipeline, is left untouched.
	 */
	@Inject(method = "getDeathMessage", at = @At("HEAD"), cancellable = true)
	private void taoyuan$forcedExecutionMessage(CallbackInfoReturnable<Component> info) {
		if (!(this.mob instanceof ServerPlayer player)) {
			return;
		}

		DamageSource source = TaoYuanDeathContext.current(player);

		if (source != null) {
			info.setReturnValue(source.getLocalizedDeathMessage(player));
		}
	}
}
