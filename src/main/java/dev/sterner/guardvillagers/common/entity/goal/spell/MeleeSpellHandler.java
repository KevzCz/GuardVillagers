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
import net.spell_engine.internals.SpellExecution;
import net.spell_engine.internals.impact.SpellImpacts;
import net.spell_engine.internals.delivery.CloudPlacer;
import net.spell_engine.internals.delivery.ProjectileLauncher;
import net.spell_engine.internals.target.SpellIntents;
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
            if (guard.isSpellOnCooldown(categorizedSpell.spellId())) {
                continue;
            }
            if (categorizedSpell.isPassive()) {
                if (castPassiveMeleeSpell(categorizedSpell, target)) {
                    guard.setSpellCooldown(
                            categorizedSpell.spellId(),
                            BaseSpellGoal.resolveCooldownTicks(guard, categorizedSpell.entry())
                    );
                }
            } else {
                if (castMeleeSpell(categorizedSpell, target)) {
                    guard.setSpellCooldown(
                            categorizedSpell.spellId(),
                            BaseSpellGoal.resolveCooldownTicks(guard, categorizedSpell.entry())
                    );
                }
            }
        }
    }

    private boolean castPassiveMeleeSpell(GuardSpellManager.CategorizedSpell categorizedSpell, LivingEntity primaryTarget) {
        Spell spell = categorizedSpell.entry().value();

        if (spell.passive == null || spell.passive.triggers == null) {
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  ⚠️ " + categorizedSpell.spellId().getPath() + " has no passive triggers",
                        Formatting.YELLOW);
            }
            return false;
        }

        
        
        boolean hasMeleeImpactTrigger = false;
        for (Spell.Trigger trigger : spell.passive.triggers) {
            if (trigger.type == Spell.Trigger.Type.MELEE_IMPACT) {
                if (trigger.equipment_condition != null
                        && trigger.equipment_condition == net.minecraft.entity.EquipmentSlot.MAINHAND
                        && guard.getMainHandStack().isEmpty()) {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  ⚠️ " + categorizedSpell.spellId().getPath() + " equipment_condition MAINHAND not met",
                                Formatting.GRAY);
                    }
                    return false;
                }

                hasMeleeImpactTrigger = true;

                if (trigger.caster_conditions != null) {
                    boolean condsMet = true;
                    for (net.spell_engine.api.spell.Spell.TargetCondition cond : trigger.caster_conditions) {
                        if (!net.spell_engine.internals.target.SpellTarget.evaluate(guard, primaryTarget, cond)) {
                            condsMet = false;
                            break;
                        }
                    }
                    if (!condsMet) return false;
                }

                if (trigger.target_conditions != null) {
                    boolean condsMet = true;
                    for (net.spell_engine.api.spell.Spell.TargetCondition cond : trigger.target_conditions) {
                        if (!net.spell_engine.internals.target.SpellTarget.evaluate(primaryTarget, guard, cond)) {
                            condsMet = false;
                            break;
                        }
                    }
                    if (!condsMet) return false;
                }

                if (trigger.chance > 0 && trigger.chance < 1.0f) {
                    if (guard.getRandom().nextFloat() > trigger.chance) {
                        if (!guard.getWorld().isClient()) {
                            GuardDebugManager.broadcast(guard,
                                    "  🎲 " + categorizedSpell.spellId().getPath() + " chance failed (" + (trigger.chance * 100) + "%)",
                                    Formatting.GRAY);
                        }
                        return false;
                    }
                }
                break;
            }
        }

        if (!hasMeleeImpactTrigger) {
            return false;
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

        SpellExecution.ImpactContext context = createContext(spell);

        
        if (spell.deliver != null) {
            return switch (spell.deliver.type) {
                case CUSTOM -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing CUSTOM delivery with handler: " + spell.deliver.custom.handler,
                                Formatting.LIGHT_PURPLE);
                    }
                    yield castMeleeCustom(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                case PROJECTILE -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing PROJECTILE delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    yield castMeleeProjectiles(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                case METEOR -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing METEOR delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    yield castMeleeMeteor(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                case CLOUD -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing CLOUD delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    yield castMeleeCloud(spell, categorizedSpell.entry(), context, primaryTarget);
                }
                default -> {
                    if (!guard.getWorld().isClient()) {
                        GuardDebugManager.broadcast(guard,
                                "  🔧 Executing DIRECT delivery",
                                Formatting.LIGHT_PURPLE);
                    }
                    yield castPassiveDirect(spell, categorizedSpell.entry(), context, primaryTarget);
                }
            };
        }

        
        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "  🔧 Executing impact based on target type",
                    Formatting.LIGHT_PURPLE);
        }
        return castPassiveDirect(spell, categorizedSpell.entry(), context, primaryTarget);
    }
    
    

    private boolean castPassiveDirect(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context, LivingEntity primaryTarget) {
        boolean anySuccess = false;
        if (spell.target == null || spell.target.type == Spell.Target.Type.FROM_TRIGGER) {
            
            boolean success = SpellImpacts.performImpacts(
                    guard.getWorld(),
                    guard,
                    primaryTarget,
                    guard,
                    spellEntry,
                    spell.impacts,
                    context.position(primaryTarget.getPos().add(0, primaryTarget.getHeight() / 2.0, 0))
            );
            anySuccess = success;
            
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        success ? "    ✅ Hit: " + primaryTarget.getName().getString() : "    ❌ Missed: " + primaryTarget.getName().getString(),
                        success ? Formatting.GREEN : Formatting.RED);
            }
        } else if (spell.target.type == Spell.Target.Type.AREA) {
            
            double radius = spell.range > 0 ? spell.range : 3.0;
            
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
                boolean success = SpellImpacts.performImpacts(
                        guard.getWorld(),
                        guard,
                        target,
                        guard,
                        spellEntry,
                        spell.impacts,
                        context.position(target.getPos().add(0, target.getHeight() / 2.0, 0))
                );
                anySuccess |= success;
                
                if (!guard.getWorld().isClient()) {
                    GuardDebugManager.broadcast(guard,
                            success ? "      ✅ Hit: " + target.getName().getString() : "      ❌ Missed: " + target.getName().getString(),
                            success ? Formatting.GREEN : Formatting.RED);
                }
            }
        } else {
            
            anySuccess = castMeleeDirect(spell, spellEntry, context);
        }
        
        playReleaseSound(spell);
        return anySuccess;
    }
    
    

    private boolean castMeleeMeteor(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context, LivingEntity primaryTarget) {
        int count = 1;
        if (spell.deliver != null && spell.deliver.meteor != null
                && spell.deliver.meteor.launch_properties != null) {
            count = 1 + spell.deliver.meteor.launch_properties.extra_launch_count;
        }

        Vec3d targetPos = primaryTarget.getPos();
        boolean success = ProjectileLauncher.fallProjectile(
                guard.getWorld(),
                guard,
                primaryTarget,
                targetPos,
                spellEntry,
                context.position(targetPos),
                count
        );

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    success ? "    ✅ Meteor launched (" + count + ")" : "    ❌ Meteor failed",
                    success ? Formatting.GREEN : Formatting.RED);
        }

        playReleaseSound(spell);
        return success;
    }

    private boolean castMeleeCloud(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context, LivingEntity primaryTarget) {
        Vec3d targetPos = primaryTarget.getPos();
        
        boolean success = false;
        try {
            CloudPlacer.placeCloud(
                    guard.getWorld(),
                    guard,
                    primaryTarget,
                    targetPos,
                    spellEntry,
                    context.position(targetPos)
            );
            success = true;
            
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
        return success;
    }

    private boolean castMeleeCustom(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context, LivingEntity primaryTarget) {
        Vec3d targetLocation = primaryTarget.getPos().add(0.0, primaryTarget.getHeight() / 2.0, 0.0);
        SpellExecution.ImpactContext targetContext = context.position(targetLocation);

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
                List.of(new SpellExecution.DeliveryTarget(primaryTarget, targetContext)),
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
        return success;
    }

    private boolean castMeleeDirect(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context) {
        double radius = spell.range > 0 ? spell.range : 5.0;
        List<LivingEntity> targets = findConeTargets(radius);

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "    🎯 Found " + targets.size() + " target(s) in cone (radius: " + radius + ")",
                    Formatting.GRAY);
        }

        boolean anySuccess = false;
        for (LivingEntity target : targets) {
            boolean success = SpellImpacts.performImpacts(
                    guard.getWorld(),
                    guard,
                    target,
                    target,
                    spellEntry,
                    spell.impacts,
                    context
            );
            anySuccess |= success;

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
        return anySuccess;
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

    private boolean castMeleeSpell(GuardSpellManager.CategorizedSpell categorizedSpell, LivingEntity primaryTarget) {
        Spell spell = categorizedSpell.entry().value();

        logMeleeCast(categorizedSpell.spellId(), spell);

        SpellExecution.ImpactContext context = createContext(spell);

        if (spell.deliver != null) {
            return switch (spell.deliver.type) {
                case PROJECTILE -> castMeleeProjectiles(spell, categorizedSpell.entry(), context, primaryTarget);
                case DIRECT -> castMeleeDirect(spell, categorizedSpell.entry(), context);
                case CUSTOM -> castMeleeCustom(spell, categorizedSpell.entry(), context, primaryTarget);
                default -> castMeleeDirect(spell, categorizedSpell.entry(), context);
            };
        }
        return castMeleeDirect(spell, categorizedSpell.entry(), context);
    }

    private boolean castMeleeProjectiles(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context, LivingEntity primaryTarget) {
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
        return count > 0;
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

    private void spawnMeleeProjectile(Spell spell, RegistryEntry<Spell> spellEntry, SpellExecution.ImpactContext context,
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

    private SpellExecution.ImpactContext createContext(Spell spell) {
        return new SpellExecution.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard))
                .position(guard.getEyePos())
                .target(SpellIntents.focusMode(spell));
    }

    private void playLaunchSound(Spell spell) {
        if (spell.deliver == null || spell.deliver.projectile == null) return;
        if (spell.deliver.projectile.launch_properties == null) return;
        if (spell.deliver.projectile.launch_properties.sound == null) return;

        Identifier soundId = Identifier.tryParse(spell.deliver.projectile.launch_properties.sound.id());
        if (soundId != null) {
            Registries.SOUND_EVENT.getEntry(soundId).ifPresent(entry ->
                    guard.getWorld().playSound(null, guard.getBlockPos(), entry.value(), SoundCategory.HOSTILE, 1.0f, 1.0f));
        }
    }

    private void playReleaseSound(Spell spell) {
        if (spell.release == null || spell.release.sound == null) return;

        Identifier soundId = Identifier.tryParse(spell.release.sound.id());
        if (soundId != null) {
            Registries.SOUND_EVENT.getEntry(soundId).ifPresent(entry ->
                    guard.getWorld().playSound(
                            null,
                            guard.getBlockPos(),
                            entry.value(),
                            SoundCategory.PLAYERS,
                            1.0F,
                            1.0F
                    ));
        }
    }
}