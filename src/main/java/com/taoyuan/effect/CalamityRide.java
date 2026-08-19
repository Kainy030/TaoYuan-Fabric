package com.taoyuan.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * Resolves where a victim really is when they are riding something.
 *
 * <p>A passenger's own {@code blockPosition()} sits at their feet, which for a minecart or a
 * boat is inside the vehicle rather than in the space around it. Judging the terrors from
 * that position misreads the surroundings: a player in a minecart running through a tunnel
 * reads as though the rails were their floor, and a player in a boat reads as though the
 * hull were solid ground.
 *
 * <p>Rather than special-casing minecarts alone, every vehicle is handled the same way --
 * boats, horses, pigs, striders and another player's shoulders all present the same problem.
 */
public final class CalamityRide {
	private CalamityRide() {
	}

	/**
	 * The position the terror checks should be run from.
	 *
	 * <p>For a passenger this is the vehicle's own block position, which is the cell actually
	 * embedded in the world. For everyone else it is simply the victim's position.
	 */
	public static BlockPos judgementPos(LivingEntity victim) {
		Entity vehicle = victim.getVehicle();

		return vehicle != null ? vehicle.blockPosition() : victim.blockPosition();
	}

	/**
	 * Whether the victim is riding anything at all.
	 */
	public static boolean isRiding(LivingEntity victim) {
		return victim.isPassenger();
	}

	/**
	 * Whether the victim is in a minecart specifically.
	 *
	 * <p>Minecarts are the one vehicle that routinely carries a player through terrain that
	 * would otherwise read as enclosing -- rail tunnels are dug to exactly the profile that
	 * the enclosure check looks for -- so callers may want to know.
	 */
	public static boolean isInMinecart(LivingEntity victim) {
		return victim.getVehicle() instanceof AbstractMinecart;
	}

	/**
	 * A short description of what the victim is riding, for logging.
	 */
	public static String describeVehicle(LivingEntity victim) {
		Entity vehicle = victim.getVehicle();

		return vehicle == null ? "none" : vehicle.getType().getDescriptionId();
	}
}
