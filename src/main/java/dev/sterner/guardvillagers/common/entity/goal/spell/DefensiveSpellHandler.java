package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

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
            Spell spell = passive.entry().value();

            if (spell.passive == null || spell.passive.triggers == null) {
                continue;
            }

            for (Spell.Trigger trigger : spell.passive.triggers) {
                if (trigger.type != Spell.Trigger.Type.DAMAGE_TAKEN) {
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
            Spell spell = passive.entry().value();

            if (spell.passive == null || spell.passive.triggers == null) {
                continue;
            }

            for (Spell.Trigger trigger : spell.passive.triggers) {
                if (trigger.type != Spell.Trigger.Type.SHIELD_BLOCK) {
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

    private void executeDefensiveSpell(GuardSpellManager.CategorizedSpell passive, Spell spell, 
            Spell.Trigger trigger, LivingEntity attacker) {
        
        Vec3d guardPos = guard.getPos().add(0.0, guard.getHeight() / 2.0, 0.0);

        SpellPower.Result augmentedPower = guard.getSpellManager().getAugmentedPower(passive.entry());
        
        SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                .power(augmentedPower)
                .position(guardPos);

        List<Spell.Impact> augmentedImpacts = guard.getSpellManager().getAugmentedImpacts(passive.entry());

        LivingEntity effectTarget = guard;
        
        Spell.Trigger.TargetSelector targetOverride = trigger.target_override;
        if (targetOverride != null && attacker != null) {
            String overrideName = targetOverride.name();

            if ("ATTACKER".equals(overrideName) || "TARGET".equals(overrideName)) {
                effectTarget = attacker;
                GuardDebugManager.broadcast(guard,
                        "  🎯 Target override: " + overrideName + " -> " + attacker.getName().getString(),
                        Formatting.YELLOW);
            }
        }

        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.CLOUD) {
            try {
                SpellHelper.placeCloud(
                        guard.getWorld(),
                        guard,
                        effectTarget,
                        guard.getPos(),
                        passive.entry(),
                        context
                );
                GuardDebugManager.broadcast(guard,
                        "  ☁️ Defensive cloud placed",
                        Formatting.AQUA);
            } catch (Exception e) {
                GuardDebugManager.broadcast(guard,
                        "  ❌ Cloud placement failed: " + e.getMessage(),
                        Formatting.RED);
            }
        } else if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {

            float augmentedRange = guard.getSpellManager().getAugmentedRange(passive.entry());
            double radius = augmentedRange > 0 ? augmentedRange : 5.0;
            List<LivingEntity> areaTargets = guard.getWorld().getEntitiesByClass(
                    LivingEntity.class,
                    guard.getBoundingBox().expand(radius),
                    e -> e.isAlive() && e != guard && guard.canTarget(e)
            );


            boolean includeCaster = spell.target.area != null && spell.target.area.include_caster;
            if (includeCaster || hasHealingImpact(spell)) {
                SpellHelper.performImpacts(
                        guard.getWorld(),
                        guard,
                        guard,
                        guard,
                        passive.entry(),
                        augmentedImpacts,
                        context
                );
            }

            for (LivingEntity areaTarget : areaTargets) {
                SpellHelper.performImpacts(
                        guard.getWorld(),
                        guard,
                        areaTarget,
                        guard,
                        passive.entry(),
                        augmentedImpacts,
                        context
                );
            }

            GuardDebugManager.broadcast(guard,
                    "  🎯 Area impact on " + areaTargets.size() + " targets",
                    Formatting.GREEN);
        } else {

            SpellHelper.performImpacts(
                    guard.getWorld(),
                    guard,
                    effectTarget,
                    guard,
                    passive.entry(),
                    augmentedImpacts,
                    context
            );

            GuardDebugManager.broadcast(guard,
                    "  ✅ Direct impact on: " + effectTarget.getName().getString(),
                    Formatting.GREEN);
        }

        playReleaseSound(spell);
    }

    private boolean hasHealingImpact(Spell spell) {
        if (spell.impacts == null) return false;
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.HEAL) {
                return true;
            }
        }
        return false;
    }

    private void playReleaseSound(Spell spell) {
        if (spell.release == null || spell.release.sound == null) return;

        Identifier soundId = Identifier.tryParse(spell.release.sound.id());
        if (soundId != null) {
            SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundId);
            guard.getWorld().playSound(
                    null,
                    guard.getBlockPos(),
                    soundEvent,
                    SoundCategory.PLAYERS,
                    1.0F,
                    1.0F
            );
        }
    }
}
