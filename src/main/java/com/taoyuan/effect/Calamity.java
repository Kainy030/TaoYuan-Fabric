package com.taoyuan.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import com.taoyuan.TaoYuanMod;

/**
 * The calamity summoned by 招灾 (Calamity Bringer).
 *
 * <p>Spawns a small pack of hostile mobs around the victim, then rivets the aggression of
 * every {@link Monster} nearby onto that victim for a short while.
 */
public final class Calamity {
	/**
	 * Mob types summoned on dry land; each is equally likely (25% with four entries).
	 */
	private static final List<EntityType<? extends Mob>> LAND_POOL = List.of(
			EntityType.ZOMBIE,
			EntityType.SKELETON,
			EntityType.SPIDER,
			EntityType.CREEPER
	);

	/**
	 * Mob summoned in water.
	 *
	 * <p>Only drowned. The land pool is unusable here: zombies convert to drowned after 300
	 * ticks anyway ({@code Zombie#convertsInWater}, Zombie.java:202), while skeletons,
	 * spiders and creepers simply suffocate once their air runs out
	 * ({@code LivingEntity#tick}, LivingEntity.java:423-430) -- roughly 25 seconds, which is
	 * less than the 30-second wave interval, so the victim would face nothing at all.
	 *
	 * <p>Drowned instead persist indefinitely and can swim, which is what makes submerging
	 * a real risk rather than a hiding place.
	 */
	private static final EntityType<? extends Mob> WATER_MOB = EntityType.DROWNED;

	/**
	 * Mob types summoned in lava, split evenly.
	 *
	 * <p>Magma cubes are fire-immune outright ({@code MagmaCube#isOnFire} returns false,
	 * MagmaCube.java:52). Blazes are not lava-immune as such but do not sink into it either;
	 * note they take damage in water <em>or rain</em>
	 * ({@code Blaze#isSensitiveToWater}, Blaze.java:117, applied in LivingEntity.java:2977),
	 * so an open-air lava pool during a storm will thin them out.
	 *
	 * <p>Magma cube size is left to vanilla's own randomisation
	 * ({@code Slime#finalizeSpawn}, Slime.java:320-330), which can roll size 4 with armour
	 * 12. That is deliberate: standing in lava is already fatal, so whatever climbs out is
	 * the victim's own doing.
	 */
	private static final List<EntityType<? extends Mob>> LAVA_POOL = List.of(
			EntityType.BLAZE,
			EntityType.MAGMA_CUBE
	);

	private static final int MIN_SUMMONS = 5;
	private static final int MAX_SUMMONS = 10;

	/** Lava waves are smaller, because what they summon is considerably stronger. */
	private static final int MIN_LAVA_SUMMONS = 3;
	private static final int MAX_LAVA_SUMMONS = 6;

	/**
	 * Chance that any given lava placement actually produces a mob.
	 *
	 * <p>Halves the effective wave size on top of the already reduced count.
	 */
	private static final float LAVA_SPAWN_CHANCE = 0.5F;

	/**
	 * Horizontal extent of the spawn ring, in blocks. Candidates are taken from the 5x5
	 * footprint centred on the victim.
	 */
	private static final int SPAWN_RADIUS = 2;

	/**
	 * Chebyshev distance the victim keeps clear around themselves. With a value of 1, the
	 * 3x3 immediately around the victim is excluded, leaving the ring between 1x1 and 5x5.
	 */
	private static final int SPAWN_EXCLUSION = 1;

	/**
	 * How far above the victim's feet to scan for a spawn position.
	 *
	 * <p>One layer, so a mob can appear level with the victim's head as well as below them.
	 * Without it a victim standing in a shallow pit or at the foot of a step is only ever
	 * answered from below, and the ledge beside them at head height goes unused.
	 */
	private static final int Y_SEARCH_ABOVE = 2;

