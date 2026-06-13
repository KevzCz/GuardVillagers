package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public final class SpellbladeCombatHelper {

    public static final float BLADE_RANGE = 3.25F;
    public static final float MELEE_SPELL_RANGE = 6.5F;
    public static final float MAGIC_CLOSE_CAST = 4.5F;
    public static final float MAGIC_COMFORT_MIN = 6.0F;
    public static final float MAGIC_COMFORT_MAX = 14.0F;
    public static final float MAGIC_MAX_RANGE = 18.0F;
    public static final float MELEE_PURSUE_RANGE = 12.0F;

    private SpellbladeCombatHelper() {}

    public enum Posture {
        MELEE_FOCUS,
        MAGIC_FOCUS
    }

    public enum Action {
        REPOSITION,
        MELEE_SPELL,
        MAGIC_SPELL,
        MELEE_DUEL,
        OPEN_FOR_MAGIC,
        PURSUE,
        WAIT
    }

    public record Plan(Action action, @Nullable GuardSpellManager.CategorizedSpell spell) {}

    public static boolean isActive(GuardEntity guard) {
        return GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack());
    }

    public static boolean inCombat(GuardEntity guard) {
        LivingEntity target = guard.getTarget();
        return target != null && target.isAlive();
    }

    public static Plan choosePlan(GuardEntity guard, LivingEntity target, Posture posture) {
        if (target == null || !target.isAlive()) {
            return new Plan(Action.WAIT, null);
        }
        if (friendlyInLineOfSight(guard)) {
            return new Plan(Action.REPOSITION, null);
        }

        Optional<GuardSpellManager.CategorizedSpell> melee = getMeleeSpell(guard);
        Optional<GuardSpellManager.CategorizedSpell> magic = getMagicSpell(guard, target);
        float dist = guard.distanceTo(target);

        if (posture == Posture.MAGIC_FOCUS && magic.isPresent()) {
            if (!guard.getVisibilityCache().canSee(target)) {
                return new Plan(Action.PURSUE, magic.get());
            }
            float range = magicRange(magic.get());
            if (dist > range + 1.0F) {
                return new Plan(Action.PURSUE, magic.get());
            }
            if (dist < MAGIC_COMFORT_MIN) {
                return new Plan(Action.OPEN_FOR_MAGIC, magic.get());
            }
            return new Plan(Action.MAGIC_SPELL, magic.get());
        }

        if (melee.isPresent() && inMeleeSpellRange(guard, target, melee.get())) {
            return new Plan(Action.MELEE_SPELL, melee.get());
        }

        if (melee.isPresent() && dist <= MELEE_PURSUE_RANGE) {
            return new Plan(Action.PURSUE, melee.get());
        }

        if (dist <= BLADE_RANGE + 0.5F) {
            return new Plan(Action.MELEE_DUEL, null);
        }

        return new Plan(Action.PURSUE, null);
    }

    public static Action movementFor(Posture posture, ModeHint mode, boolean inCastPhase, boolean magicReady) {
        if (inCastPhase) {
            return mode == ModeHint.MAGIC_CAST ? Action.MAGIC_SPELL : Action.MELEE_SPELL;
        }
        return switch (mode) {
            case MAGIC_CAST -> Action.MAGIC_SPELL;
            case MELEE_CAST -> Action.MELEE_SPELL;
            case MELEE_DUEL -> Action.MELEE_DUEL;
            case REPOSITION -> Action.REPOSITION;
            case ENGAGE -> posture == Posture.MAGIC_FOCUS && magicReady
                    ? Action.OPEN_FOR_MAGIC
                    : Action.MELEE_DUEL;
        };
    }

    public enum ModeHint {
        ENGAGE,
        MELEE_CAST,
        MAGIC_CAST,
        MELEE_DUEL,
        REPOSITION
    }

    public static Optional<GuardSpellManager.CategorizedSpell> getMeleeSpell(GuardEntity guard) {
        return guard.getSpellManager().getBestCastableSpellbladeMeleeSpell();
    }

    public static Optional<GuardSpellManager.CategorizedSpell> getMagicSpell(GuardEntity guard, LivingEntity target) {
        return guard.getSpellManager().getBestCastableSpellbladeMagicSpell(target);
    }

    public static boolean hasMagicReady(GuardEntity guard, LivingEntity target) {
        return getMagicSpell(guard, target).isPresent();
    }

    public static boolean allCombatSpellsOnCooldown(GuardEntity guard, LivingEntity target) {
        return getMeleeSpell(guard).isEmpty() && getMagicSpell(guard, target).isEmpty();
    }

    public static boolean inMeleeSpellRange(GuardEntity guard, LivingEntity target, GuardSpellManager.CategorizedSpell spell) {
        float range = meleeCastRange(spell);
        return guard.squaredDistanceTo(target) <= range * range;
    }

    public static float meleeCastRange(GuardSpellManager.CategorizedSpell spell) {
        float range = spell.entry().value().range;
        if (range <= 0) {
            range = MELEE_SPELL_RANGE;
        }
        return range;
    }

    public static float magicRange(GuardSpellManager.CategorizedSpell spell) {
        Spell data = spell.entry().value();
        float range = data.range;
        if (range <= 0) {
            range = MAGIC_MAX_RANGE;
        }
        return Math.min(range, MAGIC_MAX_RANGE);
    }

    public static float meleeCastRange(GuardEntity guard, RegistryEntry<Spell> entry) {
        float range = guard.getSpellManager().getAugmentedRange(entry);
        return range > 0 ? range : MELEE_SPELL_RANGE;
    }

    public static boolean friendlyInLineOfSight(GuardEntity guard) {
        if (!GuardVillagersConfig.friendlyFire) {
            return false;
        }
        LivingEntity enemy = guard.getTarget();
        if (enemy == null) {
            return false;
        }
        for (Entity entity : guard.getWorld().getOtherEntities(guard, guard.getBoundingBox().expand(5.0D))) {
            if (entity == enemy) {
                continue;
            }
            boolean friendly = entity.getType() == EntityType.VILLAGER
                    || entity.getType() == dev.sterner.guardvillagers.GuardVillagers.GUARD_VILLAGER
                    || entity.getType() == EntityType.IRON_GOLEM
                    || entity == guard.getOwner();
            if (friendly && guard.canSee(entity) && guard.distanceTo(entity) <= 4.0D) {
                Vec3d toFriend = entity.getPos().subtract(guard.getPos()).normalize();
                Vec3d facing = guard.getRotationVector();
                if (facing.dotProduct(toFriend) > 0.9D) {
                    return true;
                }
            }
        }
        return false;
    }
}
