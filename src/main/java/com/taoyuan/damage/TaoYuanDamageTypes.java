package com.taoyuan.damage;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

import com.taoyuan.TaoYuanMod;

/**
 * {@link ResourceKey} handles for the custom {@link DamageType}s this mod adds as data pack JSON.
 */
public final class TaoYuanDamageTypes {
	public static final ResourceKey<DamageType> SKY_TERROR = ResourceKey.create(
			Registries.DAMAGE_TYPE,
			TaoYuanMod.id("sky_terror")
	);

	public static final ResourceKey<DamageType> SEVEN_SWORD = ResourceKey.create(
			Registries.DAMAGE_TYPE,
			TaoYuanMod.id("seven_sword")
	);

	private TaoYuanDamageTypes() {
	}
}