	/**
	 * How far below the victim's feet to scan for a foothold at each horizontal position.
	 * The base Y itself is also tried, so a player standing on the ground is still covered.
	 * <p>{@code 8} covers a player mid-jump and a slab or two of terrain variation below them.
	 * Tall structures remain safe havens, which is the only way to dodge a wave without
	 * sealing the whole 5x5 column.
	 */
	private static final int Y_SEARCH_RANGE = 8;

	/** Radius of the aggression lock, in blocks; gives the requested 16x16x16 box. */
	private static final double AGGRO_RADIUS = 8.0;

	/**
	 * The result of one wave: the mobs that were placed and whether the wave at least
	 * <em>found</em> somewhere to place them.
	 *
	 * <p>{@link #anyCandidatesFound()} distinguishes "nowhere at all to put anything" from
	 * "had somewhere but every placement was rejected", which is what tells a genuinely
	 * sealed-in victim apart from one who merely got unlucky.
	 */
	public record SummonResult(List<Mob> spawned, boolean anyCandidatesFound) {
		public static final SummonResult EMPTY = new SummonResult(List.of(), false);
	}

	/**
	 * What a candidate position is filled with, which decides both which mobs can be
	 * summoned there and whether the position counts as an escape route.
	 */
	public enum Medium {
		/** Air or another non-solid, non-fluid block: mobs stand here normally. */
		OPEN,

		/** Water. */
		WATER,

		/** Lava. */
		LAVA,

		/** A solid block. */
		SOLID
	}

	/**
	 * Classifies a single position.
	 *
	 * <p>Fluids are checked before solidity because a waterlogged block reports both, and
	 * for our purposes "there is water here" is the more meaningful fact.
	 */
	public static Medium mediumAt(ServerLevel level, BlockPos pos) {
		FluidState fluid = level.getFluidState(pos);

		if (fluid.is(FluidTags.WATER)) {
			return Medium.WATER;
		}

		if (fluid.is(FluidTags.LAVA)) {
			return Medium.LAVA;
		}

		// blocksMotion rather than isSolid: it is the same notion vanilla's own
		// MOTION_BLOCKING heightmap uses (Heightmap.java:31) and, unlike isSolid, it does
		// not let a torch or a carpet read as though the space were free.
		return level.getBlockState(pos).blocksMotion() ? Medium.SOLID : Medium.OPEN;
	}

	private Calamity() {
	}

	/**
	 * Summons one wave of hostile mobs around the victim.
	 *
	 * <p>Candidates are restricted to the ring outside the immediate 3x3 so mobs never
	 * materialise right on top of the victim, and to a short vertical scan around the
	 * victim's own feet -- one layer above, several below.
	 *
	 * <p>The medium at each position decides what gets summoned: dry footholds use the land
	 * pool and vanilla's own {@link SpawnPlacements} rules, water summons drowned, and lava
	 * summons blazes and magma cubes. Submerging therefore no longer sidesteps the mechanic,
	 * which it used to: vanilla refuses every spawn touching a fluid outright
	 * ({@code NaturalSpawner#isValidEmptySpawnBlock}, NaturalSpawner.java:306).
	 *
	 * <p>Does nothing on Peaceful, where vanilla despawns hostile mobs anyway.
	 */
	public static SummonResult summon(ServerLevel level, LivingEntity victim) {
		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			return SummonResult.EMPTY;
		}

		RandomSource random = level.getRandom();

		List<BlockPos> land = new ArrayList<>();
		List<BlockPos> water = new ArrayList<>();
		List<BlockPos> lava = new ArrayList<>();

		collectCandidates(level, victim, land, water, lava);

		boolean anyCandidatesFound = !land.isEmpty() || !water.isEmpty() || !lava.isEmpty();

		if (!anyCandidatesFound) {
			return SummonResult.EMPTY;
		}

		List<Mob> spawned = new ArrayList<>();

