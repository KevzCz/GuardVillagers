package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.entity.SpellProjectile;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

import java.util.List;

public class MeleeSpellHandler {
    private static final double DEFAULT_MELEE_RANGE = 6.0;
    private static final int DEFAULT_TARGET_CAP = 3;
    private static final float CONE_ANGLE = 45.0F;

    private final GuardEntity guard;

    public MeleeSpellHandler(GuardEntity guard) {
        this.guard = guard;
    }

    public void triggerOnHitSpells(LivingEntity target) {
        List<GuardSpellManager.CategorizedSpell> meleeSpells = guard.getSpellManager().getMeleeOnHitSpells();

        if (meleeSpells.isEmpty()) {
            return;
        }

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "🎯 Triggering " + meleeSpells.size() + " melee on-hit spell(s)",
                    Formatting.AQUA);
        }

        for (GuardSpellManager.CategorizedSpell categorizedSpell : meleeSpells) {
            if (categorizedSpell.isPassive()) {
                castPassiveMeleeSpell(categorizedSpell, target);
            } else {
                castMeleeSpell(categorizedSpell, target);
            }
        }
    }

    private void castPassiveMeleeSpell(GuardSpellManager.CategorizedSpell categorizedSpell, LivingEntity primaryTarget) {
        Spell spell = categorizedSpell.entry().value();

        if (spell.passive == null || spell.passive.triggers == null) {
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  ⚠️ " + categorizedSpell.spellId().getPath() + " has no passive triggers",
                        Formatting.YELLOW);
            }
            return;
        }

        // For direct melee weapon hits, only trigger MELEE_IMPACT passives
        // SPELL_IMPACT_SPECIFIC passives are handled by SpellDelivery when spells are cast
        boolean hasMeleeImpactTrigger = false;
        for (Spell.Trigger trigger : spell.passive.triggers) {
            if (trigger.type == Spell.Trigger.Type.MELEE_IMPACT) {
                hasMeleeImpactTrigger = true;
                
                // Apply chance check for melee impact triggers
                if (trigger.chance > 0 && trigger.chance < 1.0f) {
                    if (guard.getRandom().nextFloat() > trigger.chance) {
                        if (!guard.getWorld().isClient()) {
                            GuardDebugManager.broadcast(guard,
                                    "  🎲 " + categorizedSpell.spellId().getPath() + " chance failed (" + (trigger.chance * 100) + "%)",
                                    Formatting.GRAY);
                        }
                        return;
                    }
                }
                break;
            }
        }

        if (!hasMeleeImpactTrigger) {
            // Silently skip - this passive is meant for spell impacts, not melee hits
            // Don't spam the debug log with warnings
            return;
        }

        logMeleeCast(categorizedSpell.spellId(), spell);

        if (!guard.getWorld().isClient()) {
            String deliveryType = spell.deliver != null ? spell.deliver.type.name() : "NONE";
            String targetType = spell.target != null ? spell.target.type.name() : "NONE";
            String handler = spell.deliver != null && spell.deliver.custom != null
                    ? spell.deliver.custom.handler
                    : "N/A";

            GuardDebugManager.broadcast(guard,
                    "  📝 Delivery: " + deliveryType + " | Target: " + targetType + " | Handler: " + handler,
                    Formatting.GRAY);

            if (spell.impacts != null && !spell.impacts.isEmpty()) {
                GuardDebugManager.broadcast(guard,
                        "  💥 " + spell.impacts.size() + " impact(s) defined:",
                        Formatting.GRAY);

                for (int i = 0; i < spell.impacts.size(); i++) {
                    Spell.Impact impact = spell.impacts.get(i);
                    String impactType = impact.action != null ? impact.action.type.name() : "NULL";
                    GuardDebugManager.broadcast(guard,
                            "    [" + i + "] Type: " + impactType,
                            Formatting.DARK_GRAY);

                    if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.DAMAGE) {
                        double coeff = impact.action.damage != null
                                ? impact.action.damage.spell_power_coefficient
                                : 0.0;
                        boolean bypassIframes = impact.action.damage != null && impact.action.damage.bypass_iframes;
                        float knockback = impact.action.damage != null
                                ? impact.action.damage.knockback
                                : 1.0F;

                        GuardDebugManager.broadcast(guard,
                                "        Damage: coeff=" + coeff + ", bypass_iframes=" + bypassIframes + ", knockback=" + knockback,
                                Formatting.DARK_GRAY);
                    }
                }
            } else {
                GuardDebugManager.broadcast(guard,
                        "  ⚠️ No impacts defined!",
                        Formatting.RED);
            }
        }

        SpellHelper.ImpactContext context = createContext(spell);

        // Handle different delivery types for passive melee spells
        if (spell.deliver != null) {
            switch (spell.deliver.type) {
                case CUSTOM -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing CUSTOM delivery with handler: " + spell.deliver.custom.handler,
                                Formatting.LIGHT_PURPLE);
                    }
                    castMeleeCustom(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                case PROJECTILE -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing PROJECTILE delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    castMeleeProjectiles(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                case CLOUD -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing CLOUD delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    castMeleeCloud(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                default -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing DIRECT delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    castPassiveDirect(spell, categorizedSpell.entry(), context, primaryTarget);
                }
            }
        } else {
            // No delivery type - handle based on target type
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  🔧 Executing impact based on target type",
                        Formatting.LIGHT_PURPLE);
            }
            castPassiveDirect(spell, categorizedSpell.entry(), context, primaryTarget);
        }
    }
    
    /**
     * Execute a passive spell based on its target type.
     * Handles FROM_TRIGGER, AREA, and direct impacts.
     */
    private void castPassiveDirect(Spell spell, RegistryEntry<Spell> spellEntry, SpellHelper.ImpactContext context, LivingEntity primaryTarget) {
        if (spell.target == null || spell.target.type == Spell.Target.Type.FROM_TRIGGER) {
            // FROM_TRIGGER: apply directly to the triggering target
            boolean success = SpellHelper.performImpacts(
                    guard.getWorld(),
                    guard,
                    primaryTarget,
                    guard,
                    spellEntry,
                    spell.impacts,
                    context.position(primaryTarget.getPos().add(0, primaryTarget.getHeight() / 2.0, 0))
            );
            
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        success ? "    ✅ Hit: " + primaryTarget.getName().getString() : "    ❌ Missed: " + primaryTarget.getName().getString(),
                        success ? Formatting.GREEN : Formatting.RED);
            }
        } else if (spell.target.type == Spell.Target.Type.AREA) {
            // AREA: hit all targets in range
            double radius = spell.range > 0 ? spell.range : 3.0;
            // Handle negative range as relative to melee hit (swirling_melee has range: -0.5)
            if (spell.range < 0) {
                radius = 3.0 + Math.abs(spell.range);
            }
            
            List<LivingEntity> targets = guard.getWorld().getEntitiesByClass(
                    LivingEntity.class,
                    primaryTarget.getBoundingBox().expand(radius),
                    e -> e.isAlive() && e != guard && guard.canTarget(e)
            );
            
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "    🎯 Found " + targets.size() + " target(s) in area (radius: " + radius + ")",
                        Formatting.GRAY);
            }
            
            for (LivingEntity target : targets) {
                boolean success = SpellHelper.performImpacts(
                        guard.getWorld(),
                        guard,
                        target,
                        guard,
                        spellEntry,
                        spell.impacts,
                        context.position(target.getPos().add(0, target.getHeight() / 2.0, 0))
                );
                
                if (!guard.getWorld().isClient()) {
                    GuardDebugManager.broadcast(guard,
                            success ? "      ✅ Hit: " + target.getName().getString() : "      ❌ Missed: " + target.getName().getString(),
                            success ? Formatting.GREEN : Formatting.RED);
                }
            }
        } else {
            // Default: apply to primary target
            castMeleeDirect(spell, spellEntry, context);
        }
        
        playReleaseSound(spell);
    }
    
    /**
     * Cast a melee spell that creates a cloud effect.
     */
    private void castMeleeCloud(Spell spell, RegistryEntry<Spell> spellEntry, SpellHelper.ImpactContext context, LivingEntity primaryTarget) {
        Vec3d targetPos = primaryTarget.getPos();
        
        try {
            SpellHelper.placeCloud(
                    guard.getWorld(),
                    guard,
                    primaryTarget,
                    targetPos,
                    spellEntry,
                    context.position(targetPos)
            );
            
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "    ✅ Cloud placed at target",
                        Formatting.GREEN);
            }
        } catch (Exception e) {
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "    ❌ Cloud placement failed: " + e.getMessage(),
                        Formatting.RED);
            }
        }
        
        playReleaseSound(spell);
    }

    private void castMeleeCustom(Spell spell, RegistryEntry<Spell> spellEntry, SpellHelper.ImpactContext context, LivingEntity primaryTarget) {
        Vec3d targetLocation = primaryTarget.getPos().add(0.0, primaryTarget.getHeight() / 2.0, 0.0);
        SpellHelper.ImpactContext targetContext = context.position(targetLocation);

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "    🎯 Target: " + primaryTarget.getName().getString() + " at " +
                            String.format("(%.1f, %.1f, %.1f)", targetLocation.x, targetLocation.y, targetLocation.z),
                    Formatting.GRAY);
        }

        boolean success = SpellDelivery.deliverForLivingEntity(
                guard.getWorld(),
                spellEntry,
                guard,
                List.of(new SpellHelper.DeliveryTarget(primaryTarget, targetContext)),
                targetContext,
                targetLocation,
                spell
        );

        if (!guard.getWorld().isClient()) {
            if (success) {
                GuardDebugManager.broadcast(guard,
                        "    ✅ Custom delivery successful",
                        Formatting.GREEN);
            } else {
                GuardDebugManager.broadcast(guard,
                        "    ❌ Custom delivery failed",
                        Formatting.RED);
            }
        }

        if (success) {
            SpellDelivery.triggerPassiveSpellsPublic(guard, primaryTarget, spellEntry, false);
            SpellDelivery.triggerStashedEffectsPublic(guard, primaryTarget, spellEntry);
        }

        playReleaseSound(spell);
    }

    private void castMeleeDirect(Spell spell, RegistryEntry<Spell> spellEntry, SpellHelper.ImpactContext context) {
        double radius = spell.range > 0 ? spell.range : 5.0;
        List<LivingEntity> targets = findConeTargets(radius);

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "    🎯 Found " + targets.size() + " target(s) in cone (radius: " + radius + ")",
                    Formatting.GRAY);
        }

        for (LivingEntity target : targets) {
            boolean success = SpellHelper.performImpacts(
                    guard.getWorld(),
                    guard,
                    guard,
                    target,
                    spellEntry,
                    spell.impacts,
                    context
            );

            if (!guard.getWorld().isClient()) {
                if (success) {
                    GuardDebugManager.broadcast(guard,
                            "      ✅ Hit: " + target.getName().getString(),
                            Formatting.GREEN);
                } else {
                    GuardDebugManager.broadcast(guard,
                            "      ❌ Missed: " + target.getName().getString(),
                            Formatting.RED);
                }
            }
        }

        playReleaseSound(spell);
    }

    private void logMeleeCast(Identifier spellId, Spell spell) {
        if (!guard.getWorld().isClient()) {
            String type = spell.deliver != null ? spell.deliver.type.name() : "DIRECT";
            String archetype = spell.school != null && spell.school.archetype != null
                    ? spell.school.archetype.name()
                    : "NONE";

            GuardDebugManager.broadcast(guard,
                    "⚔️ " + spellId.getPath() + " [MELEE_" + type + "] Archetype: " + archetype,
                    Formatting.RED);
        }
    }

    private void castMeleeSpell(GuardSpellManager.CategorizedSpell categorizedSpell, LivingEntity primaryTarget) {
        Spell spell = categorizedSpell.entry().value();

        logMeleeCast(categorizedSpell.spellId(), spell);

        SpellHelper.ImpactContext context = createContext(spell);

        if (spell.deliver != null) {
            switch (spell.deliver.type) {
                case PROJECTILE -> castMeleeProjectiles(spell, categorizedSpell.entry(), context, primaryTarget);
                case DIRECT -> castMeleeDirect(spell, categorizedSpell.entry(), context);
                case CUSTOM -> castMeleeCustom(spell, categorizedSpell.entry(), context, primaryTarget);
                default -> castMeleeDirect(spell, categorizedSpell.entry(), context);
            }
        } else {
            castMeleeDirect(spell, categorizedSpell.entry(), context);
        }
    }



    private void castMeleeProjectiles(Spell spell, RegistryEntry<Spell> spellEntry, SpellHelper.ImpactContext context, LivingEntity primaryTarget) {
        double range = spell.range > 0 ? spell.range : DEFAULT_MELEE_RANGE;
        int cap = (spell.target != null && spell.target.cap > 0) ? spell.target.cap : DEFAULT_TARGET_CAP;

        List<LivingEntity> targets = findMeleeTargets(range, primaryTarget);

        int count = 0;
        Vec3d spawnPos = guard.getEyePos();

        for (LivingEntity target : targets) {
            if (count >= cap) break;

            spawnMeleeProjectile(spell, spellEntry, context, target, spawnPos, range);
            count++;
        }

        playLaunchSound(spell);
    }



    private List<LivingEntity> findMeleeTargets(double range, LivingEntity primaryTarget) {
        return guard.getWorld().getEntitiesByClass(
                LivingEntity.class,
                guard.getBoundingBox().expand(range),
                e -> e != guard
                        && e.isAlive()
                        && guard.canSee(e)
                        && guard.canTarget(e)
                        && (!(e instanceof PlayerEntity) || e == primaryTarget)
        );
    }

    private List<LivingEntity> findConeTargets(double radius) {
        Vec3d forward = guard.getRotationVector();
        double cosThreshold = Math.cos(Math.toRadians(CONE_ANGLE));

        List<LivingEntity> nearby = guard.getWorld().getEntitiesByClass(
                LivingEntity.class,
                guard.getBoundingBox().expand(radius),
                e -> e != guard && e.isAlive() && guard.canSee(e)
        );

        return nearby.stream()
                .filter(e -> {
                    Vec3d toTarget = e.getPos().subtract(guard.getPos()).normalize();
                    return forward.dotProduct(toTarget) > cosThreshold;
                })
                .toList();
    }

    private void spawnMeleeProjectile(Spell spell, RegistryEntry<Spell> spellEntry, SpellHelper.ImpactContext context,
                                      LivingEntity target, Vec3d spawnPos, double range) {
        Vec3d direction = target.getEyePos().subtract(spawnPos).normalize().multiply(1.5);

        Spell.ProjectileData.Perks perks = (spell.deliver.projectile != null && spell.deliver.projectile.projectile.perks != null)
                ? spell.deliver.projectile.projectile.perks.copy()
                : new Spell.ProjectileData.Perks();

        SpellProjectile projectile = new SpellProjectile(
                guard.getWorld(),
                guard,
                spawnPos.x,
                spawnPos.y,
                spawnPos.z,
                SpellProjectile.Behaviour.FLY,
                spellEntry,
                context,
                perks
        );

        projectile.setVelocity(direction);
        projectile.range = (float) range;
        guard.getWorld().spawnEntity(projectile);
    }

    private SpellHelper.ImpactContext createContext(Spell spell) {
        return new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard))
                .position(guard.getEyePos())
                .target(SpellHelper.focusMode(spell));
    }



    private void playLaunchSound(Spell spell) {
        if (spell.deliver == null || spell.deliver.projectile == null) return;
        if (spell.deliver.projectile.launch_properties == null) return;
        if (spell.deliver.projectile.launch_properties.sound == null) return;

        Identifier soundId = Identifier.tryParse(spell.deliver.projectile.launch_properties.sound.id());
        if (soundId != null) {
            SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundId);
            guard.getWorld().playSound(null, guard.getBlockPos(), soundEvent, SoundCategory.HOSTILE, 1.0f, 1.0f);
        }
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