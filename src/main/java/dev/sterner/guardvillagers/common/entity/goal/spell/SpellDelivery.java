package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.event.SpellHandlers;
import net.spell_engine.internals.SpellHelper;
import net.spell_engine.internals.arrow.ArrowHelper;
import net.spell_power.api.SpellPower;

import java.util.ArrayList;
import java.util.List;

public class SpellDelivery {

    /**
     * Returns the effective impacts list for a spell, applying MODIFIER spell
     * PREPEND/APPEND impacts when the caster is a GuardEntity.
     */
    private static List<Spell.Impact> effectiveImpacts(LivingEntity caster, RegistryEntry<Spell> spellEntry) {
        if (caster instanceof GuardEntity guard) {
            return guard.getSpellManager().getAugmentedImpacts(spellEntry);
        }
        return spellEntry.value().impacts;
    }

    /**
     * Returns the effective range for a spell, adding range_add from MODIFIER spells
     * when the caster is a GuardEntity.
     */
    private static float effectiveRange(LivingEntity caster, RegistryEntry<Spell> spellEntry) {
        if (caster instanceof GuardEntity guard) {
            return guard.getSpellManager().getAugmentedRange(spellEntry);
        }
        return spellEntry.value().range;
    }

    public static void castProjectile(SpellContext context, int channelOffset) {
        Spell spell = context.spell();

        if (spell.deliver == null) {
            logError(context, "PROJECTILE", "Missing deliver configuration in spell");
            castDirect(context);
            return;
        }

        if (spell.deliver.type == Spell.Delivery.Type.SHOOT_ARROW) {
            if (spell.deliver.shoot_arrow == null) {
                logError(context, "SHOOT_ARROW", "Missing shoot_arrow configuration - attempting direct cast");
                castDirect(context);
                return;
            }
            logCast(context, "SHOOT_ARROW");

            try {
                ArrowHelper.shootArrow(
                        context.caster().getWorld(),
                        context.caster(),
                        context.entry(),
                        context.impactContext(),
                        channelOffset
                );
            } catch (Exception e) {
                logError(context, "SHOOT_ARROW", "Failed to shoot arrow: " + e.getMessage() + " - falling back to direct cast");
                castDirect(context);
                return;
            }
        } else if (spell.deliver.type == Spell.Delivery.Type.PROJECTILE) {
            if (spell.deliver.projectile == null) {
                logError(context, "PROJECTILE", "Missing projectile configuration - attempting direct cast");
                castDirect(context);
                return;
            }
            logCast(context, "PROJECTILE");

            try {
                SpellHelper.shootProjectile(
                        context.caster().getWorld(),
                        context.caster(),
                        context.target(),
                        context.entry(),
                        context.impactContext(),
                        channelOffset
                );
            } catch (Exception e) {
                logError(context, "PROJECTILE", "Failed to shoot projectile: " + e.getMessage() + " - falling back to direct cast");
                castDirect(context);
                return;
            }
        } else {
            logError(context, "PROJECTILE", "Unsupported delivery type: " + spell.deliver.type);
            castDirect(context);
            return;
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    public static void castMeteor(SpellContext context) {
        Spell spell = context.spell();

        if (spell.deliver == null) {
            logError(context, "METEOR", "Missing deliver configuration");
            castDirect(context);
            return;
        }

        if (spell.deliver.meteor == null) {
            logError(context, "METEOR", "Missing meteor configuration in spell deliver data");
            castDirect(context);
            return;
        }

        logCast(context, "METEOR");

        Vec3d targetPos = context.target() != null ? context.target().getPos() : context.caster().getPos();

        SpellHelper.fallProjectile(
                context.caster().getWorld(),
                context.caster(),
                context.target(),
                targetPos,
                context.entry(),
                context.impactContext()
        );

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    public static void castCloud(SpellContext context) {
        Spell spell = context.spell();

        if (spell.deliver == null || spell.deliver.clouds == null || spell.deliver.clouds.isEmpty()) {
            logError(context, "CLOUD", "Missing cloud configuration in spell deliver data");
            castDirect(context);
            return;
        }

        logCast(context, "CLOUD");

        Vec3d targetPos = context.target() != null ? context.target().getPos() : context.caster().getPos();

        SpellHelper.placeCloud(
                context.caster().getWorld(),
                context.caster(),
                context.target(),
                targetPos,
                context.entry(),
                context.impactContext()
        );

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    public static void castBeam(SpellContext context) {
        Spell spell = context.spell();

        if (spell.target == null || spell.target.type != Spell.Target.Type.BEAM) {
            logError(context, "BEAM", "Missing beam target configuration");
            castDirect(context);
            return;
        }

        if (spell.target.beam == null) {
            logError(context, "BEAM", "Missing beam configuration in spell target data");
            castDirect(context);
            return;
        }

        logCast(context, "BEAM");

        LivingEntity caster = context.caster();
        float effectiveBeamRange = effectiveRange(caster, context.entry());
        double range = effectiveBeamRange > 0 ? effectiveBeamRange : 32.0;

        List<net.minecraft.entity.Entity> entities = net.spell_engine.utils.TargetHelper.targetsFromRaycast(
                caster,
                (float) range,
                (entity) -> entity != caster
                        && entity.isAlive()
                        && caster.canSee(entity)
        );

        for (net.minecraft.entity.Entity entity : entities) {
            if (!(entity instanceof LivingEntity target)) continue;

            Vec3d impactPos = target.getPos().add(0.0, target.getHeight() / 2.0, 0.0);
            SpellHelper.ImpactContext beamContext = context.impactContext().position(impactPos);

            boolean success = SpellHelper.performImpacts(
                    caster.getWorld(),
                    caster,
                    target,
                    target,
                    context.entry(),
                    context.getImpacts(),
                    beamContext
            );

            if (success) {
                triggerPassiveSpells(caster, target, context.entry(), false);
                triggerStashedEffects(caster, target, context.entry());
            }
        }
    }

    public static void castDirect(SpellContext context) {
        Spell spell = context.spell();

        if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
            castBeam(context);
            return;
        }

        if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
            logCast(context, "AREA_DIRECT");
            castAreaDirect(context);
        } else {
            boolean hasSelfTargetImpact = hasSelfTargetingImpact(spell);
            String type = hasSelfTargetImpact ? "SELF_DIRECT" : "DIRECT";
            logCast(context, type);

            if (hasSelfTargetImpact || context.target() == null) {
                castSelfDirect(context);
            } else {
                castSingleDirect(context);
            }
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    private static boolean hasSelfTargetingImpact(Spell spell) {
        if (spell.impacts == null || spell.impacts.isEmpty()) {
            return false;
        }

        boolean hasDamageImpact = false;
        boolean hasSelfTargetImpact = false;

        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null) continue;

            if (impact.action.type == Spell.Impact.Action.Type.DAMAGE) {
                hasDamageImpact = true;
            }

            if (impact.action.apply_to_caster) {
                hasSelfTargetImpact = true;
            }

            if (impact.action.type == Spell.Impact.Action.Type.HEAL && !impact.action.apply_to_caster) {
                hasSelfTargetImpact = true;
            }
        }

        if (hasDamageImpact) {
            return false;
        }

        return hasSelfTargetImpact;
    }

    private static void castSelfDirect(SpellContext context) {
        boolean success = SpellHelper.performImpacts(
                context.caster().getWorld(),
                context.caster(),
                context.caster(),
                context.caster(),
                context.entry(),
                context.getImpacts(),
                context.impactContext()
        );

        if (success) {
            triggerPassiveSpells(context.caster(), context.caster(), context.entry(), false);
            triggerStashedEffects(context.caster(), context.caster(), context.entry());
        }
    }

    private static void castAreaDirect(SpellContext context) {
        Spell spell = context.spell();
        
        // For MELEE archetype spells with range 0 (like whirlwind), use melee attack range
        // Otherwise use spell range or fallback to 12.0
        double radius;
        boolean isMeleeArchetype = spell.school != null && 
                spell.school.archetype == net.spell_power.api.SpellSchool.Archetype.MELEE;

        float effectiveSpellRange = effectiveRange(context.caster(), context.entry());
        if (effectiveSpellRange > 0) {
            radius = effectiveSpellRange;
        } else if (isMeleeArchetype) {
            // MELEE archetype with range 0 = use melee attack range (around 3 blocks for guards)
            radius = 3.0;
        } else {
            radius = 12.0;
        }
        
        // For MELEE archetype (spinning attacks), don't require line of sight
        // Also filter to only hit valid targets for guards
        LivingEntity caster = context.caster();
        boolean requireLineOfSight = !isMeleeArchetype;

        List<LivingEntity> targets = caster.getWorld().getEntitiesByClass(
                LivingEntity.class,
                caster.getBoundingBox().expand(radius),
                e -> {
                    if (e == caster || !e.isAlive()) return false;
                    
                    // For guards, only hit valid attack targets
                    if (caster instanceof GuardEntity guard) {
                        // Don't hit friendlies
                        if (e instanceof net.minecraft.entity.passive.VillagerEntity) return false;
                        if (e instanceof GuardEntity) return false;
                        if (e instanceof net.minecraft.entity.passive.IronGolemEntity) return false;
                        if (e == guard.getOwner()) return false;
                        // Must be a valid target (hostile or targetable)
                        if (!guard.canTarget(e)) return false;
                    }
                    
                    // Line of sight check
                    return !requireLineOfSight || caster.canSee(e);
                }
        );

        for (LivingEntity target : targets) {
            boolean success = SpellHelper.performImpacts(
                    context.caster().getWorld(),
                    context.caster(),
                    target,
                    context.caster(),
                    context.entry(),
                    context.getImpacts(),
                    context.impactContext()
            );

            if (success) {
                triggerPassiveSpells(context.caster(), target, context.entry(), false);
                triggerStashedEffects(context.caster(), target, context.entry());
            }
        }
    }

    private static void castSingleDirect(SpellContext context) {
        boolean success = SpellHelper.performImpacts(
                context.caster().getWorld(),
                context.caster(),
                context.target(),
                context.caster(),
                context.entry(),
                context.getImpacts(),
                context.impactContext()
        );

        if (success && context.target() != null) {
            triggerPassiveSpells(context.caster(), context.target(), context.entry(), false);
            triggerStashedEffects(context.caster(), context.target(), context.entry());
        }
    }

    public static void castSelfCast(SpellContext context) {
        logCast(context, "SELF_CAST");

        Spell spell = context.spell();

        List<Spell.Impact> selfCastImpacts = context.getImpacts();
        if (selfCastImpacts != null && !selfCastImpacts.isEmpty()) {
            boolean success = SpellHelper.performImpacts(
                    context.caster().getWorld(),
                    context.caster(),
                    context.caster(),
                    context.caster(),
                    context.entry(),
                    selfCastImpacts,
                    context.impactContext()
            );

            if (success) {
                triggerPassiveSpells(context.caster(), context.caster(), context.entry(), false);
                triggerStashedEffects(context.caster(), context.caster(), context.entry());
            }
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    public static void castStashEffect(SpellContext context) {
        Spell spell = context.spell();

        if (spell.deliver == null || spell.deliver.stash_effect == null) {
            logError(context, "STASH_EFFECT", "Missing stash_effect configuration");
            return;
        }

        logCast(context, "STASH_EFFECT");

        Spell.Delivery.StashEffect stash = spell.deliver.stash_effect;
        Identifier effectId = Identifier.of(stash.id);

        var effectEntry = Registries.STATUS_EFFECT.getEntry(effectId);
        if (effectEntry.isEmpty()) {
            logError(context, "STASH_EFFECT", "Status effect not found: " + effectId);
            return;
        }

        int amplifier = stash.amplifier;
        if (stash.amplifier_power_multiplier != 0.0F) {
            SpellPower.Result power = SpellPower.getSpellPower(spell.school, context.caster());
            amplifier += (int)((double)stash.amplifier_power_multiplier * power.nonCriticalValue());
        }

        LivingEntity effectTarget = context.caster();

        if (stash.stacking) {
            int stack = -1;
            StatusEffectInstance existingInstance = effectTarget.getStatusEffect(effectEntry.get());
            if (existingInstance != null) {
                stack = existingInstance.getAmplifier();
                effectTarget.removeStatusEffect(effectEntry.get());
            }
            ++stack;
            StatusEffectInstance instance = new StatusEffectInstance(
                    effectEntry.get(),
                    (int)(stash.duration * 20.0F),
                    Math.min(stack, amplifier),
                    false,
                    true,
                    true
            );
            effectTarget.addStatusEffect(instance);
        } else {
            StatusEffectInstance instance = new StatusEffectInstance(
                    effectEntry.get(),
                    (int)(stash.duration * 20.0F),
                    amplifier,
                    false,
                    true,
                    true
            );
            effectTarget.addStatusEffect(instance);
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    public static void castAtPosition(SpellContext context, Vec3d position) {
        logCast(context, "POSITION");

        Spell spell = context.spell();

        SpellHelper.ImpactContext posContext = new SpellHelper.ImpactContext()
                .power(context.impactContext().power())
                .position(position);

        boolean success = SpellHelper.performImpacts(
                context.caster().getWorld(),
                context.caster(),
                context.caster(),
                context.caster(),
                context.entry(),
                context.getImpacts(),
                posContext
        );

        if (success) {
            triggerPassiveSpells(context.caster(), context.caster(), context.entry(), false);
            triggerStashedEffects(context.caster(), context.caster(), context.entry());
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    public static void castAreaCone(SpellContext context) {
        Spell spell = context.spell();

        if (spell.target == null || spell.target.area == null) {
            logError(context, "AREA_CONE", "Missing target.area configuration for cone spell");
            castAreaDirect(context);
            return;
        }

        logCast(context, "AREA_CONE");

        float effectiveConeRange = effectiveRange(context.caster(), context.entry());
        double range = effectiveConeRange > 0 ? effectiveConeRange : 10.0;
        float coneAngle = spell.target.area.angle_degrees > 0 ? spell.target.area.angle_degrees : 360.0f;

        List<LivingEntity> targets = findConeTargets(context.caster(), range, coneAngle);

        for (LivingEntity target : targets) {
            try {
                boolean success = SpellHelper.performImpacts(
                        context.caster().getWorld(),
                        context.caster(),
                        target,
                        context.caster(),
                        context.entry(),
                        context.getImpacts(),
                        context.impactContext()
                );

                if (success) {
                    triggerPassiveSpells(context.caster(), target, context.entry(), false);
                    triggerStashedEffects(context.caster(), target, context.entry());
                }
            } catch (Exception e) {
                logError(context, "AREA_CONE", "Failed to apply impact to target: " + e.getMessage());
            }
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    private static List<LivingEntity> findConeTargets(LivingEntity caster, double range, float angleDegs) {
        Vec3d forward = caster.getRotationVector();
        double cosThreshold = Math.cos(Math.toRadians(angleDegs / 2.0));

        List<LivingEntity> nearby = caster.getWorld().getEntitiesByClass(
                LivingEntity.class,
                caster.getBoundingBox().expand(range),
                e -> e != caster && e.isAlive() && caster.canSee(e)
        );

        return nearby.stream()
                .filter(e -> {
                    Vec3d toTarget = e.getPos().subtract(caster.getPos()).normalize();
                    double dot = forward.dotProduct(toTarget);
                    return dot > cosThreshold;
                })
                .toList();
    }
    public static void castCustom(SpellContext context) {
        Spell spell = context.spell();

        if (spell.deliver == null || spell.deliver.custom == null) {
            logError(context, "CUSTOM", "Missing custom delivery handler configuration");
            castDirect(context);
            return;
        }

        logCast(context, "CUSTOM");

        try {
            LivingEntity caster = context.caster();
            Entity target = context.target();

            if (target == null) {
                logError(context, "CUSTOM", "No target for AIM-required custom spell");
                return;
            }

            Vec3d targetLocation = target.getPos().add(0.0, target.getHeight() / 2.0, 0.0);
            SpellHelper.ImpactContext impactContext = context.impactContext().position(targetLocation);

            boolean success = deliverForLivingEntity(
                    caster.getWorld(),
                    context.entry(),
                    caster,
                    List.of(new SpellHelper.DeliveryTarget(target, impactContext)),
                    impactContext,
                    targetLocation,
                    spell
            );

            if (success && target instanceof LivingEntity livingTarget) {
                triggerPassiveSpells(caster, livingTarget, context.entry(), false);
                triggerStashedEffects(caster, livingTarget, context.entry());
            }

        } catch (Exception e) {
            logError(context, "CUSTOM", "Failed to cast custom spell: " + e.getMessage());
            GuardVillagers.LOGGER.error("Custom spell cast error", e);
            castDirect(context);
            return;
        }

        context.caster().swingHand(Hand.MAIN_HAND, true);
        playReleaseSound(context);
    }

    static boolean deliverForLivingEntity(
            World world,
            RegistryEntry<Spell> spellEntry,
            LivingEntity caster,
            List<SpellHelper.DeliveryTarget> targets,
            SpellHelper.ImpactContext context,
            Vec3d targetLocation,
            Spell spell
    ) {
        if (spell.deliver.delay > 0) {
            ((net.spell_engine.utils.WorldScheduler) world).schedule(spell.deliver.delay, () -> {
                deliverForLivingEntity(world, spellEntry, caster, targets, context, targetLocation, spell);
            });
            return true;
        }

        boolean delivered = false;

        switch (spell.deliver.type) {
            case CUSTOM -> {
                if (spell.deliver.custom != null) {
                    SpellHandlers.CustomDelivery handler = SpellHandlers.customDelivery.get(spell.deliver.custom.handler);

                    if (handler != null && caster instanceof net.minecraft.entity.player.PlayerEntity player) {
                        delivered = handler.onSpellDelivery(world, spellEntry, player, targets, context, targetLocation);
                    } else {
                        delivered = performCustomImpactsForNonPlayer(
                                world,
                                caster,
                                targets,
                                spellEntry,
                                spell,
                                context
                        );
                    }
                }
            }
            case DIRECT -> {
                Vec3d casterPos = caster.getPos().add(0.0, caster.getHeight() / 2.0, 0.0);

                for (SpellHelper.DeliveryTarget targeted : targets) {
                    Entity target = targeted.entity();
                    Vec3d position = target.getPos()
                            .add(0.0, target.getHeight() / 2.0, 0.0)
                            .lerp(casterPos, 0.001);

                    SpellHelper.ImpactContext targetContext = targeted.context().position(position);

                    boolean result = SpellHelper.performImpacts(
                            world,
                            caster,
                            target,
                            target,
                            spellEntry,
                            effectiveImpacts(caster, spellEntry),
                            targetContext
                    );

                    delivered = delivered || result;
                }
            }
            case PROJECTILE -> {
                for (SpellHelper.DeliveryTarget targeted : targets) {
                    SpellHelper.shootProjectile(
                            world,
                            caster,
                            targeted.entity(),
                            spellEntry,
                            targeted.context()
                    );
                }
                delivered = true;
            }
            case METEOR -> {
                for (SpellHelper.DeliveryTarget targeted : targets) {
                    SpellHelper.fallProjectile(
                            world,
                            caster,
                            targeted.entity(),
                            null,
                            spellEntry,
                            targeted.context()
                    );
                }
                delivered = true;
            }
            case CLOUD -> {
                for (SpellHelper.DeliveryTarget targeted : targets) {
                    SpellHelper.placeCloud(
                            world,
                            caster,
                            targeted.entity(),
                            null,
                            spellEntry,
                            targeted.context()
                    );
                }
                delivered = true;
            }
            default -> {
                // STASH_EFFECT, SHOOT_ARROW handled by other methods
            }
        }

        return delivered;
    }

    private static boolean performCustomImpactsForNonPlayer(
            World world,
            LivingEntity caster,
            List<SpellHelper.DeliveryTarget> targets,
            RegistryEntry<Spell> spellEntry,
            Spell spell,
            SpellHelper.ImpactContext context
    ) {
        boolean anySuccess = false;

        Vec3d casterPos = caster.getPos().add(0.0, caster.getHeight() / 2.0, 0.0);

        for (SpellHelper.DeliveryTarget targeted : targets) {
            Entity target = targeted.entity();
            Vec3d position = target.getPos()
                    .add(0.0, target.getHeight() / 2.0, 0.0)
                    .lerp(casterPos, 0.001);

            SpellHelper.ImpactContext targetContext = targeted.context().position(position);

            boolean success = SpellHelper.performImpacts(
                    world,
                    caster,
                    target,
                    caster,
                    spellEntry,
                    effectiveImpacts(caster, spellEntry),
                    targetContext
            );

            anySuccess = anySuccess || success;
        }

        return anySuccess;
    }
    public static void triggerStashedEffectsPublic(LivingEntity caster, Entity target, RegistryEntry<Spell> spellEntry) {
        triggerStashedEffects(caster, target, spellEntry);
    }

    public static void triggerPassiveSpellsPublic(LivingEntity caster, Entity target, RegistryEntry<Spell> spellEntry, boolean critical) {
        triggerPassiveSpells(caster, target, spellEntry, critical);
    }
    private static void triggerStashedEffects(LivingEntity caster, Entity target, RegistryEntry<Spell> spellEntry) {
        if (!(caster instanceof GuardEntity guard)) {
            return;
        }

        Identifier triggeredSpellId = spellEntry.getKey().get().getValue();

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "🔍 Checking stashed effects for trigger from: " + triggeredSpellId.getPath(),
                    Formatting.DARK_GRAY);
        }

        // Create a copy of the status effects to avoid ConcurrentModificationException
        List<StatusEffectInstance> effectsCopy = new ArrayList<>(caster.getStatusEffects());

        for (var effectInstance : effectsCopy) {
            var effectEntry = effectInstance.getEffectType();
            String effectId = Registries.STATUS_EFFECT.getId(effectEntry.value()).toString();

            var spellRegistry = net.spell_engine.api.spell.registry.SpellRegistry.from(caster.getWorld());
            for (var stashSpellEntry : spellRegistry.streamEntries().toList()) {
                Spell stashSpell = stashSpellEntry.value();

                if (stashSpell.deliver != null &&
                        stashSpell.deliver.type == Spell.Delivery.Type.STASH_EFFECT &&
                        stashSpell.deliver.stash_effect != null &&
                        stashSpell.deliver.stash_effect.id.equals(effectId)) {

                    if (stashSpell.deliver.stash_effect.triggers != null) {
                        for (Spell.Trigger trigger : stashSpell.deliver.stash_effect.triggers) {
                            if (trigger.type == Spell.Trigger.Type.SPELL_IMPACT_SPECIFIC) {
                                if (trigger.spell != null && trigger.spell.id != null) {
                                    if (trigger.spell.id.equals(triggeredSpellId.toString())) {
                                        if (!guard.getWorld().isClient()) {
                                            GuardDebugManager.broadcast(guard,
                                                    "⚡ Triggering stashed effect: " + effectId + " from " + triggeredSpellId.getPath(),
                                                    Formatting.YELLOW);
                                        }

                                        int consumeAmount = stashSpell.deliver.stash_effect.consume;
                                        if (consumeAmount > 0) {
                                            int currentAmplifier = effectInstance.getAmplifier();
                                            int newAmplifier = currentAmplifier - consumeAmount;

                                            caster.removeStatusEffect(effectEntry);
                                            if (newAmplifier >= 0) {
                                                caster.addStatusEffect(new StatusEffectInstance(
                                                        effectEntry,
                                                        effectInstance.getDuration(),
                                                        newAmplifier,
                                                        effectInstance.isAmbient(),
                                                        effectInstance.shouldShowParticles(),
                                                        effectInstance.shouldShowIcon()
                                                ));
                                            }
                                        }

                                        // **FIX**: Always provide a valid position for the impact context
                                        Vec3d impactPosition = target.getPos().add(0.0, target.getHeight() / 2.0, 0.0);
                                        SpellHelper.ImpactContext stashContext = new SpellHelper.ImpactContext()
                                                .power(SpellPower.getSpellPower(stashSpell.school, caster))
                                                .position(impactPosition);  // Essential for area impacts

                                        SpellHelper.performImpacts(
                                                caster.getWorld(),
                                                caster,
                                                target,
                                                caster,
                                                stashSpellEntry,
                                                stashSpell.impacts,
                                                stashContext
                                        );
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void triggerPassiveSpells(LivingEntity caster, Entity target, RegistryEntry<Spell> spellEntry, boolean critical) {
        if (!(caster instanceof GuardEntity guard)) {
            return;
        }

        Identifier triggeredSpellId = spellEntry.getKey().get().getValue();
        Spell triggeredSpell = spellEntry.value();

        List<GuardSpellManager.CategorizedSpell> passives = guard.getSpellManager().getAllPassiveSpells();

        if (!guard.getWorld().isClient() && !passives.isEmpty()) {
            GuardDebugManager.broadcast(guard,
                    "🔍 Checking " + passives.size() + " passive spells for trigger from: " + triggeredSpellId.getPath(),
                    Formatting.GRAY);
        }

        for (GuardSpellManager.CategorizedSpell passive : passives) {
            Spell passiveSpell = passive.entry().value();

            if (passiveSpell.passive == null || passiveSpell.passive.triggers == null) {
                continue;
            }

            for (Spell.Trigger trigger : passiveSpell.passive.triggers) {
                // Check for supported trigger types
                if (trigger.type != Spell.Trigger.Type.SPELL_IMPACT_SPECIFIC &&
                    trigger.type != Spell.Trigger.Type.SPELL_IMPACT_ANY &&
                    trigger.type != Spell.Trigger.Type.SPELL_CAST) {
                    continue;
                }

                // Check if trigger conditions are met
                if (!doesTriggerMatch(trigger, triggeredSpell, triggeredSpellId, critical)) {
                    continue;
                }

                // Apply chance check
                if (trigger.chance > 0 && trigger.chance < 1.0f) {
                    if (guard.getRandom().nextFloat() > trigger.chance) {
                        if (!guard.getWorld().isClient()) {
                            GuardDebugManager.broadcast(guard,
                                    "  🎲 " + passive.spellId().getPath() + " chance failed (" + (trigger.chance * 100) + "%)",
                                    Formatting.GRAY);
                        }
                        continue;
                    }
                }

                if (!guard.getWorld().isClient()) {
                    GuardDebugManager.broadcast(guard,
                            "⚡ Triggering passive: " + passive.spellId().getPath() + " from " + triggeredSpellId.getPath(),
                            Formatting.YELLOW);
                }

                executePassiveSpell(guard, target, passive, passiveSpell);
            }
        }
    }

    /**
     * Check if a trigger matches the triggering spell conditions.
     */
    private static boolean doesTriggerMatch(Spell.Trigger trigger, Spell triggeredSpell, Identifier triggeredSpellId, boolean critical) {
        // If trigger requires a specific spell ID, check it
        if (trigger.spell != null && trigger.spell.id != null && !trigger.spell.id.isEmpty()) {
            if (!trigger.spell.id.equals(triggeredSpellId.toString())) {
                return false;
            }
        }

        // If trigger requires a specific spell type (ACTIVE/PASSIVE), check it
        if (trigger.spell != null && trigger.spell.type != null) {
            if (trigger.spell.type != triggeredSpell.type) {
                return false;
            }
        }

        // If trigger requires a specific spell school, check it
        if (trigger.spell != null && trigger.spell.school != null) {
            if (triggeredSpell.school == null) {
                return false;
            }
            // Compare school IDs
            String triggerSchool = trigger.spell.school;
            String spellSchool = triggeredSpell.school.id.toString();
            if (!spellSchool.equals(triggerSchool) && !spellSchool.contains(triggerSchool)) {
                return false;
            }
        }

        // If trigger requires a specific archetype, check it
        if (trigger.spell != null && trigger.spell.archetype != null) {
            if (triggeredSpell.school == null || triggeredSpell.school.archetype == null) {
                return false;
            }
            if (triggeredSpell.school.archetype != trigger.spell.archetype) {
                return false;
            }
        }

        // If trigger requires critical hit, check it
        if (trigger.impact != null && trigger.impact.critical != null && trigger.impact.critical) {
            if (!critical) {
                return false;
            }
        }

        // If trigger requires a specific impact type (DAMAGE/HEAL), check it
        if (trigger.impact != null && trigger.impact.impact_type != null) {
            boolean hasMatchingImpact = false;
            if (triggeredSpell.impacts != null) {
                for (Spell.Impact impact : triggeredSpell.impacts) {
                    if (impact.action != null && impact.action.type.name().equals(trigger.impact.impact_type)) {
                        hasMatchingImpact = true;
                        break;
                    }
                }
            }
            return hasMatchingImpact;
        }

        return true;
    }

    /**
     * Execute a passive spell that has been triggered.
     */
    private static void executePassiveSpell(GuardEntity guard, Entity target, GuardSpellManager.CategorizedSpell passive, Spell passiveSpell) {
        SpellHelper.ImpactContext passiveContext = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(passiveSpell.school, guard));

        // Handle CLOUD delivery type
        if (passiveSpell.deliver != null && passiveSpell.deliver.type == Spell.Delivery.Type.CLOUD) {
            Vec3d targetPos = target.getPos();
            passiveContext = passiveContext.position(targetPos);

            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  ☁️ Spawning cloud at target position",
                        Formatting.AQUA);
            }

            SpellHelper.placeCloud(
                    guard.getWorld(),
                    guard,
                    target,
                    targetPos,
                    passive.entry(),
                    passiveContext
            );
            return;
        }

        // Handle AREA target type
        if (passiveSpell.target != null && passiveSpell.target.type == Spell.Target.Type.AREA) {
            Vec3d center = target.getPos().add(0.0, target.getHeight() / 2.0, 0.0);
            passiveContext = passiveContext.position(center);

            float effectivePassiveRange = guard.getSpellManager().getAugmentedRange(passive.entry());
            double radius = effectivePassiveRange > 0 ? effectivePassiveRange : 12.0;

            List<LivingEntity> areaTargets = guard.getWorld().getEntitiesByClass(
                    LivingEntity.class,
                    target.getBoundingBox().expand(radius),
                    e -> e != guard && e.isAlive() && guard.canSee(e)
            );

            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  🎯 Area targeting: found " + areaTargets.size() + " targets in radius " + radius,
                        Formatting.GRAY);
            }

            for (LivingEntity areaTarget : areaTargets) {
                boolean success = SpellHelper.performImpacts(
                        guard.getWorld(),
                        guard,
                        areaTarget,
                        guard,
                        passive.entry(),
                        guard.getSpellManager().getAugmentedImpacts(passive.entry()),
                        passiveContext
                );

                if (!guard.getWorld().isClient() && success) {
                    GuardDebugManager.broadcast(guard,
                            "    ✓ Hit: " + areaTarget.getName().getString(),
                            Formatting.GREEN);
                }
            }
            return;
        }

        // Default: direct impact on target
        if (target instanceof LivingEntity livingTarget) {
            SpellHelper.performImpacts(
                    guard.getWorld(),
                    guard,
                    livingTarget,
                    guard,
                    passive.entry(),
                    guard.getSpellManager().getAugmentedImpacts(passive.entry()),
                    passiveContext
            );
        }
    }

    private static void logCast(SpellContext context, String deliveryType) {
        if (context.caster() instanceof GuardEntity guard && !guard.getWorld().isClient()) {
            String spellName = context.spellId().getPath();
            float actualPower = (float) context.impactContext().power().baseValue();

            GuardDebugManager.broadcast(guard,
                    "✨ " + spellName + " [" + deliveryType + "] Power: " + actualPower,
                    Formatting.LIGHT_PURPLE);
        }
    }

    private static void logError(SpellContext context, String deliveryType, String reason) {
        String spellName = context.spellId().toString();
        GuardVillagers.LOGGER.error("Failed to cast spell {} with delivery type {}: {}",
                spellName, deliveryType, reason);

        if (context.caster() instanceof GuardEntity guard && !guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "❌ " + context.spellId().getPath() + " [" + deliveryType + "] - " + reason,
                    Formatting.RED);
        }
    }

    private static void playReleaseSound(SpellContext context) {
        Spell spell = context.spell();
        if (spell.release != null && spell.release.sound != null) {
            String soundIdString = spell.release.sound.id();

            if (soundIdString == null || soundIdString.isEmpty()) {
                return;
            }

            Identifier soundId = Identifier.tryParse(soundIdString);
            if (soundId != null) {
                SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
                if (sound != null) {
                    context.caster().getWorld().playSound(
                            null,
                            context.caster().getBlockPos(),
                            sound,
                            SoundCategory.PLAYERS,
                            1.0F,
                            1.0F
                    );
                }
            }
        }
    }
}