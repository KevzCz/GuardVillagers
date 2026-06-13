package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.*;
import dev.sterner.guardvillagers.common.animation.GuardAnimationDurations;
import dev.sterner.guardvillagers.common.debug.*;
import dev.sterner.guardvillagers.common.entity.*;
import dev.sterner.guardvillagers.mixin.accessor.LivingEntityAccessor;
import net.minecraft.entity.*;
import net.minecraft.entity.effect.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.*;
import net.minecraft.sound.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import net.spell_engine.api.spell.*;
import net.spell_engine.api.spell.event.*;
import net.spell_engine.api.spell.fx.ParticleBatch;
import net.spell_engine.fx.ParticleHelper;
import net.spell_engine.internals.*;
import net.spell_engine.internals.arrow.*;
import net.spell_engine.internals.melee.Melee;
import net.spell_engine.utils.TargetHelper;
import net.spell_power.api.*;
import net.minecraft.entity.mob.MobEntity;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class SpellDelivery {

    private static boolean performImpacts(
            World world,
            LivingEntity caster,
            Entity target,
            Entity source,
            RegistryEntry<Spell> entry,
            List<Spell.Impact> impacts,
            SpellHelper.ImpactContext ctx
    ) {
        boolean success = SpellHelper.performImpacts(world, caster, target, source, entry, impacts, ctx);
        if (target instanceof GuardEntity guard) {
            GuardSpellCooldowns.applyCooldownImpacts(guard, impacts);
        }
        return success;
    }

    private static List<Spell.Impact> effectiveImpacts(LivingEntity caster, RegistryEntry<Spell> spellEntry) {
        if (caster instanceof GuardEntity guard) {
            return guard.getSpellManager().getAugmentedImpacts(spellEntry);
        }
        return spellEntry.value().impacts;
    }

    private static float effectiveRange(LivingEntity caster, RegistryEntry<Spell> spellEntry) {
        return SpellCombatTargeting.resolveBaseRange(caster, spellEntry);
    }

    public static void deliverSpell(SpellContext context, int channelOffset) {
        Spell spell = context.spell();
        World world = context.caster().getWorld();

        if (spell.deliver != null && spell.deliver.delay > 0) {
            if (context.caster() instanceof GuardEntity guard
                    && GuardCastVisuals.holdsCastThroughDelivery(spell)) {
                String holdAnim = GuardCastVisuals.resolveCastAnimationId(guard, spell);
                if (holdAnim == null || holdAnim.isEmpty()) {
                    holdAnim = guard.getCastAnimationId();
                }
                GuardCastVisuals.beginCastPoseHold(guard, holdAnim);
            }
            int delayTicks = spell.deliver.delay;
            ((net.spell_engine.utils.WorldScheduler) world).schedule(delayTicks, () -> {
                if (context.caster().isAlive()) {
                    deliverSpellNow(refreshContextForDelivery(context), channelOffset);
                }
            });
            return;
        }

        deliverSpellNow(context, channelOffset);
    }

    private static void deliverSpellNow(SpellContext context, int channelOffset) {
        context = withChannelContext(context, channelOffset);
        orientCasterTowardTarget(context);

        Spell spell = context.spell();
        Spell.Delivery.Type deliveryType = GuardSpellTimings.effectiveDeliveryType(spell);

        if (deliveryType == null) {
            castDirect(context);
            applyAreaImpactIfPresent(context);
            sendReleaseFx(context);
            triggerGuardReleaseVisuals(context);
            return;
        }

        switch (deliveryType) {
            case MELEE -> castMeleeDelivery(context);
            case SHOOT_ARROW, PROJECTILE -> castProjectile(context, channelOffset);
            case METEOR -> castMeteor(context);
            case CLOUD -> castCloud(context);
            case CUSTOM -> castCustom(context);
            case DIRECT -> {
                if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
                    castBeam(context);
                } else if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
                    castAreaDirect(context);
                    context.caster().swingHand(Hand.MAIN_HAND, true);
                    if (shouldPlayReleaseSound(context)) {
                        GuardSpellSounds.playRelease(context.caster(), spell);
                    }
                } else {
                    castDirect(context);
                }
            }
            case AFFECT_ARROW -> castAffectArrow(context);
            case STASH_EFFECT -> castStashEffect(context);
            default -> castDirect(context);
        }

        applyAreaImpactIfPresent(context);
        sendReleaseFx(context);
        triggerGuardReleaseVisuals(context);
    }

    private static void triggerGuardReleaseVisuals(SpellContext context) {
        if (!(context.caster() instanceof GuardEntity guard)) {
            return;
        }
        Spell spell = context.spell();
        if (!GuardCastVisuals.hasReleaseAnimation(spell)) {
            return;
        }
        if (!GuardSpellTimings.isMeleeDelivery(spell)) {
            GuardCastVisuals.beginReleasePhase(guard, spell);
            return;
        }

        String releaseId = GuardCastVisuals.resolveReleaseAnimationId(guard, spell);
        String meleeAnimId = primaryMeleeAttackAnimationId(guard, spell);
        int deferTicks = meleeStrikeDeferTicks(spell);

        
        if (releaseId != null && releaseId.equals(meleeAnimId) && deferTicks > 0) {
            GuardCastVisuals.scheduleAfterTicks(guard, deferTicks, () -> {
                if (guard.isAlive()) {
                    GuardCastVisuals.beginReleasePhase(guard, spell);
                }
            });
            return;
        }
        
        if (meleeAnimId != null && releaseId != null && !meleeAnimId.equals(releaseId)) {
            GuardCastVisuals.beginReleasePhase(guard, spell);
            return;
        }
        if (meleeAnimId != null) {
            return;
        }
        GuardCastVisuals.beginReleasePhase(guard, spell);
    }

    @Nullable
    private static String primaryMeleeAttackAnimationId(GuardEntity guard, Spell spell) {
        if (spell.deliver == null || spell.deliver.melee == null
                || spell.deliver.melee.attacks == null || spell.deliver.melee.attacks.isEmpty()) {
            return null;
        }
        Spell.Delivery.Melee.Attack attack = spell.deliver.melee.attacks.getFirst();
        if (attack.animation == null) {
            return null;
        }
        return GuardCastVisuals.resolveAnimationId(guard, attack.animation);
    }

    private static int meleeStrikeDeferTicks(Spell spell) {
        if (spell.deliver == null || spell.deliver.melee == null
                || spell.deliver.melee.attacks == null || spell.deliver.melee.attacks.isEmpty()) {
            return 0;
        }
        Spell.Delivery.Melee.Attack attack = spell.deliver.melee.attacks.getFirst();
        return Math.max(0, (int) (attack.delay * 20));
    }

    private static void orientCasterTowardTarget(SpellContext context) {
        if (context.target() == null || !context.target().isAlive()) {
            return;
        }
        LivingEntity caster = context.caster();
        float yaw = GuardMeleeTargeting.resolveStrikeYaw(caster, context.target());
        caster.setYaw(yaw);
        caster.setBodyYaw(yaw);
        caster.setHeadYaw(yaw);
    }

    private static float meleeAttackRange(LivingEntity caster, RegistryEntry<Spell> spellEntry) {
        float range = effectiveRange(caster, spellEntry);
        if (range > 0) {
            return range;
        }
        return 3.0f;
    }

    public static void sendReleaseFx(SpellContext context) {
        if (context.caster().getWorld().isClient()) {
            return;
        }
        Spell spell = context.spell();
        if (spell.release == null) {
            return;
        }
        LivingEntity caster = context.caster();

        ParticleBatch[] releaseParticles = SpellParticleHelper.sanitize(spell.release.particles);
        if (!SpellParticleHelper.isEmpty(releaseParticles)) {
            ParticleHelper.sendBatches(caster, releaseParticles);
        }
        ParticleBatch[] scaledSource = SpellParticleHelper.sanitize(spell.release.particles_scaled_with_ranged);
        if (!SpellParticleHelper.isEmpty(scaledSource)) {
            float range = meleeAttackRange(caster, context.entry());
            ParticleBatch[] scaled = new ParticleBatch[scaledSource.length];
            for (int i = 0; i < scaled.length; i++) {
                scaled[i] = scaledSource[i].copy().scale(range);
            }
            ParticleHelper.sendBatches(caster, scaled);
        }
        if (shouldPlayReleaseSound(context)) {
            GuardSpellSounds.playRelease(caster, spell);
        }
    }

    private static SpellContext refreshContextForDelivery(SpellContext context) {
        LivingEntity caster = context.caster();
        LivingEntity target = context.target();
        if (caster instanceof GuardEntity guard) {
            LivingEntity liveTarget = guard.getTarget();
            if (liveTarget != null && liveTarget.isAlive()) {
                target = liveTarget;
            }
        }
        SpellHelper.ImpactContext base = context.impactContext();
        Vec3d pos = resolveImpactPosition(context);
        SpellHelper.ImpactContext refreshed = new SpellHelper.ImpactContext(
                base.channel(),
                base.distance(),
                pos,
                base.power(),
                base.focusMode(),
                base.channelTickIndex()
        );
        return new SpellContext(
                context.spellId(),
                context.entry(),
                context.spell(),
                caster,
                target,
                refreshed
        );
    }

    private static SpellContext withChannelContext(SpellContext context, int channelTickIndex) {
        Spell spell = context.spell();
        SpellHelper.ImpactContext base = context.impactContext();
        float channelMult = 1f;
        if (SpellHelper.isChanneled(spell) && channelTickIndex >= 0) {
            channelMult = SpellHelper.channelValueMultiplier(spell);
        }
        SpellHelper.ImpactContext updated = new SpellHelper.ImpactContext(
                channelMult,
                base.distance(),
                base.position(),
                base.power(),
                base.focusMode(),
                channelTickIndex
        );
        return new SpellContext(
                context.spellId(),
                context.entry(),
                context.spell(),
                context.caster(),
                context.target(),
                updated
        );
    }

    private static Vec3d resolveImpactPosition(SpellContext context) {
        LivingEntity caster = context.caster();
        Spell spell = context.spell();
        Vec3d pos = caster.getPos();

        if (spell.target != null && spell.target.type == Spell.Target.Type.AIM && spell.target.aim != null) {
            Vec3d aimPoint = context.target() != null
                    ? context.target().getPos().add(0, context.target().getHeight() * 0.5, 0)
                    : caster.getEyePos();
            if (spell.target.aim.reposition_vertically != 0) {
                Vec3d grounded = TargetHelper.findSolidBelow(
                        caster, aimPoint, caster.getWorld(), spell.target.aim.reposition_vertically);
                if (grounded != null) {
                    return grounded;
                }
            }
            return aimPoint;
        }

        if (context.impactContext().position() != null) {
            return context.impactContext().position();
        }
        return pos;
    }

    private static void applyAreaImpactIfPresent(SpellContext context) {
        Spell spell = context.spell();
        if (spell.area_impact == null) {
            return;
        }

        LivingEntity caster = context.caster();
        World world = caster.getWorld();
        Vec3d impactPos = resolveImpactPosition(context);

        Entity exclude = context.target();

        SpellHelper.ImpactContext areaContext = context.impactContext().position(impactPos);

        boolean success = SpellHelper.lookupAndPerformAreaImpact(
                spell.area_impact,
                context.entry(),
                caster,
                exclude,
                caster,
                context.getImpacts(),
                areaContext,
                false
        );

        if (success && exclude instanceof LivingEntity livingTarget) {
            triggerPassiveSpells(caster, livingTarget, context.entry(), false);
            triggerStashedEffects(caster, livingTarget, context.entry());
        }

        if (caster instanceof GuardEntity guard && !world.isClient() && success) {
            float radius = spell.area_impact.combinedRadius(context.impactContext().power().baseValue());
            List<Entity> areaTargets = TargetHelper.targetsFromArea(
                    world,
                    caster,
                    impactPos,
                    caster.getRotationVector(),
                    radius,
                    spell.area_impact.area,
                    SpellCombatTargeting.combatFilter(caster)
            );
            if (exclude != null) {
                areaTargets.remove(exclude);
            }
            GuardDebugManager.broadcast(guard,
                    "💥 Area impact: " + context.spellId().getPath()
                            + " → " + areaTargets.size() + " target(s), r=" + String.format("%.1f", radius),
                    Formatting.GREEN);
        }
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
                GuardSpellArrowDelivery.shoot(context, channelOffset);
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
            orientCasterTowardTarget(context);

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
        orientCasterTowardTarget(context);

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

        logCast(context, "BEAM");

        LivingEntity caster = context.caster();
        World world = caster.getWorld();
        float range = SpellCombatTargeting.scaledRange(
                caster, context.entry(), SpellCombatTargeting.DEFAULT_BEAM_RANGE);

        List<Entity> entities = TargetHelper.targetsFromRaycast(
                caster, range, SpellCombatTargeting.combatFilter(caster));

        int hits = SpellCombatTargeting.deliverImpacts(
                context, entities, null, SpellCombatTargeting.casterCenter(caster), 0);

        if (caster instanceof GuardEntity guard) {
            SpellCombatTargeting.broadcastHitSummary(
                    guard, world, context.spellId().getPath(), "beam", hits, entities.size(), range);
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
        } else if (spell.target != null && (spell.target.type == Spell.Target.Type.AIM
                || spell.target.type == Spell.Target.Type.CASTER)) {
            logCast(context, spell.target.type.name());
            if (hasAssignedSupportTarget(spell, context)) {
                castSingleDirect(context);
            } else {
                castResolvedDirect(context);
            }
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

        LivingEntity caster = context.caster();
        if (!(caster instanceof GuardEntity guard && guard.shouldSuppressVanillaSpellSwing())) {
            caster.swingHand(Hand.MAIN_HAND, true);
        }
        if (shouldPlayReleaseSound(context)) {
            GuardSpellSounds.playRelease(context.caster(), spell);
        }
    }

    private static void playReleaseSound(SpellContext context) {
        if (shouldPlayReleaseSound(context)) {
            GuardSpellSounds.playRelease(context.caster(), context.spell());
        }
    }

    private static boolean shouldPlayReleaseSound(SpellContext context) {
        Spell spell = context.spell();
        if (!SpellHelper.isChanneled(spell)
                || spell.active == null
                || spell.active.cast == null
                || spell.active.cast.channel_ticks <= 0) {
            return true;
        }
        int channelIndex = context.impactContext().channelTickIndex();
        return channelIndex >= spell.active.cast.channel_ticks - 1;
    }

    private static boolean hasAssignedSupportTarget(Spell spell, SpellContext context) {
        LivingEntity assigned = context.target();
        if (assigned == null || !assigned.isAlive()) {
            return false;
        }
        if (spell.impacts == null || spell.impacts.isEmpty()) {
            return false;
        }
        boolean hasHeal = false;
        boolean hasDamage = false;
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null) {
                continue;
            }
            if (impact.action.type == Spell.Impact.Action.Type.HEAL) {
                hasHeal = true;
            }
            if (impact.action.type == Spell.Impact.Action.Type.DAMAGE) {
                hasDamage = true;
            }
        }
        return hasHeal || !hasDamage;
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

            if (impact.action.type == Spell.Impact.Action.Type.SPAWN) {
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
        boolean success = performImpacts(
                context.caster().getWorld(),
                context.caster(),
                context.caster(),
                context.caster(),
                context.entry(),
                context.getImpacts(),
                context.impactContext()
        );

        if (success) {
            registerFreshSpellSummons(context);
            triggerPassiveSpells(context.caster(), context.caster(), context.entry(), false);
            triggerStashedEffects(context.caster(), context.caster(), context.entry());
        }
    }

    private static void registerFreshSpellSummons(SpellContext context) {
        if (!(context.caster() instanceof GuardEntity guard) || guard.getWorld().isClient()) {
            return;
        }
        if (!hasSpawnImpacts(context.spell())) {
            return;
        }

        java.util.Set<Identifier> spawnTypes = collectSpawnEntityTypes(context.spell());
        if (spawnTypes.isEmpty()) {
            return;
        }

        net.minecraft.util.math.Box box = guard.getBoundingBox().expand(6.0);
        for (LivingEntity entity : guard.getWorld().getEntitiesByClass(
                LivingEntity.class, box, candidate -> candidate != guard && candidate.age <= 3)) {
            Identifier typeId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType());
            if (spawnTypes.contains(typeId)) {
                guard.registerSpellSummon(entity);
            }
        }
    }

    private static boolean hasSpawnImpacts(Spell spell) {
        if (spell.impacts == null) {
            return false;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.SPAWN) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<Identifier> collectSpawnEntityTypes(Spell spell) {
        java.util.Set<Identifier> types = new java.util.HashSet<>();
        if (spell.impacts == null) {
            return types;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null || impact.action.type != Spell.Impact.Action.Type.SPAWN
                    || impact.action.spawns == null) {
                continue;
            }
            for (Spell.Impact.Action.Spawn spawn : impact.action.spawns) {
                if (spawn.entity_type_id != null) {
                    Identifier id = Identifier.tryParse(spawn.entity_type_id);
                    if (id != null) {
                        types.add(id);
                    }
                }
            }
        }
        return types;
    }

    private static void castAreaDirect(SpellContext context) {
        Spell spell = context.spell();
        LivingEntity caster = context.caster();
        World world = caster.getWorld();

        // For melee-mechanic spells on guards, spell.range is the effect AoE angle radius, not a search distance.
        // Use meleeAttackRange (≥3.0) so the area search actually finds nearby enemies.
        float range = (caster instanceof GuardEntity && spell.range_mechanic == Spell.RangeMechanic.MELEE)
                ? meleeAttackRange(caster, context.entry()) * caster.getScale()
                : SpellCombatTargeting.scaledRange(caster, context.entry(), meleeAttackRange(caster, context.entry()));
        Spell.Target.Area area = SpellCombatTargeting.targetArea(spell);
        Vec3d casterPos = SpellCombatTargeting.casterCenter(caster);

        List<Entity> entities;
        if (caster instanceof GuardEntity && spell.range_mechanic == Spell.RangeMechanic.MELEE) {
            float strikeYaw = GuardMeleeTargeting.resolveStrikeYaw(caster, context.target());
            List<LivingEntity> meleeTargets = GuardMeleeTargeting.findTargets(
                    caster, context.target(), null, range, strikeYaw, caster.getPitch());
            entities = new java.util.ArrayList<>(meleeTargets);
        } else {
            entities = SpellCombatTargeting.resolveSpellTargets(context);
        }

        int hits = SpellCombatTargeting.deliverImpacts(
                context, entities, area, casterPos, range * range);

        if (caster instanceof GuardEntity guard) {
            SpellCombatTargeting.broadcastHitSummary(
                    guard, world, context.spellId().getPath(), "area", hits, entities.size(), range);
        }
    }

    private static void castResolvedDirect(SpellContext context) {
        LivingEntity caster = context.caster();
        World world = caster.getWorld();
        List<Entity> entities = SpellCombatTargeting.resolveSpellTargets(context);

        if (entities.isEmpty()) {
            castSelfDirect(context);
            return;
        }

        Vec3d center = SpellCombatTargeting.casterCenter(caster);
        int hits = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity target)) {
                continue;
            }
            SpellHelper.ImpactContext impactCtx = context.impactContext()
                    .position(SpellCombatTargeting.impactPosition(target, center));
            if (performImpacts(
                    world,
                    caster,
                    target,
                    target,
                    context.entry(),
                    context.getImpacts(),
                    impactCtx
            )) {
                hits++;
                triggerImpactFollowUps(caster, target, context);
            }
        }

        if (caster instanceof GuardEntity guard) {
            float range = SpellCombatTargeting.scaledRange(
                    caster, context.entry(), meleeAttackRange(caster, context.entry()));
            SpellCombatTargeting.broadcastHitSummary(
                    guard, world, context.spellId().getPath(), "direct", hits, entities.size(), range);
        }
    }

    static void triggerImpactFollowUps(LivingEntity caster, LivingEntity target, SpellContext context) {
        triggerStashedEffects(caster, target, context.entry());
        if (caster instanceof GuardEntity guard
                && target != caster
                && guard.canTarget(target)) {
            triggerPassiveSpells(caster, target, context.entry(), false);
        }
    }

    private static void castSingleDirect(SpellContext context) {
        boolean success = performImpacts(
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
            boolean success = performImpacts(
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

        boolean success = performImpacts(
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
                boolean success = performImpacts(
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
    public static void castAffectArrow(SpellContext context) {
        Spell spell = context.spell();

        if (spell.deliver == null || spell.deliver.affect_arrow == null) {
            logError(context, "AFFECT_ARROW", "Missing affect_arrow configuration - falling back to stash effect");
            castSelfCast(context);
            return;
        }

        logCast(context, "AFFECT_ARROW");

        boolean success = performImpacts(
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

        context.caster().swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
        playReleaseSound(context);
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
                        if (handler != null && caster instanceof GuardEntity guard) {
                            GuardDebugManager.broadcast(guard,
                                    "CUSTOM handler '" + spell.deliver.custom.handler + "' is player-only; using impact fallback",
                                    Formatting.YELLOW);
                        }
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

                    boolean result = performImpacts(
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

            boolean success = performImpacts(
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

        List<StatusEffectInstance> effectsCopy = new ArrayList<>(caster.getStatusEffects());

        for (var effectInstance : effectsCopy) {
            var effectEntry = effectInstance.getEffectType();
            String effectId = Registries.STATUS_EFFECT.getId(effectEntry.value()).toString();

            var spellRegistry = net.spell_engine.api.spell.registry.SpellRegistry.from(caster.getWorld());
            for (var stashSpellEntry : spellRegistry.streamEntries().toList()) {
                Identifier stashSpellId = stashSpellEntry.getKey()
                        .map(key -> key.getValue())
                        .orElse(null);
                if (stashSpellId == null || !guard.getSpellManager().knowsSpell(stashSpellId)) {
                    continue;
                }

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

                                        Vec3d impactPosition = target.getPos().add(0.0, target.getHeight() / 2.0, 0.0);
                                        SpellHelper.ImpactContext stashContext = new SpellHelper.ImpactContext()
                                                .power(SpellPower.getSpellPower(stashSpell.school, caster))
                                                .position(impactPosition);

                                        performImpacts(
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
        if (target instanceof LivingEntity living && living != caster && !guard.canTarget(living)) {
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
                if (trigger.type != Spell.Trigger.Type.SPELL_IMPACT_SPECIFIC &&
                    trigger.type != Spell.Trigger.Type.SPELL_IMPACT_ANY &&
                    trigger.type != Spell.Trigger.Type.SPELL_CAST) {
                    continue;
                }

                if (!doesTriggerMatch(trigger, triggeredSpell, triggeredSpellId, critical)) {
                    continue;
                }

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

    private static boolean doesTriggerMatch(Spell.Trigger trigger, Spell triggeredSpell, Identifier triggeredSpellId, boolean critical) {
        if (trigger.spell != null && trigger.spell.id != null && !trigger.spell.id.isEmpty()) {
            if (!trigger.spell.id.equals(triggeredSpellId.toString())) {
                return false;
            }
        }

        if (trigger.spell != null && trigger.spell.type != null) {
            if (trigger.spell.type != triggeredSpell.type) {
                return false;
            }
        }

        if (trigger.spell != null && trigger.spell.school != null) {
            if (triggeredSpell.school == null) {
                return false;
            }
            String triggerSchool = trigger.spell.school;
            String spellSchool = triggeredSpell.school.id.toString();
            if (!spellSchool.equals(triggerSchool) && !spellSchool.contains(triggerSchool)) {
                return false;
            }
        }

        if (trigger.spell != null && trigger.spell.archetype != null) {
            if (triggeredSpell.school == null || triggeredSpell.school.archetype == null) {
                return false;
            }
            if (triggeredSpell.school.archetype != trigger.spell.archetype) {
                return false;
            }
        }

        if (trigger.impact != null && trigger.impact.critical != null && trigger.impact.critical) {
            if (!critical) {
                return false;
            }
        }

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

    private static Vec3d passiveImpactPosition(GuardEntity guard, @Nullable Entity target) {
        if (target instanceof LivingEntity living) {
            return SpellCombatTargeting.impactPosition(
                    living, SpellCombatTargeting.casterCenter(guard));
        }
        if (target != null) {
            return target.getPos();
        }
        return SpellCombatTargeting.casterCenter(guard);
    }

    private static void executePassiveSpell(GuardEntity guard, Entity target, GuardSpellManager.CategorizedSpell passive, Spell passiveSpell) {
        Vec3d impactPos = passiveImpactPosition(guard, target);
        SpellHelper.ImpactContext passiveContext = new SpellHelper.ImpactContext()
                .power(guard.getSpellManager().getAugmentedPower(passive.entry()))
                .position(impactPos);

        if (!guard.getWorld().isClient()) {
            String deliverLabel = passiveSpell.deliver != null
                    ? passiveSpell.deliver.type.name()
                    : "direct";
            GuardDebugManager.broadcast(guard,
                    "  ⚡ Passive " + passive.spellId().getPath() + " → " + deliverLabel,
                    Formatting.YELLOW);
        }

        if (passiveSpell.deliver != null) {
            executePassiveDelivery(guard, target, passive, passiveSpell, passiveContext, impactPos);
            return;
        }

        deliverPassiveImpacts(guard, target, passive, passiveSpell, passiveContext);
    }

    private static void executePassiveDelivery(
            GuardEntity guard,
            Entity target,
            GuardSpellManager.CategorizedSpell passive,
            Spell passiveSpell,
            SpellHelper.ImpactContext passiveContext,
            Vec3d impactPos
    ) {
        if (passiveSpell.deliver.type == Spell.Delivery.Type.STASH_EFFECT) {
            SpellContext stashContext = new SpellContext(
                    passive.spellId(),
                    passive.entry(),
                    passiveSpell,
                    guard,
                    guard,
                    passiveContext
            );
            castStashEffect(stashContext);
            return;
        }

        List<SpellHelper.DeliveryTarget> deliveryTargets = buildPassiveDeliveryTargets(
                guard, target, passive, passiveSpell, passiveContext);
        Vec3d triggerLocation = passiveSpell.target != null
                && passiveSpell.target.type == Spell.Target.Type.FROM_TRIGGER
                ? impactPos
                : null;

        boolean delivered = deliverForLivingEntity(
                guard.getWorld(),
                passive.entry(),
                guard,
                deliveryTargets,
                passiveContext,
                triggerLocation,
                passiveSpell
        );

        if (!delivered) {
            deliverPassiveImpacts(guard, target, passive, passiveSpell, passiveContext);
        }
    }

    private static List<SpellHelper.DeliveryTarget> buildPassiveDeliveryTargets(
            GuardEntity guard,
            Entity target,
            GuardSpellManager.CategorizedSpell passive,
            Spell passiveSpell,
            SpellHelper.ImpactContext passiveContext
    ) {
        if (passiveSpell.target != null && passiveSpell.target.type == Spell.Target.Type.AREA) {
            SpellContext areaContext = new SpellContext(
                    passive.spellId(),
                    passive.entry(),
                    passiveSpell,
                    guard,
                    target instanceof LivingEntity living ? living : null,
                    passiveContext
            );
            return SpellCombatTargeting.resolveSpellTargets(areaContext).stream()
                    .map(entity -> new SpellHelper.DeliveryTarget(entity, passiveContext))
                    .toList();
        }

        if (passiveSpell.target != null && passiveSpell.target.type == Spell.Target.Type.CASTER) {
            return List.of(new SpellHelper.DeliveryTarget(guard, passiveContext));
        }

        if (target != null) {
            return List.of(new SpellHelper.DeliveryTarget(target, passiveContext));
        }

        return List.of();
    }

    private static void deliverPassiveImpacts(
            GuardEntity guard,
            Entity target,
            GuardSpellManager.CategorizedSpell passive,
            Spell passiveSpell,
            SpellHelper.ImpactContext passiveContext
    ) {
        World world = guard.getWorld();
        Vec3d casterCenter = SpellCombatTargeting.casterCenter(guard);
        List<Spell.Impact> impacts = guard.getSpellManager().getAugmentedImpacts(passive.entry());

        if (passiveSpell.target != null && passiveSpell.target.type == Spell.Target.Type.AREA) {
            SpellContext areaContext = new SpellContext(
                    passive.spellId(),
                    passive.entry(),
                    passiveSpell,
                    guard,
                    target instanceof LivingEntity living ? living : null,
                    passiveContext
            );
            for (Entity entity : SpellCombatTargeting.resolveSpellTargets(areaContext)) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                Vec3d pos = SpellCombatTargeting.impactPosition(living, casterCenter);
                performImpacts(
                        world,
                        guard,
                        living,
                        guard,
                        passive.entry(),
                        impacts,
                        passiveContext.position(pos)
                );
            }
            return;
        }

        if (target instanceof LivingEntity livingTarget) {
            performImpacts(
                    world,
                    guard,
                    livingTarget,
                    guard,
                    passive.entry(),
                    impacts,
                    passiveContext
            );
        }
    }

    public static void castMeleeDelivery(SpellContext context) {
        Spell spell = context.spell();
        LivingEntity caster = context.caster();
        World world = caster.getWorld();

        if (caster instanceof GuardEntity guard && GuardCastVisuals.holdsCastThroughMeleeStrike(spell)) {
            String holdAnim = GuardCastVisuals.resolveCastAnimationId(guard, spell);
            if (holdAnim == null || holdAnim.isEmpty()) {
                holdAnim = guard.getCastAnimationId();
            }
            GuardCastVisuals.beginCastPoseHold(guard, holdAnim);
        }

        if (spell.deliver == null || spell.deliver.melee == null
                || spell.deliver.melee.attacks == null || spell.deliver.melee.attacks.isEmpty()) {
            logError(context, "MELEE", "Missing melee attack list – falling back to castDirect");
            castDirect(context);
            return;
        }

        logCast(context, "MELEE");

        List<Spell.Delivery.Melee.Attack> attacks = spell.deliver.melee.attacks;
        boolean channeled = SpellHelper.isChanneled(spell) && context.impactContext().isChanneled();
        if (channeled) {
            int index = Math.floorMod(context.impactContext().channelTickIndex(), attacks.size());
            attacks = List.of(attacks.get(index));
        }

        Set<LivingEntity> alreadyHit = Collections.synchronizedSet(new HashSet<>());

        if (!channeled && attacks.size() > 1) {
            scheduleChainedMeleeAttacks(context, attacks, world);
            return;
        }

        for (Spell.Delivery.Melee.Attack attack : attacks) {
            scheduleMeleeAttack(context, attack, alreadyHit, world, Math.max(0, (int) (attack.delay * 20)));
        }
    }

    private static void scheduleChainedMeleeAttacks(SpellContext context,
                                                    List<Spell.Delivery.Melee.Attack> attacks,
                                                    World world) {
        int scheduleAt = 0;
        for (Spell.Delivery.Melee.Attack attack : attacks) {
            scheduleAt += Math.max(0, (int) (attack.delay * 20));
            Set<LivingEntity> attackHits = Collections.synchronizedSet(new HashSet<>());
            scheduleMeleeAttack(context, attack, attackHits, world, scheduleAt);
        }
    }

    private static void scheduleMeleeAttack(SpellContext context,
                                            Spell.Delivery.Melee.Attack attack,
                                            Set<LivingEntity> alreadyHit,
                                            World world,
                                            int primaryDelayTicks) {
        LivingEntity caster = context.caster();

        if (attack.particles != null && attack.particles.length > 0) {
            if (primaryDelayTicks <= 0) {
                ParticleHelper.sendBatches(caster, attack.particles);
            } else {
                ((net.spell_engine.utils.WorldScheduler) world).schedule(primaryDelayTicks,
                        () -> ParticleHelper.sendBatches(caster, attack.particles));
            }
        }

        Runnable primaryStrike = () -> executeMeleeAttack(
                refreshContextForDelivery(context), attack, alreadyHit, world, true);

            if (primaryDelayTicks <= 0) {
                primaryStrike.run();
            } else {
                ((net.spell_engine.utils.WorldScheduler) world).schedule(primaryDelayTicks, primaryStrike);
            }

            if (attack.additional_strikes > 0) {
                float additionalStrikeDelay = attack.additional_strike_delay > 0
                        ? attack.additional_strike_delay : 0.1f;
                for (int i = 1; i <= attack.additional_strikes; i++) {
                    final int strikeNum = i;
                    int extraDelayTicks = primaryDelayTicks + (int) (strikeNum * additionalStrikeDelay * 20);
                    ((net.spell_engine.utils.WorldScheduler) world).schedule(extraDelayTicks, () -> {
                        if (caster.isAlive()) {
                        executeMeleeAttack(refreshContextForDelivery(context), attack, alreadyHit, world, false);
                        }
                    });
                }
            }
        }

    private static Melee.Attack toMeleeAttack(SpellContext context, Spell.Delivery.Melee.Attack attack) {
        float range = meleeAttackRange(context.caster(), context.entry());

        String attackId = attack.id != null ? attack.id : "";
        return new Melee.Attack(
                attack.duration,
                Math.max(0, (int) (attack.delay * 20)),
                attack.additional_strikes,
                Math.max(1, (int) (attack.additional_strike_delay * 20)),
                attack.additional_hits_on_same_target,
                attack.attack_speed_multiplier > 0 ? attack.attack_speed_multiplier : 1.0f,
                attack.forward_momentum,
                attack.allow_momentum_airborne,
                attack.movement_speed,
                attack.movement_slipperiness,
                range,
                attack.hitbox,
                attack.animation,
                Melee.AttackContext.of(context.spellId(), attackId)
        );
    }

    private static void executeMeleeAttack(SpellContext context,
                                           Spell.Delivery.Melee.Attack attack,
                                           Set<LivingEntity> alreadyHit,
                                           World world,
                                           boolean playAttackAnimation) {
        LivingEntity caster = context.caster();
        if (!caster.isAlive()) return;

        orientCasterTowardTarget(context);

        float strikeYaw = GuardMeleeTargeting.resolveStrikeYaw(caster, context.target());
        float attackRange = meleeAttackRange(caster, context.entry());

        Entity focus = context.target();
        List<LivingEntity> candidates = GuardMeleeTargeting.findTargets(
                caster,
                focus,
                attack.hitbox,
                attackRange,
                strikeYaw,
                caster.getPitch()
        );

        List<Spell.Impact> impacts = context.getImpacts();
        boolean hasSpellImpacts = impacts != null && !impacts.isEmpty();
        int impactSoundCap = attack.impact_sound_cap > 0 ? attack.impact_sound_cap : 999;

        for (LivingEntity target : candidates) {
            if (!attack.additional_hits_on_same_target && alreadyHit.contains(target)) {
                continue;
            }

            Vec3d impactPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
            SpellHelper.ImpactContext impactCtx = context.impactContext().position(impactPos);

            boolean weaponHit = false;
            boolean spellHit = false;

            clearMeleeDamageInvulnerability(target);

            if (caster instanceof GuardEntity guard) {
                weaponHit = guard.trySpellMeleeWeaponHit(target);
            } else if (caster instanceof MobEntity mob) {
                weaponHit = mob.tryAttack(target);
            }

            if (hasSpellImpacts) {
                spellHit = SpellHelper.meleeImpact(caster, List.of(target), context.entry(), impactCtx);
            }

            if (weaponHit || spellHit) {
                alreadyHit.add(target);
                triggerPassiveSpells(caster, target, context.entry(), false);
                triggerStashedEffects(caster, target, context.entry());

                if (attack.impact_sound != null && attack.impact_sound.id() != null && impactSoundCap > 0) {
                    Identifier soundId = Identifier.tryParse(attack.impact_sound.id());
                    if (soundId != null) {
                        var soundEntry = Registries.SOUND_EVENT.getEntry(soundId);
                        if (soundEntry.isPresent()) {
                            world.playSound(null, target.getBlockPos(), soundEntry.get().value(), SoundCategory.PLAYERS, 1.0F, 1.0F);
                            impactSoundCap--;
                        }
                    }
                }

                if (caster instanceof GuardEntity guard && !world.isClient()) {
                    String dmgType = spellHit && weaponHit ? "spell+weapon"
                            : spellHit ? "spell" : "weapon";
                    String attackLabel = SpellCombatTargeting.meleeAttackLabel(guard, attack);
                    GuardDebugManager.broadcast(guard,
                            "⚔️ " + context.spellId().getPath() + " (" + attackLabel + ", " + dmgType + ") → "
                                    + target.getName().getString(),
                            Formatting.GREEN);
                }
            }
        }

        if (playAttackAnimation && attack.animation != null && caster instanceof GuardEntity guard) {
            String attackAnimId = GuardCastVisuals.resolveAnimationId(guard, attack.animation);
            if (attackAnimId != null) {
                String releaseAnimId = null;
                if (context.spell().release != null && context.spell().release.animation != null) {
                    releaseAnimId = GuardCastVisuals.resolveReleaseAnimationId(guard, context.spell());
                }
                
                if (releaseAnimId == null || !attackAnimId.equals(releaseAnimId)) {
                    GuardCastVisuals.playPerAttackSwing(guard, attack);
                }
            }
        }

        float attackArc = (attack.hitbox != null && attack.hitbox.arc > 0) ? attack.hitbox.arc : 120.0f;
        boolean allowAirborne = attack.allow_momentum_airborne
                || (context.spell().deliver != null
                && context.spell().deliver.melee != null
                && context.spell().deliver.melee.allow_airborne);

        if (playAttackAnimation && attackArc < 270f && caster.isAlive() && (caster.isOnGround() || allowAirborne)
                && attack.forward_momentum > 0) {
            Vec3d direction = new Vec3d(0, 0, 1)
                    .rotateY((float) Math.toRadians(-strikeYaw))
                    .multiply(attack.forward_momentum);
            caster.addVelocity(direction.x, 0, direction.z);
            caster.velocityModified = true;
        }

        if (caster instanceof GuardEntity guard && attack.movement_slipperiness > 0) {
            Melee.Attack meleeAttack = toMeleeAttack(context, attack);
            int durationTicks = meleeAttack.duration() > 0
                    ? meleeAttack.duration()
                    : Math.max(10, (int) (attack.delay * 20) + 5);
            Melee.Attack timedAttack = new Melee.Attack(
                    durationTicks,
                    meleeAttack.delay(),
                    meleeAttack.additional_strikes(),
                    meleeAttack.additional_strike_delay(),
                    meleeAttack.additional_hits_on_same_target(),
                    meleeAttack.speed(),
                    meleeAttack.forward_momentum(),
                    meleeAttack.allow_momentum_airborne(),
                    meleeAttack.movement_speed(),
                    meleeAttack.movement_slip(),
                    meleeAttack.range(),
                    meleeAttack.hitbox(),
                    meleeAttack.animation(),
                    meleeAttack.context()
            );
            guard.setMeleeSkillAttack(new Melee.ActiveAttack(
                    timedAttack,
                    caster.age,
                    guard.getMainHandStack().getItem()
            ));
        }

        if (playAttackAnimation && attack.swing_sound != null && attack.swing_sound.id() != null) {
            Identifier soundId = Identifier.tryParse(attack.swing_sound.id());
            if (soundId != null) {
                Registries.SOUND_EVENT.getEntry(soundId).ifPresent(entry ->
                        world.playSound(null, caster.getBlockPos(), entry.value(), SoundCategory.PLAYERS, 1.0F, 1.0F));
            }
        }

        if (!(caster instanceof GuardEntity guard && guard.shouldSuppressVanillaSpellSwing())) {
        caster.swingHand(Hand.MAIN_HAND, true);
        }
    }

    private static void clearMeleeDamageInvulnerability(LivingEntity target) {
        ((LivingEntitySpellMeleeAccess) target).guardvillagers$clearMeleeDamageInvulnerability();
    }

    private static void logCast(SpellContext context, String deliveryType) {
        if (context.caster() instanceof GuardEntity guard
                && !guard.getWorld().isClient()
                && GuardDebugManager.hasWatchers(guard)) {
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

}