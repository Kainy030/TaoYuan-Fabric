package com.taoyuan.effect;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;

import com.taoyuan.TaoYuanMod;

/**
 * Mob effects added by this mod.
 */
public final class TaoYuanEffects {
	/**
	 * 招灾 (Calamity Bringer) -- {@code taoyuan:calamity_bringer}.
	 *
	 * <p>Registered as a {@link Holder} because that is what {@code MobEffectInstance} and
	 * {@code LivingEntity#addEffect} expect in 1.21.11.
	 *
	 * <p>Test in game with: {@code /effect give @p taoyuan:calamity_bringer}
	 */
	public static final Holder<MobEffect> CALAMITY_BRINGER = Registry.registerForHolder(
			BuiltInRegistries.MOB_EFFECT,
			TaoYuanMod.id("calamity_bringer"),
			new CalamityBringerEffect()
	);

	private TaoYuanEffects() {
	}

	/**
	 * Triggers class loading, and therefore registration. Called from the mod initializer.
	 */
	public static void initialize() {
		TaoYuanMod.LOGGER.info("Registered mob effect: {}", TaoYuanMod.id("calamity_bringer"));
	}
}
