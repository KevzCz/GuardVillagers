package dev.sterner.guardvillagers.common.contract;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.special.GuardHiring;
import dev.sterner.guardvillagers.common.special.SpecialGuardDefinition;
import dev.sterner.guardvillagers.common.special.SpecialGuardSpawner;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ContractItem extends Item {

    public static final long TICKS_PER_DAY = 24000L;
    private static final long TICKS_PER_MINUTE = 1200L;
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int SUMMON_DISTANCE = 2;
    private static final int SUMMON_SEARCH_RADIUS = 3;
    private static final int SUMMON_PARTICLES = 20;
    private static final double PARTICLE_SPREAD = 0.4D;
    private static final double PARTICLE_HEIGHT = 1.0D;
    private static final float SOUND_VOLUME = 1.0F;
    private static final float SOUND_PITCH = 1.0F;
    private static final float HALF_TURN = 180.0F;
    private static final double BLOCK_CENTER = 0.5D;

    private static final String KEY = "item.guardvillagers.contract.";
    private static final String MSG_DISABLED = KEY + "disabled";
    private static final String MSG_BLANK = KEY + "blank";
    private static final String MSG_WRONG_PLACE = KEY + "wrong_place";
    private static final String MSG_SPENT = KEY + "spent";
    private static final String MSG_COOLING = KEY + "cooling";
    private static final String MSG_GLOBAL_CAP = KEY + "global_cap";
    private static final String MSG_RECALLED = KEY + "recalled";
    private static final String MSG_INVALID = KEY + "invalid";
    private static final String MSG_NO_ROOM = KEY + "no_room";
    private static final String MSG_COST = KEY + "cost";
    private static final String MSG_SUMMONED = KEY + "summoned";
    private static final String TIP_GUARD = KEY + "tooltip.guard";
    private static final String TIP_TERM = KEY + "tooltip.term";
    private static final String TIP_COST = KEY + "tooltip.cost";
    private static final String TIP_FREE = KEY + "tooltip.free";
    private static final String TIP_USES = KEY + "tooltip.uses";
    private static final String TIP_UNLIMITED = KEY + "tooltip.unlimited";
    private static final String TIP_MAX_ACTIVE = KEY + "tooltip.max_active";
    private static final String TIP_COOLDOWN = KEY + "tooltip.cooldown";
    private static final String TIP_ACTIVE = KEY + "tooltip.active";
    private static final String TIP_SPENT = KEY + "tooltip.spent";
    private static final String TIP_BLANK = KEY + "tooltip.blank";

    public ContractItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!(world instanceof ServerWorld serverWorld) || !(user instanceof ServerPlayerEntity player)) {
            return TypedActionResult.success(stack, world.isClient());
        }
        if (!GuardVillagersConfig.contractsEnabled) {
            return fail(player, stack, Text.translatable(MSG_DISABLED));
        }
        ContractTerms terms = stack.get(GuardContractComponents.CONTRACT_TERMS);
        if (terms == null) {
            return fail(player, stack, Text.translatable(MSG_BLANK));
        }
        if (!terms.dimensions().isEmpty() && !terms.dimensions().contains(serverWorld.getRegistryKey().getValue())) {
            return fail(player, stack, Text.translatable(MSG_WRONG_PLACE));
        }

        ContractProgress progress = stack.getOrDefault(GuardContractComponents.CONTRACT_PROGRESS, ContractProgress.fresh());
        SummonedGuardState state = SummonedGuardState.get(serverWorld.getServer());
        long now = serverWorld.getTime();
        List<UUID> ownGuards = state.guardsFor(progress.contractId(), now);

        if (ownGuards.size() >= Math.max(1, terms.maxActive())) {
            int recalled = recall(serverWorld, player, ownGuards);
            player.sendMessage(Text.translatable(MSG_RECALLED, recalled), true);
            return TypedActionResult.success(stack);
        }
        if (progress.isSpent(terms)) {
            return fail(player, stack, Text.translatable(MSG_SPENT));
        }
        if (now < progress.cooldownUntil()) {
            long minutes = Math.max(1L, Math.ceilDiv(progress.cooldownUntil() - now, TICKS_PER_MINUTE));
            return fail(player, stack, Text.translatable(MSG_COOLING, minutes));
        }
        if (state.activeFor(player.getUuid(), now) >= GuardVillagersConfig.maxSummonedGuards) {
            return fail(player, stack, Text.translatable(MSG_GLOBAL_CAP, GuardVillagersConfig.maxSummonedGuards));
        }
        Optional<SpecialGuardDefinition> definition = terms.resolve();
        if (definition.isEmpty()) {
            return fail(player, stack, Text.translatable(MSG_INVALID));
        }
        ContractCost cost = terms.cost();
        if (!cost.isFree() && !GuardHiring.canAffordItems(player, cost.items(), cost.count())) {
            return fail(player, stack, Text.translatable(MSG_COST, cost.count(), GuardHiring.describe(cost.items())));
        }
        BlockPos spawnPos = findSpawnPos(serverWorld, player);
        if (spawnPos == null) {
            return fail(player, stack, Text.translatable(MSG_NO_ROOM));
        }
        GuardEntity guard = SpecialGuardSpawner.spawn(serverWorld, definition.get(), spawnPos,
                player.getYaw() + HALF_TURN, SpawnReason.MOB_SUMMONED);
        if (guard == null) {
            return fail(player, stack, Text.translatable(MSG_INVALID));
        }
        if (!cost.isFree()) {
            GuardHiring.payItems(player, cost.items(), cost.count());
        }

        long expiresAt = now + terms.effectiveTermDays() * TICKS_PER_DAY;
        terms.guard().ifRight(json -> {
            if (json.isJsonObject()) {
                guard.setInlineDefinitionSource(json.getAsJsonObject());
            }
        });
        guard.hireBy(player);
        guard.markSummoned(player.getUuid(), progress.contractId(), expiresAt);
        state.add(new SummonedGuardState.Summon(guard.getUuid(), player.getUuid(), progress.contractId(), expiresAt));

        long cooldownUntil = terms.cooldownDays() > 0 ? now + terms.cooldownDays() * TICKS_PER_DAY : 0L;
        stack.set(GuardContractComponents.CONTRACT_PROGRESS, progress.afterSummon(cooldownUntil, ownGuards.size() + 1));

        serverWorld.spawnParticles(ParticleTypes.ENCHANT, guard.getX(), guard.getY() + PARTICLE_HEIGHT, guard.getZ(),
                SUMMON_PARTICLES, PARTICLE_SPREAD, PARTICLE_HEIGHT, PARTICLE_SPREAD, 0.0D);
        serverWorld.playSound(null, guard.getBlockPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS,
                SOUND_VOLUME, SOUND_PITCH);
        player.sendMessage(Text.translatable(MSG_SUMMONED, terms.displayName(), terms.effectiveTermDays()), true);
        return TypedActionResult.success(stack);
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        if (!(world instanceof ServerWorld serverWorld) || !(entity instanceof PlayerEntity)
                || world.getTime() % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        ContractProgress progress = stack.get(GuardContractComponents.CONTRACT_PROGRESS);
        if (progress == null) {
            return;
        }
        int active = SummonedGuardState.get(serverWorld.getServer()).guardsFor(progress.contractId(), world.getTime()).size();
        if (active != progress.active()) {
            stack.set(GuardContractComponents.CONTRACT_PROGRESS, progress.withActive(active));
        }
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        ContractTerms terms = stack.get(GuardContractComponents.CONTRACT_TERMS);
        if (terms == null) {
            tooltip.add(Text.translatable(TIP_BLANK).formatted(Formatting.GRAY));
            return;
        }
        ContractProgress progress = stack.get(GuardContractComponents.CONTRACT_PROGRESS);
        tooltip.add(Text.translatable(TIP_GUARD, terms.displayName()).formatted(Formatting.GOLD));
        tooltip.add(Text.translatable(TIP_TERM, terms.effectiveTermDays()).formatted(Formatting.GRAY));
        ContractCost cost = terms.cost();
        tooltip.add(cost.isFree()
                ? Text.translatable(TIP_FREE).formatted(Formatting.GRAY)
                : Text.translatable(TIP_COST, cost.count(), GuardHiring.describe(cost.items())).formatted(Formatting.GRAY));
        int spent = progress == null ? 0 : progress.usesSpent();
        tooltip.add(terms.hasUnlimitedUses()
                ? Text.translatable(TIP_UNLIMITED).formatted(Formatting.GRAY)
                : Text.translatable(TIP_USES, Math.max(0, terms.uses() - spent), terms.uses()).formatted(Formatting.GRAY));
        tooltip.add(Text.translatable(TIP_MAX_ACTIVE, Math.max(1, terms.maxActive())).formatted(Formatting.GRAY));
        if (terms.cooldownDays() > 0) {
            tooltip.add(Text.translatable(TIP_COOLDOWN, terms.cooldownDays()).formatted(Formatting.GRAY));
        }
        if (progress != null && progress.active() > 0) {
            tooltip.add(Text.translatable(TIP_ACTIVE, progress.active(), Math.max(1, terms.maxActive())).formatted(Formatting.GREEN));
        } else if (progress != null && progress.isSpent(terms)) {
            tooltip.add(Text.translatable(TIP_SPENT).formatted(Formatting.DARK_GRAY));
        }
    }

    private static TypedActionResult<ItemStack> fail(PlayerEntity player, ItemStack stack, Text message) {
        player.sendMessage(message, true);
        return TypedActionResult.fail(stack);
    }

    private static int recall(ServerWorld world, PlayerEntity player, List<UUID> guards) {
        int recalled = 0;
        for (UUID id : guards) {
            if (world.getEntity(id) instanceof GuardEntity guard && guard.isAlive()) {
                BlockPos pos = findSpawnPos(world, player);
                if (pos == null) {
                    continue;
                }
                guard.getNavigation().stop();
                guard.refreshPositionAndAngles(pos.getX() + BLOCK_CENTER, pos.getY(), pos.getZ() + BLOCK_CENTER, guard.getYaw(), guard.getPitch());
                recalled++;
            }
        }
        return recalled;
    }

    @Nullable
    private static BlockPos findSpawnPos(ServerWorld world, PlayerEntity player) {
        Direction facing = player.getHorizontalFacing();
        BlockPos ahead = player.getBlockPos().offset(facing, SUMMON_DISTANCE);
        if (fits(world, ahead)) {
            return ahead;
        }
        for (BlockPos candidate : BlockPos.iterateOutwards(player.getBlockPos(), SUMMON_SEARCH_RADIUS, 1, SUMMON_SEARCH_RADIUS)) {
            if (fits(world, candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static boolean fits(ServerWorld world, BlockPos pos) {
        Box box = GuardVillagers.GUARD_VILLAGER.getDimensions().getBoxAt(pos.getX() + BLOCK_CENTER, pos.getY(), pos.getZ() + BLOCK_CENTER);
        return world.isSpaceEmpty(box) && world.getBlockState(pos.down()).isSolidBlock(world, pos.down());
    }
}