		// Fluids take precedence: a victim standing in water or lava is dealt with by the
		// medium they chose, not by whatever dry ledge happens to be nearby.
		if (!water.isEmpty()) {
			summonWave(level, random, spawned, water,
					MIN_SUMMONS, MAX_SUMMONS, 1.0F, true, pos -> WATER_MOB);
		} else if (!lava.isEmpty()) {
			summonWave(level, random, spawned, lava,
					MIN_LAVA_SUMMONS, MAX_LAVA_SUMMONS, LAVA_SPAWN_CHANCE, true,
					pos -> LAVA_POOL.get(random.nextInt(LAVA_POOL.size())));
		} else {
			summonWave(level, random, spawned, land,
					MIN_SUMMONS, MAX_SUMMONS, 1.0F, false,
					pos -> LAND_POOL.get(random.nextInt(LAND_POOL.size())));
		}

		return new SummonResult(spawned, anyCandidatesFound);
	}

	/**
	 * Places one wave into the given candidate positions.
	 *
	 * @param chance probability that each individual placement is attempted at all
	 * @param picker chooses the mob type for a position
	 */
	private static void summonWave(ServerLevel level, RandomSource random, List<Mob> spawned,
			List<BlockPos> candidates, int min, int max, float chance, boolean inFluid,
			Function<BlockPos, EntityType<? extends Mob>> picker) {
		int wanted = min + random.nextInt(max - min + 1);

		for (int i = 0; i < wanted; i++) {
			if (chance < 1.0F && random.nextFloat() >= chance) {
				continue;
			}

			BlockPos pos = candidates.get(random.nextInt(candidates.size()));
			Mob mob = spawnAt(level, picker.apply(pos), pos, random, inFluid);

			if (mob != null) {
				spawned.add(mob);
			}
		}
	}

	/**
	 * Gathers every viable position in the ring, sorted into the three media.
	 *
	 * <p>Dry positions are validated with the land pool's own placement rules, so they still
	 * require a solid block underfoot and two blocks of headroom. Fluid positions only need
	 * the fluid itself to be deep enough for the mob to occupy, since the mobs summoned
	 * there live in it.
	 *
	 * <p>Vertically the scan runs from {@link #Y_SEARCH_ABOVE} layers above the victim's feet
	 * down to {@link #Y_SEARCH_RANGE} below, so a ledge at head height is as usable as the
	 * floor below.
	 */
	private static void collectCandidates(ServerLevel level, LivingEntity victim,
			List<BlockPos> land, List<BlockPos> water, List<BlockPos> lava) {
		BlockPos origin = victim.blockPosition();

		int baseX = origin.getX();
		int baseY = origin.getY();
		int baseZ = origin.getZ();

		for (int dx = -SPAWN_RADIUS; dx <= SPAWN_RADIUS; dx++) {
			for (int dz = -SPAWN_RADIUS; dz <= SPAWN_RADIUS; dz++) {
				// Keep the victim's immediate surroundings clear.
				if (Math.max(Math.abs(dx), Math.abs(dz)) <= SPAWN_EXCLUSION) {
					continue;
				}

				int x = baseX + dx;
				int z = baseZ + dz;

				// From one layer above the victim's feet down to the bottom of the scan.
				for (int dy = Y_SEARCH_ABOVE; dy >= -Y_SEARCH_RANGE; dy--) {
					BlockPos pos = new BlockPos(x, baseY + dy, z);

					switch (mediumAt(level, pos)) {
						case WATER -> {
							// Two cells of water so the mob is properly submerged.
							if (mediumAt(level, pos.above()) == Medium.WATER) {
								water.add(pos);
							}
						}
						case LAVA -> lava.add(pos);
						case OPEN -> {
							// Dry ground still answers to vanilla's placement rules; any of
							// the land pool passing is enough to call the spot viable.
							if (isLandFootholdOk(level, pos)) {
								land.add(pos);
							}
						}
						default -> {
						}
					}
				}
			}
		}
	}

	/**
	 * Minimum thickness of the floor beneath a land foothold, in blocks.
	 *
	 * <p>Mobs will not be summoned onto a thin slab of a platform. Without this, a player who
	 * bridged out into the sky would be answered with a wave of mobs standing on the bridge
	 * itself, and because a successful wave clears the failure counter
	 * ({@code CalamityBringerEffect#strikeSummoning}) the sky terror would never get its
	 * turn -- laying a three-wide walkway would quietly downgrade the outcome from a
	 * certain death to a handful of mobs.
	 *
	 * <p>Three blocks is deliberately modest: natural ground, floors and roofs all clear it,
	 * while bridges, scaffolding and platforms do not.
	 */
	private static final int MIN_FLOOR_THICKNESS = 3;

	/**
	 * Horizontal directions a foothold looks in to find a neighbour sharing its floor.
	 */
	private static final int[][] HORIZONTAL_NEIGHBOURS = {
			{1, 0},
			{-1, 0},
			{0, 1},
			{0, -1}
	};

	/**
	 * Whether any land-pool mob could legally stand at this position.
	 *
	 * <p>Three things must hold: vanilla's own placement rules, a floor at least
	 * {@link #MIN_FLOOR_THICKNESS} deep, and a horizontally adjacent column whose floor is
	 * just as deep. The last of those means the ground has to be at least two cells across,
	 * so a single-file walkway is never summoned onto however deep it is built.
	 */
	private static boolean isLandFootholdOk(ServerLevel level, BlockPos pos) {
		if (!hasSubstantialFloor(level, pos) || !hasAdjacentFloor(level, pos)) {
			return false;
		}

		for (EntityType<? extends Mob> type : LAND_POOL) {
			if (SpawnPlacements.isSpawnPositionOk(type, level, pos)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether at least one of the four horizontally adjacent columns has a floor as deep as
	 * this one's.
	 *
	 * <p>Together with {@link #hasSubstantialFloor} this requires a 2x1 footprint of solid
	 * ground: enough to call it a floor, rather than a line of blocks to walk along.
	 */
	private static boolean hasAdjacentFloor(ServerLevel level, BlockPos pos) {
		for (int[] offset : HORIZONTAL_NEIGHBOURS) {
			BlockPos neighbour = new BlockPos(pos.getX() + offset[0], pos.getY(), pos.getZ() + offset[1]);

			if (hasSubstantialFloor(level, neighbour)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether the cells directly below this position form a floor thick enough to summon
	 * onto.
	 *
	 * <p>Requires {@link #MIN_FLOOR_THICKNESS} solid cells in an unbroken run, starting
	 * immediately below {@code pos}. Bedrock counts as unbounded, so the bottom of the world
	 * is always thick enough.
	 */
	private static boolean hasSubstantialFloor(ServerLevel level, BlockPos pos) {
		int bottom = level.getMinY();

		for (int i = 1; i <= MIN_FLOOR_THICKNESS; i++) {
			int y = pos.getY() - i;

			if (y < bottom) {
				return false;
			}

			BlockPos below = new BlockPos(pos.getX(), y, pos.getZ());

			if (level.getBlockState(below).is(Blocks.BEDROCK)) {
				return true;
			}

			if (mediumAt(level, below) != Medium.SOLID) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Height of the enclosure check, counted upward from the victim's feet.
	 */
	private static final int TOMB_HEIGHT = 4;

	/**
	 * How many of the surrounding cells must be filled for the victim to count as entombed.
	 *
	 * <p>Fluid counts towards this, so a flooded tomb is still a tomb. The threshold is a
	 * density, not a demand that every cell be packed: it takes 34 filled cells anywhere in
	 * the volume, which a slab or a trapdoor cannot fake its way past.
	 */
	private static final int TOMB_FILLED_CELLS = 34;

	/**
	 * Whether the victim is sealed into a space barely larger than themselves.
	 *
	 * <p>Three things must hold:
	 *
	 * <ul>
	 *   <li>They are standing on something solid -- not swimming, not treading fluid.</li>
	 *   <li>At least {@link #TOMB_FILLED_CELLS} cells around them are filled, fluid
	 *       included.</li>
	 *   <li>Something solid is directly over their head.</li>
	 * </ul>
	 *
	 * <p>The solid requirements on the floor and ceiling are what keep open water out: a
	 * swimmer is surrounded on all sides, but has neither ground beneath them nor a lid above,
	 * and the drowned summoned around them are answer enough.
	 *
	 * <p>Deliberately independent of {@link #summon}: a wave can fail for reasons that have
	 * nothing to do with being buried.
	 */
	public static boolean isEnclosed(ServerLevel level, LivingEntity victim) {
		return isEnclosedAt(level, CalamityRide.judgementPos(victim));
	}

	/**
	 * As {@link #isEnclosed}, from an explicit position.
	 */
	public static boolean isEnclosedAt(ServerLevel level, BlockPos origin) {
		boolean floor = mediumAt(level, origin.below()) == Medium.SOLID;
		boolean lid = mediumAt(level, origin.above(2)) == Medium.SOLID;

		int filled = 0;

		if (floor && lid) {
			for (int dy = 0; dy < TOMB_HEIGHT; dy++) {
				for (int dx = -SPAWN_RADIUS; dx <= SPAWN_RADIUS; dx++) {
					for (int dz = -SPAWN_RADIUS; dz <= SPAWN_RADIUS; dz++) {
						// The two cells the victim themselves occupy.
						if (dx == 0 && dz == 0 && dy <= 1) {
							continue;
						}

						BlockPos pos = new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);

						// Fluid packs a tomb just as well as stone does.
						if (mediumAt(level, pos) != Medium.OPEN) {
							filled++;
						}
					}
				}
			}
		}

		boolean enclosed = floor && lid && filled >= TOMB_FILLED_CELLS;

		TaoYuanMod.LOGGER.debug("Tomb check at {}: solidFloor={}, solidLid={}, filled={} (>={} required) -> {}",
				origin, floor, lid, filled, TOMB_FILLED_CELLS, enclosed);

		return enclosed;
	}

	/**
	 * Horizontal radius of the airspace check around the victim, giving a 5x5 footprint.
	 */
	private static final int AIRSPACE_RADIUS = 2;

	/**
	 * Height of the airspace check, counted upward from the victim's own feet.
	 *
	 * <p>Starting at the victim's Y rather than below it is what lets the check pass at all:
	 * anyone standing anywhere has a floor beneath their feet, so including that layer would
	 * make the condition unsatisfiable. Layers Y, Y+1 and Y+2 describe the space the victim
	 * occupies and the space immediately above them.
	 */
	private static final int AIRSPACE_HEIGHT = 3;

	/**
	 * Blocks of standing ground beneath the victim that disqualify the sky terror.
	 *
	 * <p>Someone perched on a thin platform or a pillar is exposed; someone standing on
	 * genuinely deep ground is not. Twenty is enough that maintaining it while moving means
	 * laying twenty blocks per step, which is its own answer.
	 */
	private static final int GROUND_DEPTH_THRESHOLD = 20;

	/**
	 * How far the ground scan reaches past a gap before giving up.
	 *
	 * <p>Natural terrain is riddled with cavities -- lush caves, aquifers, ravines -- and a
	 * scan that stopped at the first one would read solid ground as a thin ledge. Each gap
	 * extends the scan by this much instead, repeatedly, so only genuinely hollow ground
	 * falls short of the threshold.
	 */
	private static final int GROUND_GAP_TOLERANCE = 3;

	/**
	 * Depth credited when the scan reaches bedrock.
	 *
	 * <p>At the bottom of the world there is nothing left to count, so a player standing on
	 * the floor would otherwise register as hovering over nothing. Bedrock is treated as
	 * unbounded ground instead.
	 */
	private static final int BEDROCK_DEPTH = 100;

	/**
	 * Whether the victim is exposed in open air.
	 *
	 * <p>Four things must hold at once: nothing overhead, nothing in the surrounding
	 * airspace, no fluid in that airspace, and less than {@link #GROUND_DEPTH_THRESHOLD}
	 * blocks of ground underfoot. Together they describe someone hanging in the sky rather
	 * than merely standing somewhere with a view.
	 */
	public static boolean isExposedToSky(ServerLevel level, LivingEntity victim) {
		return isExposedToSkyAt(level, CalamityRide.judgementPos(victim));
	}

	/**
	 * As {@link #isExposedToSky}, from an explicit position.
	 */
	public static boolean isExposedToSkyAt(ServerLevel level, BlockPos origin) {
		boolean ceiling = hasCeiling(level, origin);
		boolean airspace = isAirspaceClear(level, origin);
		int depth = groundDepthBelow(level, origin);

		// Logged per sub-condition because a failed sky check is otherwise indistinguishable
		// from a failed enclosure check, and the four conditions fail for very different
		// reasons.
		TaoYuanMod.LOGGER.debug("Sky check at {}: ceiling={}, airspaceClear={}, groundDepth={} (<{} required), roofedDimension={}",
				origin, ceiling, airspace, depth, GROUND_DEPTH_THRESHOLD,
				level.dimensionType().hasCeiling());

		return !ceiling && airspace && depth < GROUND_DEPTH_THRESHOLD;
	}

	/**
	 * How far up a ceiling is looked for in dimensions that have one of their own.
	 *
	 * <p>The Nether is roofed with bedrock ({@code DimensionTypes} NETHER sets
	 * {@code hasCeiling}), so scanning to the top of the world would always find something
	 * overhead and the sky terror could never apply there at all. Limiting the scan asks the
	 * question that actually matters -- is there anything above the victim nearby -- rather
	 * than whether the dimension happens to be closed.
	 *
	 * <p>Matched to {@link #GROUND_DEPTH_THRESHOLD} so the space above is judged on the same
	 * scale as the ground below.
	 */
	private static final int CEILING_SCAN_LIMIT = 20;

	/**
	 * Whether anything covers the victim.
	 *
	 * <p>In open dimensions the whole column up to the build limit is checked, so any block
	 * overhead counts as cover. In roofed ones only {@link #CEILING_SCAN_LIMIT} blocks are:
	 * the Nether's bedrock roof would otherwise mean the sky terror could never apply there,
	 * since something is always overhead eventually.
	 *
	 * <p>Scans blocks directly rather than consulting {@code canSeeSky}, which measures sky
	 * <em>light</em> (BlockAndTintGetter.java:22) and is therefore always false in the Nether
	 * -- where {@code SKY_LIGHT_FACTOR} is zero (DimensionTypes.java:79) -- and is also
	 * reduced by leaves, glass and water.
	 */
	private static boolean hasCeiling(ServerLevel level, BlockPos origin) {
		int start = origin.getY() + AIRSPACE_HEIGHT;
		int worldTop = level.getMaxY();

		int limit = level.dimensionType().hasCeiling()
				? Math.min(start + CEILING_SCAN_LIMIT - 1, worldTop)
				: worldTop;

		for (int y = start; y <= limit; y++) {
			if (mediumAt(level, new BlockPos(origin.getX(), y, origin.getZ())) != Medium.OPEN) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether the 5x5x3 box rooted at the victim's feet is free of blocks and fluid alike.
	 *
	 * <p>The victim's own cell is skipped; everything else, including the ring at foot level,
	 * must be open. A railing, a wall or a pool of water anywhere in that volume means the
	 * victim is not hanging in open air.
	 */
	private static boolean isAirspaceClear(ServerLevel level, BlockPos origin) {
		for (int dy = 0; dy < AIRSPACE_HEIGHT; dy++) {
			for (int dx = -AIRSPACE_RADIUS; dx <= AIRSPACE_RADIUS; dx++) {
				for (int dz = -AIRSPACE_RADIUS; dz <= AIRSPACE_RADIUS; dz++) {
					// The cell the victim stands in is theirs to occupy.
					if (dx == 0 && dz == 0 && dy == 0) {
						continue;
					}

					BlockPos pos = new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);

					if (mediumAt(level, pos) != Medium.OPEN) {
						return false;
					}
				}
			}
		}

		return true;
	}

	/**
	 * Counts the blocks of ground directly beneath the victim.
	 *
	 * <p>Only solid cells are counted; fluid and open air are not. A short gap does not end
	 * the scan -- up to {@link #GROUND_GAP_TOLERANCE} consecutive empty cells are stepped
	 * over, so a lush cave or an aquifer under otherwise solid ground still reads as solid
	 * ground. A longer run of emptiness does end it: at that point there genuinely is
	 * nothing underfoot, and whatever terrain lies further below is not what the victim is
	 * standing on.
	 *
	 * <p>That distinction is the whole point of the counter. Letting the scan run past
	 * arbitrarily deep emptiness would find the world's real surface far below a sky
	 * platform and credit it to the victim, which is exactly the case the sky terror exists
	 * to catch.
	 *
	 * <p>Bedrock short-circuits to {@link #BEDROCK_DEPTH}, so standing on the floor of the
	 * world never reads as hovering over nothing.
	 *
	 * <p>Stops early once the threshold is cleared, since the caller only compares against
	 * it.
	 */
	private static int groundDepthBelow(ServerLevel level, BlockPos origin) {
		int solid = 0;
		int consecutiveGap = 0;
		int bottom = level.getMinY();

		for (int y = origin.getY() - 1; y >= bottom; y--) {
			BlockPos pos = new BlockPos(origin.getX(), y, origin.getZ());

			if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
				return BEDROCK_DEPTH;
			}

			if (mediumAt(level, pos) == Medium.SOLID) {
				solid++;
				consecutiveGap = 0;

				if (solid >= GROUND_DEPTH_THRESHOLD) {
					return solid;
				}
			} else if (++consecutiveGap > GROUND_GAP_TOLERANCE) {
				// Past this much uninterrupted emptiness the ground has ended for good.
				break;
			}
		}

		return solid;
	}

	/**
	 * Places one mob at a position already known to be viable.
	 *
	 * <p>Mirrors what {@code EntityType#create(ServerLevel, Consumer, BlockPos, ...)} does
	 * internally, minus the vertical adjustment we do not want here.
	 *
	 * @param inFluid when true, skip the liquid half of the obstruction check
	 */
	private static @Nullable Mob spawnAt(ServerLevel level, EntityType<? extends Mob> type, BlockPos pos, RandomSource random, boolean inFluid) {
		Mob mob = type.create(level, EntitySpawnReason.MOB_SUMMONED);

		if (mob == null) {
			return null;
		}

		mob.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
		mob.yHeadRot = mob.getYRot();
		mob.yBodyRot = mob.getYRot();

		// Belt and braces: rejects spots occupied by other entities. For fluid placements the
		// liquid clause has to be dropped, because Mob#checkSpawnObstruction refuses any
		// bounding box containing liquid outright (Mob.java:764) -- which is exactly the
		// placement we are making on purpose here.
		boolean unobstructed = inFluid
				? level.isUnobstructed(mob)
				: mob.checkSpawnObstruction(level);

		if (!unobstructed) {
			mob.discard();
			return null;
		}

		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.MOB_SUMMONED, null);
		level.addFreshEntity(mob);

		return mob;
	}

	/**
	 * Forces every nearby {@link Monster} to target the victim.
	 *
	 * <p>Does nothing on Peaceful: hostile mobs are not supposed to engage the player there,
	 * and vanilla clears combat targets anyway (see {@code TargetingConditions}).
	 *
	 * <p>Called every tick for the duration of the lock, which is what keeps the
	 * aggression from drifting to other players. Once the caller stops invoking this, the
	 * mobs revert to ordinary targeting.
	 */
	public static void lockAggression(ServerLevel level, LivingEntity victim) {
		if (level.getDifficulty() == Difficulty.PEACEFUL) {
			return;
		}

		AABB box = victim.getBoundingBox().inflate(AGGRO_RADIUS);

		for (Monster monster : level.getEntitiesOfClass(Monster.class, box)) {
			if (monster.isAlive() && monster.getTarget() != victim) {
				monster.setTarget(victim);
			}
		}
	}
}
