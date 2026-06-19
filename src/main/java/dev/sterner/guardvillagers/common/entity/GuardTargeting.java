package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public final class GuardTargeting {

    private GuardTargeting() {}

    public static boolean isBlacklisted(LivingEntity target) {
        return GuardVillagersConfig.mobBlackList.contains(target.getSavedEntityId());
    }

    public static boolean isOwnerPet(GuardEntity guard, LivingEntity target) {
        if (!guard.isHired()) return false;
        java.util.UUID ownerUuid = guard.getOwnerUuid();
        if (ownerUuid == null) return false;
        return target instanceof TameableEntity tameable
                && tameable.isTamed()
                && ownerUuid.equals(tameable.getOwnerUuid());
    }

    public static boolean isProtectedAlly(GuardEntity guard, LivingEntity target) {
        return target.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE)
                || guard.isOwner(target)
                || guard.isSpellSummon(target)
                || isOwnerPet(guard, target)
                || target instanceof VillagerEntity
                || target instanceof IronGolemEntity
                || target instanceof GuardEntity;
    }

    
    public static boolean isHostileMob(LivingEntity target) {
        return target instanceof Monster;
    }

    

    public static boolean isInAttackMobsList(GuardEntity guard, LivingEntity target) {
        if (guard.getAttackMobs().isEmpty()) return false;
        Identifier entityId = Registries.ENTITY_TYPE.getId(target.getType());
        return guard.getAttackMobs().contains(entityId);
    }

    public static boolean isRetaliationTarget(GuardEntity guard, LivingEntity target) {
        return target == guard.getAttacker() || target == guard.getTarget() && guard.getAttacker() != null;
    }

    public static boolean passesGuardFilters(GuardEntity guard, LivingEntity target) {
        if (isBlacklisted(target) || isProtectedAlly(guard, target)) {
            return false;
        }
        if (isOwnerCombatTarget(guard, target)) {
            return true;
        }
        if (target instanceof PlayerEntity) {
            return guard.shouldAttackPlayers() || isRetaliationTarget(guard, target);
        }
        if (target instanceof MobEntity) {
            return isHostileMob(target) || isInAttackMobsList(guard, target) || isRetaliationTarget(guard, target);
        }
        return true;
    }

    public static boolean isOwnerCombatTarget(GuardEntity guard, LivingEntity target) {
        if (!guard.isHired()) return false;
        LivingEntity owner = guard.getOwner();
        if (owner == null) return false;
        return target == owner.getAttacker() || target == owner.getAttacking();
    }

    
    public static boolean isProactiveHuntTarget(LivingEntity entity) {
        return GuardVillagersConfig.attackAllMobs
                && entity instanceof MobEntity
                && isHostileMob(entity)
                && !isBlacklisted(entity);
    }

    
    public static boolean isVisibleHostileTarget(GuardEntity guard, @Nullable LivingEntity target, double rangeSq) {
        if (target == null || !target.isAlive() || target.isSpectator()) {
            return false;
        }
        boolean validTarget = isHostileMob(target)
                || isInAttackMobsList(guard, target)
                || (target instanceof PlayerEntity && !isProtectedAlly(guard, target)
                        && (guard.shouldAttackPlayers() || isRetaliationTarget(guard, target)));
        if (!validTarget) {
            return false;
        }
        return guard.canTarget(target)
                && guard.getVisibilityCache().canSee(target)
                && guard.squaredDistanceTo(target) <= rangeSq;
    }
}
