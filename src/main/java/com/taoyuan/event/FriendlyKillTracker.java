package com.taoyuan.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import com.taoyuan.TaoYuanMod;
import com.taoyuan.attachment.TaoYuanAttachments;

/**
 * Counts every "good" kill a player makes (currently: villager and iron golem only).
 *
 * <p>The counter is consulted by the seven-sword terror, which ranks players on their past
 * deeds. See {@code CalamityTerror}.
 */
public final class FriendlyKillTracker {
	private FriendlyKillTracker() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(FriendlyKillTracker::onDeath);
		TaoYuanMod.LOGGER.debug("Registered friendly kill tracker");
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		// Only the two NPCs that the seven-sword myth references. The set deliberately
		// excludes animals and neutral mobs; this is a moral ledger, not a kill counter.
		if (!(entity instanceof Villager) && !(entity instanceof IronGolem)) {
			return;
		}

		// Resolve the killer. Going through getEntity() catches both direct and indirect
		// (pet, projectile) blame, which is what the seven-sword myth would care about.
		Player killer = source.getEntity() instanceof Player p ? p : null;

		if (killer == null) {
			return;
		}

		int total = killer.getAttachedOrCreate(TaoYuanAttachments.FRIENDLY_KILLS) + 1;
		killer.setAttached(TaoYuanAttachments.FRIENDLY_KILLS, total);

		TaoYuanMod.LOGGER.debug("{} killed a {}; tally now {}",
				killer.getName().getString(), entity.getType().getDescriptionId(), total);
	}
}
