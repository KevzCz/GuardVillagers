package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.animation.GuardAnimationDurations;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellParameters;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class GuardSpellTimings {

    private GuardSpellTimings() {}

    public static int channeledMeleeFollowThroughTicks(GuardEntity guard, Spell spell) {
        if (!SpellParameters.isChanneled(spell)
                || spell.deliver == null
                || spell.deliver.type != Spell.Delivery.Type.MELEE
                || spell.deliver.melee == null
                || spell.deliver.melee.attacks == null
                || spell.deliver.melee.attacks.isEmpty()) {
            return 0;
        }
        Spell.Delivery.Melee.Attack last = spell.deliver.melee.attacks.get(spell.deliver.melee.attacks.size() - 1);
        int pendingDelay = Math.max(0, (int) (last.delay * 20));
        return pendingDelay + Math.min(6, attackAnimationTicks(guard, last) / 2) + 2;
    }

    public static int meleeFollowThroughTicks(GuardEntity guard, Spell spell) {
        if (spell.deliver == null || spell.deliver.type != Spell.Delivery.Type.MELEE
                || spell.deliver.melee == null || spell.deliver.melee.attacks == null) {
            return 0;
        }
        if (SpellParameters.isChanneled(spell)) {
            return 0;
        }

        List<Spell.Delivery.Melee.Attack> attacks = spell.deliver.melee.attacks;
        if (attacks.size() > 1) {
            return chainedAttackDurationTicks(guard, spell, attacks);
        }

        Spell.Delivery.Melee.Attack attack = attacks.get(0);
        int start = Math.max(0, (int) (attack.delay * 20));
        int strikeSpan = 0;
        if (attack.additional_strikes > 0) {
            float step = attack.additional_strike_delay > 0 ? attack.additional_strike_delay : 0.1f;
            strikeSpan = (int) (attack.additional_strikes * step * 20);
        }
        int animTicks = attackAnimationTicks(guard, attack);
        return start + strikeSpan + animTicks + 2;
    }

    private static int chainedAttackDurationTicks(GuardEntity guard, Spell spell,
                                                  List<Spell.Delivery.Melee.Attack> attacks) {
        int scheduleAt = 0;
        int endAt = 0;
        for (Spell.Delivery.Melee.Attack attack : attacks) {
            scheduleAt += Math.max(0, (int) (attack.delay * 20));
            int strikeSpan = 0;
            if (attack.additional_strikes > 0) {
                float step = attack.additional_strike_delay > 0 ? attack.additional_strike_delay : 0.1f;
                strikeSpan = (int) (attack.additional_strikes * step * 20);
            }
            int attackEnd = scheduleAt + Math.max(attackAnimationTicks(guard, attack), strikeSpan);
            endAt = Math.max(endAt, attackEnd);
        }
        return endAt + 2;
    }

    private static int attackAnimationTicks(GuardEntity guard, Spell.Delivery.Melee.Attack attack) {
        if (attack.animation == null) {
            return attack.duration > 0 ? Math.max(1, (int) (attack.duration * 20)) : 8;
        }
        String id = GuardCastVisuals.resolveAnimationId(guard, attack.animation);
        float speed = GuardCastVisuals.effectiveAnimationSpeed(guard, attack.animation);
        return id != null ? GuardCastVisuals.swingDurationTicks(id, speed) : 8;
    }

    public static int postDeliverWaitTicks(GuardEntity guard, Spell spell) {
        int melee = meleeFollowThroughTicks(guard, spell);
        int releaseAnim = 0;
        if (spell.release != null && spell.release.animation != null) {
            String id = GuardCastVisuals.resolveReleaseAnimationId(guard, spell);
            if (id != null) {
                float speed = GuardCastVisuals.effectiveAnimationSpeed(guard, spell.release.animation);
                releaseAnim = GuardAnimationDurations.durationTicks(id, speed);
            }
        }
        return Math.max(melee, releaseAnim);
    }

    public static boolean isMeleeDelivery(Spell spell) {
        return spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.MELEE;
    }

    public static boolean isDelayedImpactSpell(Spell spell) {
        return spell.deliver != null && spell.deliver.delay > 0
                && (spell.deliver.type == null || spell.deliver.type == Spell.Delivery.Type.DIRECT);
    }

    @Nullable
    public static Spell.Delivery.Type effectiveDeliveryType(Spell spell) {
        if (spell.deliver == null) {
            return null;
        }
        if (spell.deliver.type != null) {
            return spell.deliver.type;
        }
        return Spell.Delivery.Type.DIRECT;
    }
}
