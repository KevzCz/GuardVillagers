package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.delivery.melee.OrientedBoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class GuardMeleeTargeting {

    private GuardMeleeTargeting() {}

    public static float yawToward(LivingEntity from, LivingEntity to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
    }

    public static float resolveStrikeYaw(LivingEntity caster, @Nullable LivingEntity contextTarget) {
        LivingEntity aim = contextTarget;
        if ((aim == null || !aim.isAlive()) && caster instanceof GuardEntity guard) {
            aim = guard.getTarget();
        }
        if (aim != null && aim.isAlive()) {
            return yawToward(caster, aim);
        }
        return caster.getHeadYaw();
    }

    public static List<LivingEntity> findTargets(
            LivingEntity caster,
            @Nullable Entity focusTarget,
            @Nullable Spell.Delivery.Melee.HitBox hitbox,
            float attackRange,
            float yaw,
            float pitch
    ) {
        double shoulderHeight = caster.getHeight() * 0.15 * caster.getScaleFactor();
        Vec3d origin = caster.getEyePos().subtract(0, shoulderHeight, 0);

        float width = hitbox != null && hitbox.width > 0 ? hitbox.width : 1f;
        float height = hitbox != null && hitbox.height > 0 ? hitbox.height : 1f;
        float length = hitbox != null && hitbox.length > 0 ? hitbox.length : 1f;
        double arc = hitbox != null && hitbox.arc > 0 ? hitbox.arc : 120.0;
        float roll = hitbox != null ? hitbox.roll : 0f;

        Vec3d hitboxSize = new Vec3d(width * attackRange, height * attackRange, length * attackRange);
        OrientedBoundingBox obb = new OrientedBoundingBox(origin, hitboxSize, pitch, yaw, roll);
        if (arc <= 180) {
            obb = obb.offsetAlongAxisZ(hitboxSize.z / 2.0);
        }
        obb.updateVertex();

        double searchRange = attackRange * 2.0 + 1.0;
        Box searchBox = caster.getBoundingBox().expand(searchRange);
        List<LivingEntity> candidates = caster.getWorld().getEntitiesByClass(
                LivingEntity.class,
                searchBox,
                e -> isValidMeleeTarget(caster, e, focusTarget)
        );

        if (focusTarget instanceof LivingEntity livingFocus && livingFocus.isAlive()
                && !candidates.contains(livingFocus) && isValidMeleeTarget(caster, livingFocus, null)) {
            candidates.add(livingFocus);
        }

        Vec3d forward = forwardFromYaw(yaw);
        double cosThreshold = Math.cos(Math.toRadians(arc / 2.0));
        List<LivingEntity> hits = new ArrayList<>();

        for (LivingEntity entity : candidates) {
            if (!obb.intersects(entity.getBoundingBox().expand(entity.getTargetingMargin()))
                    && !obb.contains(entity.getPos().add(0, entity.getHeight() * 0.5, 0))) {
                continue;
            }

            Vec3d toEntity = entity.getPos().add(0, entity.getHeight() * 0.5, 0).subtract(origin);
            if (toEntity.lengthSquared() > (attackRange + 1.0) * (attackRange + 1.0)) {
                continue;
            }

            if (arc < 360 && arc > 0) {
                Vec3d dir = toEntity.normalize();
                if (forward.dotProduct(dir) < cosThreshold) {
                    continue;
                }
            }

            hits.add(entity);
        }

        return hits;
    }

    private static boolean isValidMeleeTarget(LivingEntity caster, LivingEntity entity, @Nullable Entity focusTarget) {
        if (entity == caster || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        if (entity == focusTarget) {
            return true;
        }
        if (caster instanceof GuardEntity guard) {
            if (entity instanceof VillagerEntity || entity instanceof GuardEntity || entity instanceof IronGolemEntity) {
                return false;
            }
            if (entity == guard.getOwner()) {
                return false;
            }
            return guard.canTarget(entity);
        }
        return entity.isAttackable();
    }

    private static Vec3d forwardFromYaw(float yaw) {
        float rad = yaw * 0.017453292F;
        return new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad));
    }
}
