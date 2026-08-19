package com.taoyuan.effect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import com.taoyuan.TaoYuanMod;
import com.taoyuan.attachment.TaoYuanAttachments;
import com.taoyuan.damage.TaoYuanDamageTypes;
import com.taoyuan.damage.TaoYuanExecution;

public final class CalamityTerror {
	private static final int NARRATION_DELAY_TICKS = 3 * 20;

	private record ScheduledTask(int[] ticksRemaining, Runnable action) {}

	private static final Object SCHEDULER_LOCK = new Object();
	private static final List<ScheduledTask> SCHEDULED = new ArrayList<>();

	private CalamityTerror() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			synchronized (SCHEDULER_LOCK) {
				Iterator<ScheduledTask> it = SCHEDULED.iterator();
				while (it.hasNext()) {
					ScheduledTask t = it.next();
					if (--t.ticksRemaining[0] <= 0) {
						it.remove();
						t.action.run();
					}
				}
			}
		});
	}

	private static void tell(Runnable action, int ticks) {
		synchronized (SCHEDULER_LOCK) {
			SCHEDULED.add(new ScheduledTask(new int[]{ticks}, action));
		}
	}

	/**
	 * Resolves which branch the victim falls into and runs it. Caller must have already
	 * set the counter to {@code LATE_STAGE_THRESHOLD}.
	 *
	 * <p>Height plays no part any more. Both terrors are decided entirely from what is
	 * around the victim, so the same judgment holds at y=200 as at y=-50, in the Nether as
	 * in the End, and no dimension needs a threshold of its own.
	 *
	 * <p>Enclosure is measured directly from the surrounding blocks rather than from a failed
	 * wave: a wave can fail for reasons that have nothing to do with hiding.
	 *
	 * <p>Enclosure is tested first. The two conditions can both hold -- standing at the
	 * bottom of a shaft dug straight to the surface is both walled in and open overhead --
	 * and being boxed in is the more definite state of the two.
	 */
	public static void trigger(ServerLevel level, ServerPlayer victim) {
		boolean enclosed = Calamity.isEnclosed(level, victim);
		boolean exposed = Calamity.isExposedToSky(level, victim);

		TaoYuanMod.LOGGER.debug("Terror check for {} in {}: enclosed={}, exposed={}, riding={}",
				victim.getName().getString(), level.dimension().identifier(),
				enclosed, exposed, CalamityRide.describeVehicle(victim));

		if (enclosed) {
			triggerSevenSwordTerror(level, victim);
		} else if (exposed) {
			triggerSkyTerror(level, victim);
		} else {
			TaoYuanMod.LOGGER.debug("No terror applies to {} (neither enclosed nor exposed)",
					victim.getName().getString());
		}
	}

	private static void triggerSkyTerror(ServerLevel level, ServerPlayer victim) {
		victim.sendSystemMessage(narrate("message.taoyuan.sky_terror", victim));

		tell(() -> {
			ServerLevel sl = currentServerLevel(victim);
			if (sl == null) {
				return;
			}

			TaoYuanExecution.Result result = TaoYuanExecution.execute(sl, victim, TaoYuanDamageTypes.SKY_TERROR);
			narrateExecution(victim, result);

			TaoYuanMod.LOGGER.debug("Sky terror resolved for {}: {}",
					victim.getName().getString(), result);
		}, NARRATION_DELAY_TICKS);
	}

	private static void triggerSevenSwordTerror(ServerLevel level, ServerPlayer victim) {
		victim.sendSystemMessage(narrate("message.taoyuan.seven_sword", victim));

		tell(() -> {
			ServerLevel sl = currentServerLevel(victim);
			if (sl == null) {
				return;
			}
			runJudgment(sl);
			TaoYuanMod.LOGGER.debug("Seven-sword judgment complete for {}", victim.getName().getString());
		}, NARRATION_DELAY_TICKS);
	}

	/**
	 * The seven-sword judges everyone online.
	 *
	 * <p>Every participant first hears the two lines that explain why judgment has come at
	 * all, then their own verdict is applied. Carrying the calamity is itself the verdict of
	 * "great evil" -- those players are struck down on their own account, not as collateral
	 * of whoever triggered the terror.
	 *
	 * <p>Creative players are ruled supremely good regardless of their ledger, so a
	 * developer or admin passing through is rewarded rather than repeatedly killed. That
	 * check comes first, before the calamity indictment, because an ability-invulnerable
	 * player could not be executed anyway.
	 */
	private static void runJudgment(ServerLevel level) {
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			// Each player is judged in their own dimension. Reusing the triggering player's
			// level would drop rewards into the wrong world at the same coordinates, and
			// would resolve gamerules and damage events against a level the victim is not in.
			ServerLevel playerLevel = p.level();

			// Context for the verdict, sent to participant and bystander alike.
			p.sendSystemMessage(narrate("message.taoyuan.judgment_echo"));
			p.sendSystemMessage(narrate("message.taoyuan.judgment_summon"));

			// Creative outranks every other verdict: judged supremely good, ledger ignored.
			if (p.getAbilities().invulnerable) {
				dropLoot(playerLevel, p, 1, new ItemStack(Items.GOLD_INGOT));
				p.sendSystemMessage(narrate("message.taoyuan.sword_cannot_judge_god"));
				TaoYuanMod.LOGGER.debug("Seven-sword judged {} supremely good (creative)",
						p.getName().getString());
				continue;
			}

			// Carrying the calamity is its own indictment: the sword takes them regardless
			// of their ledger, and the good-kill tiers never apply.
			if (p.hasEffect(TaoYuanEffects.CALAMITY_BRINGER)) {
				judge(playerLevel, p);
				continue;
			}

			int kills = p.getAttachedOrCreate(TaoYuanAttachments.FRIENDLY_KILLS);

			if (kills <= 10) {
				dropLoot(playerLevel, p, 1, new ItemStack(Items.GOLD_INGOT));
			} else if (kills <= 20) {
				dropLoot(playerLevel, p, 7, new ItemStack(Items.IRON_NUGGET));
			} else {
				judge(playerLevel, p);
			}
		}
	}

	/**
	 * Runs the seven-sword execution and narrates whatever the sword actually managed to do.
	 */
	private static void judge(ServerLevel level, ServerPlayer player) {
		TaoYuanExecution.Result result = TaoYuanExecution.execute(level, player, TaoYuanDamageTypes.SEVEN_SWORD);
		narrateExecution(player, result);

		TaoYuanMod.LOGGER.debug("Seven-sword verdict for {}: {}", player.getName().getString(), result);
	}

	/**
	 * Tells the player when something intervened on their behalf.
	 *
	 * <p>A totem earns the two-line scapegoat aside, a creative player is told they are
	 * above the judgment, and a death needs no commentary beyond the death message itself.
	 */
	private static void narrateExecution(ServerPlayer player, TaoYuanExecution.Result result) {
		if (result == TaoYuanExecution.Result.SAVED_BY_TOTEM) {
			player.sendSystemMessage(narrate("message.taoyuan.scapegoat_echo"));
			player.sendSystemMessage(narrate("message.taoyuan.scapegoat_puppet"));
		} else if (result == TaoYuanExecution.Result.SPARED_CREATIVE) {
			player.sendSystemMessage(narrate("message.taoyuan.god_does_not_die"));
		}
	}

	private static void dropLoot(ServerLevel level, Player target, int count, ItemStack template) {
		Vec3 pos = target.position();

		for (int i = 0; i < count; i++) {
			ItemStack stack = template.copy();
			ItemEntity item = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
			item.setDeltaMovement(
					level.random.nextGaussian() * 0.05,
					level.random.nextDouble() * 0.1 + 0.1,
					level.random.nextGaussian() * 0.05);
			level.addFreshEntity(item);
		}
	}

	private static ServerLevel currentServerLevel(ServerPlayer p) {
		return p.level() instanceof ServerLevel sl ? sl : null;
	}

	private static Component narrate(String key, ServerPlayer victim) {
		return Component.translatable(key, victim.getName().getString())
				.withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
	}

	/** Same styling as {@link #narrate}, for lines that take no arguments. */
	private static Component narrate(String key) {
		return Component.translatable(key)
				.withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
	}
}
