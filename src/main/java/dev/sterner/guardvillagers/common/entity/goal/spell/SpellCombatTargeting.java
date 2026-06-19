package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardTargeting;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellHelper;
import net.spell_engine.internals.target.EntityRelations;
import net.spell_engine.internals.target.SpellTarget;
import net.spell_engine.utils.TargetHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class SpellCombatTargeting {

    static final float DEFAULT_BEAM_RANGE = 32.0f;
    private static final double IMPACT_LERP = 0.01;

    private SpellCombatTargeting() {}

    

    @Nullable
    public static LivingEntity resolveHostileCastTarget(GuardEntity guard, float range) {
        double rangeSq = range * range;
        LivingEntity current = guard.getTarget();
        if (GuardTargeting.isVisibleHostileTarget(guard, current, rangeSq)) {
            return current;
        }

        Box searchBox = guard.getBoundingBox().expand(range);
        LivingEntity nearest = null;
        double nearestSq = rangeSq;
        for (LivingEntity entity : guard.getWorld().getEntitiesByClass(LivingEntity.class, searchBox,
                candidate -> GuardTargeting.isVisibleHostileTarget(guard, candidate, rangeSq))) {
            double distSq = guard.squaredDistanceTo(entity);
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = entity;
            }
        }

        return nearest;
    }

    static float scaledRange(LivingEntity caster, RegistryEntry<Spell> entry, float fallbackIfZero) {
        float range = resolveBaseRange(caster, entry);
        if (range <= 0) {
            range = fallbackIfZero;
        }
        return range * caster.getScale();
    }

    static float resolveBaseRange(LivingEntity caster, RegistryEntry<Spell> entry) {
        if (caster instanceof GuardEntity guard) {
            return guard.getSpellManager().getAugmentedRange(entry);
        }
        return entry.value().range;
    }

    static Vec3d casterCenter(LivingEntity caster) {
        return caster.getPos().add(0, caster.getHeight() / 2.0, 0);
    }

    static Vec3d impactPosition(LivingEntity target, Vec3d casterCenter) {
        return target.getPos()
                .add(0, target.getHeight() / 2.0, 0)
                .lerp(casterCenter, IMPACT_LERP);
    }

    static Spell.Target.Area targetArea(Spell spell) {
        return spell.target != null && spell.target.area != null
                ? spell.target.area
                : new Spell.Target.Area();
    }

    static float distanceMultiplier(Spell.Target.Area area, double squaredRange, Vec3d center, LivingEntity target) {
        if (area.distance_dropoff != Spell.Target.Area.DropoffCurve.SQUARED) {
            return 1.0f;
        }
        float mult = (float) ((squaredRange - target.squaredDistanceTo(center)) / squaredRange);
        return Math.max(mult, 0.0f);
    }

    static Predicate<Entity> combatFilter(LivingEntity caster) {
        return new CombatTargetFilter(caster);
    }

    
    static List<Entity> resolveSpellTargets(SpellContext context) {
        LivingEntity caster = context.caster();
        Spell spell = context.spell();
        if (spell.target == null || spell.impacts == null) {
            return List.of();
        }

        float range = scaledRange(caster, context.entry(), spell.range);
        SpellTarget.FocusMode focusMode = SpellHelper.focusMode(spell);
        Predicate<Entity> selectionPredicate = target -> {
            var deliveryIntent = SpellHelper.deliveryIntent(spell);
            boolean intentAllows = deliveryIntent.isPresent()
                    && EntityRelations.actionAllowed(focusMode, deliveryIntent.get(), caster, target);
            for (Spell.Impact impact : spell.impacts) {
                SpellTarget.Intent intent = SpellHelper.impactIntent(impact.action);
                boolean allowed = impact.action.apply_to_caster
                        ? target == caster
                        : EntityRelations.actionAllowed(focusMode, intent, caster, target);
                intentAllows = intentAllows || allowed;
            }
            return intentAllows;
        };

        return switch (spell.target.type) {
            case AIM -> {
                if (caster instanceof GuardEntity guard) {
                    LivingEntity combatTarget = guard.getTarget();
                    if (combatTarget != null && combatTarget.isAlive()
                            && selectionPredicate.test(combatTarget)
                            && guard.squaredDistanceTo(combatTarget) <= (double) range * range) {
                        yield List.of(combatTarget);
                    }
                }
                Entity hit = TargetHelper.targetFromRaycast(caster, range, selectionPredicate);
                yield hit != null ? List.of(hit) : List.of();
            }
            case BEAM -> new ArrayList<>(TargetHelper.targetsFromRaycast(caster, range, selectionPredicate));
            case AREA -> {
                var targets = new ArrayList<>(TargetHelper.targetsFromArea(
                        caster, range, targetArea(spell), selectionPredicate));
                if (spell.target.area != null && spell.target.area.include_caster) {
                    targets.add(caster);
                }
                yield targets;
            }
            case CASTER -> List.of(caster);
            default -> List.of();
        };
    }

    static int deliverImpacts(SpellContext context,
                              List<Entity> candidates,
                              @Nullable Spell.Target.Area area,
                              Vec3d dropoffCenter,
                              double squaredRange) {
        LivingEntity caster = context.caster();
        World world = caster.getWorld();
        Vec3d center = casterCenter(caster);
        int hits = 0;

        for (Entity entity : candidates) {
            if (!(entity instanceof LivingEntity target)) {
                continue;
            }

            float distanceMult = area != null
                    ? distanceMultiplier(area, squaredRange, dropoffCenter, target)
                    : 1.0f;
            SpellHelper.ImpactContext impactCtx = context.impactContext()
                    .position(impactPosition(target, center))
                    .distance(distanceMult);

            if (SpellDelivery.performImpacts(
                    world,
                    caster,
                    target,
                    target,
                    context.entry(),
                    context.getImpacts(),
                    impactCtx
            )) {
                hits++;
                SpellDelivery.triggerImpactFollowUps(caster, target, context);
            }
        }
        return hits;
    }

    static void broadcastHitSummary(GuardEntity guard, World world, String spellPath,
                                    String deliveryLabel, int hits, int candidates, float range) {
        if (world.isClient()) {
            return;
        }
        GuardDebugManager.broadcast(guard,
                "🎯 " + spellPath + " " + deliveryLabel + " → " + hits + "/" + candidates
                        + " hit (r=" + String.format("%.1f", range) + ")",
                hits > 0 ? Formatting.GREEN : Formatting.YELLOW);
    }

    static String meleeAttackLabel(GuardEntity guard, Spell.Delivery.Melee.Attack attack) {
        if (attack.id != null && !attack.id.isEmpty()) {
            return attack.id;
        }
        if (attack.animation != null) {
            String animId = GuardCastVisuals.resolveAnimationId(guard, attack.animation);
            if (animId != null) {
                int slash = animId.lastIndexOf('/');
                return slash >= 0 ? animId.substring(slash + 1) : animId;
            }
        }
        return "swing";
    }

    private record CombatTargetFilter(LivingEntity caster) implements Predicate<Entity> {
        @Override
        public boolean test(Entity entity) {
            if (!(entity instanceof LivingEntity living)) {
                return false;
            }
            if (living == caster || !living.isAlive() || living.isSpectator()) {
                return false;
            }
            if (caster instanceof GuardEntity guard) {
                return guard.canTarget(living) && caster.canSee(living);
            }
            return living.isAttackable() && caster.canSee(living);
        }
    }
}
