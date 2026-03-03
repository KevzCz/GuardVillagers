package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public class ConditionalSpellGoal {
    private final GuardEntity guard;
    private final Map<Identifier, SpellCondition> spellConditions = new HashMap<>();

    public ConditionalSpellGoal(GuardEntity guard) {
        this.guard = guard;
    }

    public void registerHealthThreshold(String spellId, float threshold) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.healthBelow(threshold));
        }
    }

    public void registerHealthRange(String spellId, float min, float max) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.healthBetween(min, max));
        }
    }

    public void registerDistanceToTarget(String spellId, double minDistance, double maxDistance) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.targetDistance(minDistance, maxDistance));
        }
    }

    public void registerMinDistanceToTarget(String spellId, double minDistance) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.targetMinDistance(minDistance));
        }
    }

    public void registerMaxDistanceToTarget(String spellId, double maxDistance) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.targetMaxDistance(maxDistance));
        }
    }

    public void registerNearbyEnemyCount(String spellId, int minCount) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.nearbyEnemies(minCount));
        }
    }

    public void registerNearbyAllyCount(String spellId, int minCount) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.nearbyAllies(minCount));
        }
    }

    public void registerHasStatusEffect(String spellId, String effectId) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.hasStatusEffect(effectId));
        }
    }

    public void registerMissingStatusEffect(String spellId, String effectId) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.missingStatusEffect(effectId));
        }
    }

    public void registerCustom(String spellId, Predicate<GuardEntity> condition) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.custom(condition));
        }
    }

    public void registerAlways(String spellId) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.always());
        }
    }

    public void registerCombined(String spellId, SpellCondition... conditions) {
        Identifier id = Identifier.tryParse(spellId);
        if (id != null) {
            spellConditions.put(id, SpellCondition.and(conditions));
        }
    }

    public boolean canCastSpell(Identifier spellId) {
        SpellCondition condition = spellConditions.get(spellId);
        if (condition == null) {
            return true;
        }
        return condition.test(guard);
    }

    public boolean hasConditionRegistered(Identifier spellId) {
        return spellConditions.containsKey(spellId);
    }

    public void clearConditions() {
        spellConditions.clear();
    }

    public static class SpellCondition {
        private final Predicate<GuardEntity> predicate;

        private SpellCondition(Predicate<GuardEntity> predicate) {
            this.predicate = predicate;
        }

        public boolean test(GuardEntity guard) {
            return predicate.test(guard);
        }

        public static SpellCondition healthBelow(float threshold) {
            return new SpellCondition(guard ->
                    (guard.getHealth() / guard.getMaxHealth()) <= threshold
            );
        }

        public static SpellCondition healthBetween(float min, float max) {
            return new SpellCondition(guard -> {
                float ratio = guard.getHealth() / guard.getMaxHealth();
                return ratio >= min && ratio <= max;
            });
        }

        public static SpellCondition targetDistance(double min, double max) {
            return new SpellCondition(guard -> {
                LivingEntity target = guard.getTarget();
                if (target == null) return false;
                double dist = guard.squaredDistanceTo(target);
                return dist >= (min * min) && dist <= (max * max);
            });
        }

        public static SpellCondition targetMinDistance(double minDistance) {
            return new SpellCondition(guard -> {
                LivingEntity target = guard.getTarget();
                if (target == null) return false;
                return guard.squaredDistanceTo(target) >= (minDistance * minDistance);
            });
        }

        public static SpellCondition targetMaxDistance(double maxDistance) {
            return new SpellCondition(guard -> {
                LivingEntity target = guard.getTarget();
                if (target == null) return false;
                return guard.squaredDistanceTo(target) <= (maxDistance * maxDistance);
            });
        }

        public static SpellCondition nearbyEnemies(int minCount) {
            return new SpellCondition(guard -> {
                long count = guard.getWorld().getEntitiesByClass(
                        LivingEntity.class,
                        guard.getBoundingBox().expand(8.0),
                        e -> e != guard && e.isAlive() && guard.canTarget(e)
                ).size();
                return count >= minCount;
            });
        }

        public static SpellCondition nearbyAllies(int minCount) {
            return new SpellCondition(guard -> {
                long count = guard.getWorld().getEntitiesByClass(
                        LivingEntity.class,
                        guard.getBoundingBox().expand(8.0),
                        e -> e != guard && e.isAlive() && !guard.canTarget(e)
                ).size();
                return count >= minCount;
            });
        }

        public static SpellCondition hasStatusEffect(String effectId) {
            return new SpellCondition(guard -> {
                net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(effectId);
                if (id == null) return false;
                var effect = net.minecraft.registry.Registries.STATUS_EFFECT.get(id);
                return guard.hasStatusEffect(net.minecraft.registry.Registries.STATUS_EFFECT.getEntry(effect));
            });
        }

        public static SpellCondition missingStatusEffect(String effectId) {
            return new SpellCondition(guard -> {
                net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(effectId);
                if (id == null) return true;
                var effect = net.minecraft.registry.Registries.STATUS_EFFECT.get(id);
                return !guard.hasStatusEffect(net.minecraft.registry.Registries.STATUS_EFFECT.getEntry(effect));
            });
        }

        public static SpellCondition custom(Predicate<GuardEntity> predicate) {
            return new SpellCondition(predicate);
        }

        public static SpellCondition always() {
            return new SpellCondition(guard -> true);
        }

        public static SpellCondition and(SpellCondition... conditions) {
            return new SpellCondition(guard -> {
                for (SpellCondition condition : conditions) {
                    if (!condition.test(guard)) return false;
                }
                return true;
            });
        }

        public static SpellCondition or(SpellCondition... conditions) {
            return new SpellCondition(guard -> {
                for (SpellCondition condition : conditions) {
                    if (condition.test(guard)) return true;
                }
                return false;
            });
        }

        public static SpellCondition not(SpellCondition condition) {
            return new SpellCondition(guard -> !condition.test(guard));
        }
    }
}