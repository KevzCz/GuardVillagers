package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.Formatting;
import net.spell_engine.api.spell.Spell;

import java.util.List;

public class DefensiveSpellHandler {
    private final GuardEntity guard;

    public DefensiveSpellHandler(GuardEntity guard) {
        this.guard = guard;
    }

    public void triggerDamageTakenSpells(DamageSource source, float amount) {
        if (guard.getWorld().isClient()) {
            return;
        }

        List<GuardSpellManager.CategorizedSpell> passives = guard.getSpellManager()
                .getPassiveSpells(GuardSpellManager.SpellCategory.PASSIVE_DEFENSE);

        if (passives.isEmpty()) {
            return;
        }

        Entity attacker = source.getAttacker();
        LivingEntity livingAttacker = attacker instanceof LivingEntity le ? le : null;

        int triggeredCount = 0;
        for (GuardSpellManager.CategorizedSpell passive : passives) {
            if (guard.isSpellOnCooldown(passive.spellId())) {
                continue;
            }

            Spell spell = passive.entry().value();

            if (spell.passive == null || spell.passive.triggers == null) {
                continue;
            }

            for (Spell.Trigger trigger : spell.passive.triggers) {
                if (trigger.type != Spell.Trigger.Type.DAMAGE_TAKEN) {
                    continue;
                }

                if (!checkCasterConditions(trigger)) {
                    continue;
                }

                if (trigger.chance > 0 && trigger.chance < 1.0f) {
                    if (guard.getRandom().nextFloat() > trigger.chance) {
                        GuardDebugManager.broadcast(guard,
                                "  🎲 " + passive.spellId().getPath() + " chance failed (" + (trigger.chance * 100) + "%)",
                                Formatting.GRAY);
                        continue;
                    }
                }

                GuardDebugManager.broadcast(guard,
                        "🛡️ Damage taken passive: " + passive.spellId().getPath(),
                        Formatting.LIGHT_PURPLE);

                executeDefensiveSpell(passive, spell, trigger, livingAttacker);
                triggeredCount++;
                break;
            }
        }

        if (triggeredCount > 0) {
            GuardDebugManager.broadcast(guard,
                    "💥 Triggered " + triggeredCount + " damage-taken passive(s)",
                    Formatting.AQUA);
        }
    }

    public void triggerShieldBlockSpells(float blockedDamage, LivingEntity attacker) {
        if (guard.getWorld().isClient()) {
            return;
        }

        List<GuardSpellManager.CategorizedSpell> passives = guard.getSpellManager()
                .getPassiveSpells(GuardSpellManager.SpellCategory.PASSIVE_DEFENSE);

        if (passives.isEmpty()) {
            return;
        }

        int triggeredCount = 0;
        for (GuardSpellManager.CategorizedSpell passive : passives) {
            if (guard.isSpellOnCooldown(passive.spellId())) {
                continue;
            }

            Spell spell = passive.entry().value();

            if (spell.passive == null || spell.passive.triggers == null) {
                continue;
            }

            for (Spell.Trigger trigger : spell.passive.triggers) {
                if (trigger.type != Spell.Trigger.Type.SHIELD_BLOCK) {
                    continue;
                }

                if (!checkCasterConditions(trigger)) {
                    continue;
                }

                if (trigger.chance > 0 && trigger.chance < 1.0f) {
                    if (guard.getRandom().nextFloat() > trigger.chance) {
                        GuardDebugManager.broadcast(guard,
                                "  🎲 " + passive.spellId().getPath() + " chance failed (" + (trigger.chance * 100) + "%)",
                                Formatting.GRAY);
                        continue;
                    }
                }

                GuardDebugManager.broadcast(guard,
                        "🛡️ Shield block passive: " + passive.spellId().getPath(),
                        Formatting.GOLD);

                executeDefensiveSpell(passive, spell, trigger, attacker);
                triggeredCount++;
                break;
            }
        }

        if (triggeredCount > 0) {
            GuardDebugManager.broadcast(guard,
                    "🔰 Triggered " + triggeredCount + " shield-block passive(s)",
                    Formatting.AQUA);
        }
    }

    private boolean checkCasterConditions(Spell.Trigger trigger) {
        if (trigger.caster_conditions == null || trigger.caster_conditions.isEmpty()) {
            return true;
        }
        for (Spell.TargetCondition cond : trigger.caster_conditions) {
            if (!net.spell_engine.internals.target.SpellTarget.evaluate(guard, guard, cond)) {
                return false;
            }
        }
        return true;
    }

    private void executeDefensiveSpell(GuardSpellManager.CategorizedSpell passive, Spell spell,
            Spell.Trigger trigger, LivingEntity attacker) {

        LivingEntity effectTarget;
        Spell.Trigger.TargetSelector targetOverride = trigger.target_override;
        if (targetOverride == Spell.Trigger.TargetSelector.CASTER) {
            effectTarget = guard;
        } else if (spell.target != null && spell.target.type == Spell.Target.Type.FROM_TRIGGER
                && attacker != null) {
            effectTarget = attacker;
        } else {
            effectTarget = guard;
        }

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "  🎯 Defensive target: " + effectTarget.getName().getString(),
                    Formatting.YELLOW);
        }

        LivingEntity deliveryTarget = SupportSpellCasting.resolveDeliveryTarget(spell, effectTarget);
        SupportSpellCasting.deliver(guard, passive.spellId(), passive.entry(), deliveryTarget, 0);
        guard.setSpellCooldown(passive.spellId(), BaseSpellGoal.resolveCooldownTicks(guard, passive.entry()));

        GuardDebugManager.broadcast(guard,
                "  ✅ Defensive delivery via SpellDelivery",
                Formatting.GREEN);
    }
}
