package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.GuardCombatRole;
import dev.sterner.guardvillagers.common.ai.GuardWeaponArchetype;
import dev.sterner.guardvillagers.common.ai.HolyZoneHelper;
import dev.sterner.guardvillagers.common.ai.SpellbladeCombatHelper;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import dev.sterner.guardvillagers.common.ai.SupportAreaOptimizer;
import dev.sterner.guardvillagers.common.ai.SupportBacklineHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseHealerGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardCastVisuals;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardSpellEffectHelper;
import dev.sterner.guardvillagers.common.entity.goal.spell.SupportSpellCasting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class GuardSupportCasterGoal extends BaseHealerGoal {

    private static final float HEAL_CAP_COMBAT = 0.82F;
    private static final float HEAL_CAP_PEACE = 1.0F;
    private static final float ALLY_SCAN = 16.0F;
    private static final float CLUSTER_SCAN = 12.0F;
    private static final float SINGLE_HEAL_RANGE = 6.5F;
    private static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);

    private static final Identifier ID_CIRCLE = Identifier.of("paladins", "circle_of_healing");
    private static final Identifier ID_HEAL = Identifier.of("paladins", "heal");
    private static final Identifier ID_HSHOCK = Identifier.of("paladins", "holy_shock");
    private static final Identifier ID_BARRIER = Identifier.of("paladins", "barrier");

    private static final int TTL_BARRIER = 20 * 10;
    private static final float BARRIER_RADIUS = 5.0F;
    private static final float HSHOCK_RANGE = 6.0F;

    private enum CastIntent {
        NONE,
        SINGLE_HEAL,
        AREA_HEAL,
        AREA_BUFF,
        BARRIER,
        HOLY_SHOCK,
        HYBRID_SONG
    }

    private int pathDelay;
    @Nullable
    private MarkerEntity cachedHolyZone;
    private boolean holyZoneCachedThisTick;

    private Runnable pendingCast;
    private CastIntent pendingIntent = CastIntent.NONE;
    @Nullable
    private LivingEntity pendingSpellTarget;
    private Vec3d positioningTarget;
    private int positioningTicks;

    public GuardSupportCasterGoal(GuardEntity guard) {
        super(guard);
    }

    private GuardCombatRole.SupportRole role() {
        return GuardCombatRole.resolve(guard);
    }

    @Override
    public boolean canStart() {
        if (guard.isSpellCastBusy()) {
            return false;
        }
        if (guard.spellCastGraceTicks > 0) {
            return false;
        }
        if (guard.getSpellManager().hasCastablePhysicalMeleeSpell()) {
            return false;
        }
        if (guard.getSpellManager().shouldUseMeleeWeaponCasting()) {
            return false;
        }
        if (GuardWeaponArchetype.resolve(guard.getMainHandStack()) == GuardWeaponArchetype.Role.MELEE) {
            return false;
        }
        if (GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (SpellbladeCombatHelper.isActive(guard)) {
            return false;
        }
        if (!GuardCombatRole.isDedicatedSupport(guard) && shouldDeferToOffensiveCaster()) {
            return false;
        }
        if (pendingCast != null || positioningTicks > 0) {
            return true;
        }
        return hasSupportWork(peacefulMode());
    }

    @Override
    public boolean shouldContinue() {
        if (guard.isCastingMeleeSpell()) {
            return false;
        }
        if (guard.getSpellManager().shouldUseMeleeWeaponCasting()) {
            return false;
        }
        if (GuardWeaponArchetype.resolve(guard.getMainHandStack()) == GuardWeaponArchetype.Role.MELEE) {
            return false;
        }
        if (GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (SpellbladeCombatHelper.isActive(guard)) {
            return false;
        }
        if (!GuardCombatRole.isDedicatedSupport(guard) && shouldDeferToOffensiveCaster()) {
            return false;
        }
        if (pendingCast != null || positioningTicks > 0) {
            return true;
        }
        return hasSupportWork(peacefulMode());
    }

    @Override
    public void stop() {
        super.stop();
        pendingCast = null;
        pendingIntent = CastIntent.NONE;
        positioningTarget = null;
        positioningTicks = 0;
        pathDelay = 0;
        pendingSpellTarget = null;
        cachedHolyZone = null;
        holyZoneCachedThisTick = false;
        guard.setChannelTickIndex(0);
        this.setControls(EnumSet.noneOf(Control.class));
    }

    @Override
    public void tick() {
        cachedHolyZone = HolyZoneHelper.findNearby(guard, 32.0D);
        holyZoneCachedThisTick = true;
        LivingEntity enemy = guard.getTarget();
        if (currentSpellId != null && !guard.getSpellManager().knowsSpell(currentSpellId)) {
            pendingCast = null;
            pendingIntent = CastIntent.NONE;
            pendingSpellTarget = null;
            positioningTarget = null;
            positioningTicks = 0;
            abortActiveCast();
        }
        tickCooldowns();

        boolean activeSupport = pendingCast != null
                || positioningTicks > 0
                || hasSupportWork(peacefulMode());
        updateControls(activeSupport);

        if (handlePendingCast(enemy)) {
            return;
        }

        if (positioningTicks > 0 && positioningTarget != null) {
            tickPositioning(enemy);
            return;
        }

        boolean combat = inCombat();
        boolean anchor = combat && SupportBacklineHelper.shouldAnchorForRetreatingAllies(guard);

        if (combat) {
            softZoneLeash();
            if (!anchor) {
                tickBacklineMovement(enemy);
            } else {
                guard.getNavigation().stop();
            }
            if (activeSupport && tryCombatSupport(enemy, anchor)) {
                return;
            }
            return;
        }

        if (steerTowardHolyZone()) {
            tryPeacefulSupport();
            return;
        }
        tryPeacefulSupport();
    }

    private void updateControls(boolean activeSupport) {
        if (inCombat() || activeSupport || pendingCast != null || positioningTicks > 0) {
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        } else {
            this.setControls(EnumSet.noneOf(Control.class));
        }
    }

    private boolean inCombat() {
        LivingEntity enemy = guard.getTarget();
        return enemy != null && enemy.isAlive();
    }

    private boolean shouldDeferToOffensiveCaster() {
        if (!guard.getSpellManager().mainHandSupportsMagicCasting()) {
            return false;
        }
        LivingEntity enemy = guard.getTarget();
        return enemy != null && enemy.isAlive()
                && guard.getSpellManager().getBestCastableCombatSong(enemy).isPresent();
    }

    private boolean peacefulMode() {
        return !inCombat();
    }

    private boolean hasSupportWork(boolean peaceful) {
        boolean canHeal = guard.getSpellManager().hasHealingSpells();
        boolean canBuff = guard.getSpellManager().hasSupportBuffSpells();
        if (!canHeal && !canBuff && !GuardCombatRole.isDedicatedSupport(guard)) {
            return false;
        }
        if (canHeal && guard.getHealth() < guard.getMaxHealth()) {
            return true;
        }
        if (canHeal && hasHurtAllyNearby(ALLY_SCAN)) {
            return true;
        }
        if (canHeal && SupportBacklineHelper.shouldAnchorForRetreatingAllies(guard)) {
            return true;
        }
        if (guard.getSpellManager().getBestCastableSupportBuffSpell().isEmpty()) {
            return false;
        }
        if (!peaceful) {
            return true;
        }
        return !findNearbyAllyList(ALLY_SCAN).isEmpty()
                || guard.getHealth() / guard.getMaxHealth() < 0.98F;
    }

    private boolean tryCombatSupport(LivingEntity enemy, boolean anchor) {
        if (tryAreaHeal(CLUSTER_SCAN, HEAL_CAP_COMBAT, anchor)) return true;
        if (tryAreaBuff(CLUSTER_SCAN, anchor)) return true;
        if (trySingleHeal(HEAL_CAP_COMBAT, ALLY_SCAN, anchor)) return true;
        if (role() == GuardCombatRole.SupportRole.PRIEST && tryBarrier(anchor)) return true;
        if (!hasHurtAllyNearby(ALLY_SCAN)) {
            if (tryHybridSong(enemy, anchor)) return true;
            if (role() == GuardCombatRole.SupportRole.PRIEST && tryHolyShock(enemy, anchor)) return true;
        }
        return false;
    }

    private void tryPeacefulSupport() {
        guard.getNavigation().stop();
        trySingleHeal(HEAL_CAP_PEACE, ALLY_SCAN, true);
        tryAreaHeal(CLUSTER_SCAN, HEAL_CAP_PEACE, true);
        tryAreaBuff(CLUSTER_SCAN, true);
        if (role() == GuardCombatRole.SupportRole.PRIEST) {
            tryBarrier(true);
        }
    }

    private boolean trySingleHeal(float cap, float radius, boolean anchor) {
        if (pendingCast != null) return false;
        return findHealSpell().map(spell -> {
            if (isSpellOnCooldown(spell.spellId())) return false;
            LivingEntity target = findBestHealableAlly(radius, cap);
            if (target == null || !insideHolyZone()) return false;
            if (guard.distanceTo(target) > SINGLE_HEAL_RANGE) {
                schedulePathTo(target.getPos(), 1.1D);
                return true;
            }
            beginCast(spell.spellId(), spell.entry(), CastIntent.SINGLE_HEAL, anchor, target, () ->
                    finishCast(spell.spellId(), spell.entry().value()));
            return true;
        }).orElse(false);
    }

    private boolean tryAreaHeal(float clusterRadius, float cap, boolean anchor) {
        if (pendingCast != null) return false;
        return findAreaHealSpell().map(spell -> {
            if (isSpellOnCooldown(spell.spellId())) return false;

            List<LivingEntity> hurt = collectHurtAllies(clusterRadius, cap);
            if (hurt.isEmpty() || !insideHolyZone()) return false;

            float effectRadius = SupportAreaOptimizer.resolveEffectRadius(spell.entry().value());
            Optional<Vec3d> optimal = SupportAreaOptimizer.bestPosition(
                    guard, hurt, effectRadius, SupportAreaOptimizer.DEFAULT_MAX_MOVE);

            if (optimal.isPresent() && guard.getPos().squaredDistanceTo(optimal.get()) > 1.0D) {
                startPositioning(optimal.get());
                return true;
            }

            if (SupportAreaOptimizer.alliesInRadius(guard.getPos(), hurt, effectRadius) < 1) {
                LivingEntity nearest = hurt.stream()
                        .min(java.util.Comparator.comparingDouble(guard::squaredDistanceTo))
                        .orElse(null);
                if (nearest != null) {
                    schedulePathTo(nearest.getPos(), 1.1D);
                    return true;
                }
                return false;
            }

            beginCast(spell.spellId(), spell.entry(), CastIntent.AREA_HEAL, anchor, null, () ->
                    finishCast(spell.spellId(), spell.entry().value()));
            return true;
        }).orElse(false);
    }

    private boolean tryAreaBuff(float clusterRadius, boolean anchor) {
        if (pendingCast != null) return false;
        Optional<GuardSpellManager.CategorizedSpell> opt = guard.getSpellManager().getBestCastableSupportBuffSpell();
        if (opt.isEmpty()) return false;

        GuardSpellManager.CategorizedSpell spell = opt.get();
        if (isSpellOnCooldown(spell.spellId())) return false;

        Spell data = spell.entry().value();
        if (GuardSpellEffectHelper.alreadyAffected(guard, data)) {
            return false;
        }
        if (data.target == null || data.target.type != Spell.Target.Type.AREA) {
            return trySelfOrAllyBuff(spell, anchor);
        }

        List<LivingEntity> allies = findNearbyAllyList(ALLY_SCAN);
        if (allies.size() < 2) {
            return trySelfOrAllyBuff(spell, anchor);
        }
        if (GuardSpellEffectHelper.allAlliesAlreadyAffected(allies, data)) {
            return false;
        }

        float radius = SupportAreaOptimizer.resolveEffectRadius(data);
        Optional<Vec3d> optimal = SupportAreaOptimizer.bestPosition(guard, allies, radius, SupportAreaOptimizer.DEFAULT_MAX_MOVE);
        if (optimal.isPresent() && guard.getPos().squaredDistanceTo(optimal.get()) > 1.0D) {
            startPositioning(optimal.get());
            return true;
        }

        beginCast(spell.spellId(), spell.entry(), CastIntent.AREA_BUFF, anchor, null, () ->
                finishCast(spell.spellId(), data));
        return true;
    }

    private boolean trySelfOrAllyBuff(GuardSpellManager.CategorizedSpell spell, boolean anchor) {
        Spell data = spell.entry().value();
        LivingEntity target = resolveBuffTarget(data);
        if (target == null || GuardSpellEffectHelper.alreadyAffected(target, data)) {
            return false;
        }
        beginCast(spell.spellId(), spell.entry(), CastIntent.AREA_BUFF, anchor, target, () ->
                finishCast(spell.spellId(), data));
        return true;
    }

    private boolean tryBarrier(boolean anchor) {
        if (pendingCast != null || anchor) return false;
        if (!hasBarrierSpell()) return false;
        if (!hasHurtAllyNearby(ALLY_SCAN) && guard.getHealth() / guard.getMaxHealth() > 0.9F) {
            return false;
        }
        return findOwned(ID_BARRIER).or(() -> findOwnedByPath("barrier")).map(spell -> {
            if (isSpellOnCooldown(spell.spellId())) return false;
            beginCast(spell.spellId(), spell.entry(), CastIntent.BARRIER, true, null, () -> {
                spawnBarrierMarker(guard.getPos());
                finishCast(spell.spellId(), spell.entry().value());
            });
            return true;
        }).orElse(false);
    }

    private boolean tryHolyShock(LivingEntity enemy, boolean anchor) {
        if (pendingCast != null || anchor || enemy instanceof PlayerEntity) return false;
        if (guard.distanceTo(enemy) > HSHOCK_RANGE) return false;
        return findOwned(ID_HSHOCK).or(() -> findOwnedByPath("holy_shock")).map(spell -> {
            if (isSpellOnCooldown(spell.spellId())) return false;
            beginCast(spell.spellId(), spell.entry(), CastIntent.HOLY_SHOCK, false, enemy, () ->
                    finishCast(spell.spellId(), spell.entry().value()));
            return true;
        }).orElse(false);
    }

    private boolean tryHybridSong(LivingEntity enemy, boolean anchor) {
        if (pendingCast != null || anchor || hasHurtAllyNearby(ALLY_SCAN)) return false;
        if (guard.getSpellManager().getBestCastableCombatSong(enemy).isPresent()) {
            return false;
        }
        return guard.getSpellManager().getBestCastableHybridAreaSong().map(spell -> {
            if (isSpellOnCooldown(spell.spellId())) return false;
            Spell data = spell.entry().value();
            if (!guard.getSpellManager().inCastRange(enemy, data)) return false;
            LivingEntity spellTarget = isAreaSpell(data) ? null : enemy;
            beginCast(spell.spellId(), spell.entry(), CastIntent.HYBRID_SONG, false, spellTarget, () ->
                    finishCast(spell.spellId(), data));
            return true;
        }).orElse(false);
    }

    private void beginCast(
            Identifier spellId,
            RegistryEntry<Spell> entry,
            CastIntent intent,
            boolean anchor,
            @Nullable LivingEntity spellTarget,
            Runnable onComplete
    ) {
        pendingIntent = intent;
        pendingSpellTarget = spellTarget;
        pendingCast = onComplete;
        currentSpellId = spellId;
        cachedSpellEntry = entry;
        beginSpellCast(spellId, entry, false);
        if (anchor) {
            guard.getNavigation().stop();
        }
    }

    private void finishCast(Identifier spellId, Spell spell) {
        guard.stopUsingItem();
        guard.setActiveBeam(null);
        if (GuardCastVisuals.hasReleaseAnimation(spell)) {
            GuardCastVisuals.beginReleasePhase(guard, spell);
        }
        GuardCastVisuals.completeCastWithRelease(guard, spell);
        scheduleSpellCooldownOnComplete(spellId, spell);
        cachedSpellEntry = null;
        currentSpellId = null;
    }

    private boolean handlePendingCast(@Nullable LivingEntity enemy) {
        if (pendingCast == null) return false;

        boolean anchor = pendingIntent == CastIntent.BARRIER
                || SupportBacklineHelper.shouldAnchorForRetreatingAllies(guard);

        if (enemy != null && enemy.isAlive()) {
            SupportBacklineHelper.SpellMovementProfile profile = cachedSpellEntry != null
                    ? SupportBacklineHelper.SpellMovementProfile.from(cachedSpellEntry.value())
                    : null;
            SupportBacklineHelper.applyCastMovement(guard, enemy, profile, anchor);
        } else if (anchor) {
            guard.getNavigation().stop();
        }

        if (!guard.isUsingItem()) {
            guard.setCurrentHand(Hand.MAIN_HAND);
            guard.setCastingSpell(true);
        }

        if (cachedSpellEntry != null && windUpTicks % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (windUpTicks > 0) {
            windUpTicks--;
            return true;
        }

        if (cachedSpellEntry != null && isChanneled) {
            LivingEntity lookTarget = SupportSpellCasting.resolveLookTarget(pendingSpellTarget, enemy);
            tickChanneledCast(lookTarget, pendingSpellTarget, this::completeSupportCast);
            return true;
        }

        Runnable cast = pendingCast;
        Spell spell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
        if (!isChanneled && currentSpellId != null && cachedSpellEntry != null) {
            SupportSpellCasting.deliver(guard, currentSpellId, cachedSpellEntry, pendingSpellTarget, 0);
        }
        pendingCast = null;
        pendingIntent = CastIntent.NONE;
        pendingSpellTarget = null;
        if (cast != null) {
            cast.run();
        }
        if (spell == null) {
            guard.stopUsingItem();
            guard.setCastingSpell(false);
        }
        return true;
    }

    private void completeSupportCast() {
        Spell spell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
        Identifier spellId = currentSpellId;
        pendingCast = null;
        pendingIntent = CastIntent.NONE;
        pendingSpellTarget = null;
        guard.setActiveBeam(null);
        guard.setChannelTickIndex(0);
        channelHitsDelivered = 0;
        castingDelayTicks = 0;
        isChanneled = false;
        channelTicksLeft = 0;
        if (spell != null && spellId != null) {
            finishCast(spellId, spell);
        } else {
            guard.stopUsingItem();
            guard.setCastingSpell(false);
        }
    }

    private void startPositioning(Vec3d target) {
        Vec3d clamped = SupportAreaOptimizer.clampToMoveBudget(
                guard.getPos(), target, SupportAreaOptimizer.DEFAULT_MAX_MOVE);
        positioningTarget = clamped != null ? clamped : target;
        positioningTicks = 12;
        schedulePathTo(positioningTarget, 1.15D);
    }

    private void tickPositioning(@Nullable LivingEntity enemy) {
        positioningTicks--;
        if (positioningTarget != null) {
            schedulePathTo(positioningTarget, 1.15D);
            if (guard.getPos().squaredDistanceTo(positioningTarget) <= 1.5D) {
                positioningTicks = 0;
                positioningTarget = null;
            }
        }
        if (enemy != null) {
            guard.getLookControl().lookAt(enemy, 30.0F, 30.0F);
        }
    }

    private void tickBacklineMovement(LivingEntity enemy) {
        SupportBacklineHelper.desiredBacklinePosition(guard, enemy).ifPresent(pos -> {
            if (guard.getPos().squaredDistanceTo(pos) > 4.0D) {
                schedulePathTo(pos, 1.0D);
            } else {
                guard.getNavigation().stop();
            }
        });
        guard.getLookControl().lookAt(enemy, 30.0F, 30.0F);
    }

    private void schedulePathTo(Vec3d pos, double speed) {
        if (--pathDelay > 0) return;
        guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, speed);
        pathDelay = PATH_DELAY.get(guard.getRandom());
    }

    private MarkerEntity tickZone() {
        if (!holyZoneCachedThisTick) {
            cachedHolyZone = HolyZoneHelper.findNearby(guard, 32.0D);
        }
        return cachedHolyZone;
    }

    private boolean steerTowardHolyZone() {
        MarkerEntity zone = tickZone();
        if (zone == null) return false;
        float radius = HolyZoneHelper.radiusOf(zone, BARRIER_RADIUS);
        if (!HolyZoneHelper.inside(guard, zone, radius)) {
            if (--pathDelay <= 0) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zone, radius, 1.1D);
                pathDelay = PATH_DELAY.get(guard.getRandom());
            }
            return true;
        }
        HolyZoneHelper.softLeashInside(guard, zone, radius);
        return false;
    }

    private void softZoneLeash() {
        MarkerEntity zone = tickZone();
        if (zone == null) return;
        HolyZoneHelper.softLeashInside(guard, zone, HolyZoneHelper.radiusOf(zone, BARRIER_RADIUS));
    }

    private boolean insideHolyZone() {
        MarkerEntity zone = tickZone();
        if (zone == null) return true;
        return HolyZoneHelper.inside(guard, zone, HolyZoneHelper.radiusOf(zone, BARRIER_RADIUS));
    }

    private List<LivingEntity> findNearbyAllyList(float radius) {
        return guard.getWorld().getEntitiesByClass(
                LivingEntity.class,
                guard.getBoundingBox().expand(radius),
                e -> isAlly(guard, e) && e.isAlive()
        );
    }

    private List<LivingEntity> collectHurtAllies(float radius, float cap) {
        List<LivingEntity> hurt = new ArrayList<>();
        for (LivingEntity ally : findNearbyAllyList(radius)) {
            if (ally.getHealth() / ally.getMaxHealth() < cap) {
                hurt.add(ally);
            }
        }
        if (guard.getHealth() / guard.getMaxHealth() < cap) {
            hurt.add(guard);
        }
        return hurt;
    }

    @Nullable
    private LivingEntity resolveBuffTarget(Spell spell) {
        if (spell.target == null) return guard;
        return switch (spell.target.type) {
            case CASTER -> guard;
            case AREA -> null;
            default -> {
                LivingEntity ally = findNearbyAllyList(ALLY_SCAN).stream().findFirst().orElse(null);
                yield ally != null ? ally : guard;
            }
        };
    }

    private Optional<GuardSpellManager.CategorizedSpell> findHealSpell() {
        return findOwned(ID_HEAL)
                .or(() -> findOwnedByPath("heal"))
                .or(() -> findOwnedByPath("mend"))
                .or(() -> findBestHeal(s -> hasHealImpact(s) && !isAreaSpell(s)));
    }

    private Optional<GuardSpellManager.CategorizedSpell> findAreaHealSpell() {
        return findOwned(ID_CIRCLE)
                .or(() -> findOwnedByPath("circle"))
                .or(() -> findOwnedByPath("song_of_healing"))
                .or(() -> findBestHeal(GuardSupportCasterGoal::isAreaSpell));
    }

    private Optional<GuardSpellManager.CategorizedSpell> findBestHeal(Predicate<Spell> filter) {
        return guard.getSpellManager().getBestSpell(GuardSpellManager.SpellCategory.HEALING,
                s -> filter.test(s.entry().value()) && !isSpellOnCooldown(s.spellId()));
    }

    private Optional<GuardSpellManager.CategorizedSpell> findOwned(Identifier id) {
        if (!guard.getSpellManager().knowsSpell(id)) return Optional.empty();
        return guard.getSpellManager().getAllActiveSpells().stream()
                .filter(s -> s.spellId().equals(id)).findFirst();
    }

    private Optional<GuardSpellManager.CategorizedSpell> findOwnedByPath(String pathPart) {
        return Optional.ofNullable(guard.getSpellManager().findActiveSpellByPath(pathPart));
    }

    private boolean hasBarrierSpell() {
        return findOwned(ID_BARRIER).or(() -> findOwnedByPath("barrier")).isPresent();
    }

    private static boolean hasHealImpact(Spell spell) {
        return spell.impacts != null && spell.impacts.stream()
                .anyMatch(i -> i.action != null && i.action.type == Spell.Impact.Action.Type.HEAL);
    }

    private static boolean isAreaSpell(Spell spell) {
        return spell.target != null && spell.target.type == Spell.Target.Type.AREA;
    }

    private void spawnBarrierMarker(Vec3d pos) {
        World w = guard.getWorld();
        if (w.isClient() || !(w instanceof net.minecraft.server.world.ServerWorld sw)) return;
        MarkerEntity marker = net.minecraft.entity.EntityType.MARKER.create(sw);
        if (marker == null) return;
        marker.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setInvulnerable(true);
        marker.addCommandTag("guard_priest_zone");
        marker.addCommandTag("zone:barrier");
        marker.addCommandTag("r:" + BARRIER_RADIUS);
        sw.spawnEntity(marker);
        guard.delayedTasks.add(new com.mojang.datafixers.util.Pair<>(TTL_BARRIER, () -> {
            if (marker.isAlive()) marker.discard();
        }));
    }
}
