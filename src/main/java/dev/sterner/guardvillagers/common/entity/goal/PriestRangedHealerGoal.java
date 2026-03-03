package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.ai.HolyZoneHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseHealerGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellContext;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
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

import java.util.EnumSet;

public class PriestRangedHealerGoal extends BaseHealerGoal {
    private static final float HEAL_CAP_IN_COMBAT = 0.80F;
    private static final float HEAL_CAP_OOC = 1.00F;
    private static final float SEEK_RADIUS = 16.0F;
    private static final float CLUSTER_SEARCH_RADIUS = 12.0F;
    private static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);

    private static final Identifier ID_CIRCLE  = Identifier.of("paladins", "circle_of_healing");
    private static final Identifier ID_HEAL    = Identifier.of("paladins", "heal");
    private static final Identifier ID_HSHOCK  = Identifier.of("paladins", "holy_shock");
    private static final Identifier ID_BARRIER = Identifier.of("paladins", "barrier");

    private static final int   COOLDOWN_BARRIER       = 20 * 20;
    private static final int   TTL_BARRIER_MARKER     = 20 * 10;
    private static final float DEFAULT_BARRIER_RADIUS = 5.0F;
    private static final float HSHOCK_MAX_RANGE       = 6.0F;
    private static final float HSHOCK_MAX_RANGE_SQR   = HSHOCK_MAX_RANGE * HSHOCK_MAX_RANGE;

    private int seeTime = 0;
    private int updatePathDelay = 0;
    private int pathDelay = 0;

    private Runnable pendingCast = null;
    private int windupTicks = 0;

    private enum PendingSpell { NONE, HEAL_SINGLE, CIRCLE, HOLY_SHOCK, BARRIER }
    private PendingSpell pendingType = PendingSpell.NONE;

    public PriestRangedHealerGoal(GuardEntity guard) {
        super(guard);
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity target = guard.getTarget();
        boolean hasEnemy = target != null && target.isAlive();
        boolean selfHurt = guard.getHealth() < guard.getMaxHealth();
        boolean allyHurt = hasHurtAllyNearby(SEEK_RADIUS);

        return guard.getSpellManager().hasHealingSpells() && (hasEnemy || selfHurt || allyHurt);
    }

    @Override
    public boolean shouldContinue() {
        LivingEntity target = guard.getTarget();
        boolean hasEnemy = target != null && target.isAlive();
        boolean selfHurt = guard.getHealth() < guard.getMaxHealth();
        boolean allyHurt = hasHurtAllyNearby(SEEK_RADIUS);

        return guard.getSpellManager().hasHealingSpells()
                && (pendingCast != null || hasEnemy || selfHurt || allyHurt);
    }

    @Override
    public void stop() {
        super.stop();
        seeTime = 0;
        updatePathDelay = 0;
        pathDelay = 0;
        pendingCast = null;
        windupTicks = 0;
        pendingType = PendingSpell.NONE;
    }

    @Override
    public void tick() {
        LivingEntity enemy = guard.getTarget();
        tickCooldowns();

        if (handlePendingCast()) return;
        if (handleBarrierCasting(enemy)) return;
        if (handleZoneMovement()) return;

        if (enemy == null || !enemy.isAlive()) {
            handleOutOfCombat();
            return;
        }

        updateCombatMovement(enemy);
        handleInCombat(enemy);
    }

    private boolean handlePendingCast() {
        if (pendingCast == null) return false;

        if (!guard.isUsingItem()) {
            guard.setCurrentHand(Hand.MAIN_HAND);
            guard.setCastingSpell(true);
        }

        if (cachedSpellEntry != null && windupTicks % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (--windupTicks <= 0) {
            guard.stopUsingItem();
            guard.setCastingSpell(false);

            Runnable cast = pendingCast;
            pendingCast = null;
            pendingType = PendingSpell.NONE;

            cast.run();
        }

        return true;
    }

    private boolean handleBarrierCasting(LivingEntity enemy) {
        if (pendingCast == null || pendingType != PendingSpell.BARRIER) return false;

        guard.getNavigation().stop();
        guard.getMoveControl().strafeTo(0.0F, 0.0F);
        guard.setVelocity(guard.getVelocity().multiply(0.2D));

        if (enemy != null) {
            guard.lookAtEntity(enemy, 30.0F, 30.0F);
            guard.getLookControl().lookAt(enemy, 30.0F, 30.0F);
        }

        return true;
    }

    private boolean handleZoneMovement() {
        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone == null) return false;

        float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);

        if (!HolyZoneHelper.inside(guard, zone, zr)) {
            if (--pathDelay <= 0) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zone, zr, 1.1D);
                pathDelay = PATH_DELAY.get(guard.getRandom());
            }
            return true;
        } else {
            HolyZoneHelper.softLeashInside(guard, zone, zr);
        }

        return false;
    }

    private void handleOutOfCombat() {
        guard.getNavigation().stop();
        tryHealSingle(HEAL_CAP_OOC, SEEK_RADIUS);
        tryCircleForCluster(CLUSTER_SEARCH_RADIUS);
    }

    private void updateCombatMovement(LivingEntity enemy) {
        boolean canSee = guard.getVisibilityCache().canSee(enemy);
        boolean inAimPhase = guard.isUsingItem() || pendingCast != null;
        boolean canRun = pendingCast == null;

        var mv = CombatMovementHelper.applyRangedCombatMovement(
                guard, enemy, canSee, this.seeTime, this.updatePathDelay,
                0, inAimPhase, canRun, 1.15D, HSHOCK_MAX_RANGE
        );

        this.seeTime = mv.seeTime();
        this.updatePathDelay = mv.updatePathDelay();
    }

    private void handleInCombat(LivingEntity enemy) {
        if (enemy instanceof PlayerEntity) {
            handlePlayerEnemy();
        } else {
            handleMobEnemy(enemy);
        }
    }

    private void handlePlayerEnemy() {
        if (tryHealSingleGuardOnly(HEAL_CAP_IN_COMBAT, SEEK_RADIUS)) return;
        if (tryCircleForGuardCluster(CLUSTER_SEARCH_RADIUS)) return;
        if (isBarrierPriest()) {
            tryBarrier();
        }
    }

    private void handleMobEnemy(LivingEntity enemy) {
        if (tryHealSingle(HEAL_CAP_IN_COMBAT, SEEK_RADIUS)) return;
        if (tryCircleForCluster(CLUSTER_SEARCH_RADIUS)) return;
        if (isBarrierPriest() && tryBarrier()) return;
        tryHolyShock(enemy);
    }

    private boolean isBarrierPriest() {
        return guard.getSpellManager().hasSpells(GuardSpellManager.SpellCategory.SUPPORT)
                && guard.getSpellManager().getSpells(GuardSpellManager.SpellCategory.SUPPORT)
                .stream()
                .anyMatch(s -> s.spellId().getPath().contains("barrier"));
    }

    private boolean tryHealSingle(float capFrac, float radius) {
        if (pendingCast != null || isSpellOnCooldown(ID_HEAL)) return false;

        return getSpellEntry(ID_HEAL).map(entry -> {
            LivingEntity target = findBestHealableAlly(radius, capFrac);
            if (target == null || !isInsideZoneIfExists()) return false;

            if (guard.distanceTo(target) > 6.0F) {
                return moveToTarget(target);
            }

            scheduleHealCast(entry, target);
            return true;
        }).orElse(false);
    }

    private boolean tryHealSingleGuardOnly(float capFrac, float radius) {
        if (pendingCast != null || isSpellOnCooldown(ID_HEAL)) return false;

        return getSpellEntry(ID_HEAL).map(entry -> {
            LivingEntity target = findBestHealableGuardAlly(radius, capFrac);
            if (target == null || !isInsideZoneIfExists()) return false;

            if (guard.distanceTo(target) > 6.0F) {
                return moveToTarget(target);
            }

            scheduleHealCast(entry, target);
            return true;
        }).orElse(false);
    }

    private boolean tryCircleForCluster(float searchRadius) {
        if (pendingCast != null || isSpellOnCooldown(ID_CIRCLE)) return false;

        return getSpellEntry(ID_CIRCLE).map(entry -> {
            Cluster cluster = findHurtAllyCluster(searchRadius);
            if (!cluster.valid || !isInsideZoneIfExists()) return false;

            if (guard.getPos().squaredDistanceTo(cluster.center) > (3.0D * 3.0D)) {
                return moveToClusterCenter(cluster);
            }

            scheduleCircleCast(entry);
            return true;
        }).orElse(false);
    }

    private boolean tryCircleForGuardCluster(float searchRadius) {
        if (pendingCast != null || isSpellOnCooldown(ID_CIRCLE)) return false;

        return getSpellEntry(ID_CIRCLE).map(entry -> {
            Cluster cluster = findHurtGuardCluster(searchRadius);
            if (!cluster.valid || !isInsideZoneIfExists()) return false;

            if (guard.getPos().squaredDistanceTo(cluster.center) > (3.0D * 3.0D)) {
                return moveToClusterCenter(cluster);
            }

            scheduleCircleCast(entry);
            return true;
        }).orElse(false);
    }

    private boolean tryBarrier() {
        if (pendingCast != null || isSpellOnCooldown(ID_BARRIER)) return false;

        return getSpellEntry(ID_BARRIER).map(entry -> {
            cachedSpellEntry = entry;
            beginWindup(PendingSpell.BARRIER, () -> {
                Vec3d pos = guard.getPos();
                SpellContext context = createSpellContext(ID_BARRIER, entry, null)
                        .buildImpactContext()
                        .build();

                SpellDelivery.castAtPosition(context, pos);
                spawnZoneMarker("barrier", pos, DEFAULT_BARRIER_RADIUS, TTL_BARRIER_MARKER);
                HolyZoneHelper.debugLog(guard, "Spawned BARRIER at " + pos);

                spellCooldowns.put(ID_BARRIER, COOLDOWN_BARRIER);
                cachedSpellEntry = null;
            });
            return true;
        }).orElse(false);
    }

    private boolean tryHolyShock(LivingEntity enemy) {
        if (pendingCast != null || enemy == null || !enemy.isAlive()) return false;
        if (enemy instanceof PlayerEntity || isSpellOnCooldown(ID_HSHOCK)) return false;
        if (guard.squaredDistanceTo(enemy) > HSHOCK_MAX_RANGE_SQR) return false;

        return getSpellEntry(ID_HSHOCK).map(entry -> {
            cachedSpellEntry = entry;
            beginWindup(PendingSpell.HOLY_SHOCK, () -> {
                SpellContext context = createSpellContext(ID_HSHOCK, entry, enemy).build();
                SpellDelivery.castProjectile(context, 0);
                spellCooldowns.put(ID_HSHOCK, getCooldownTicks(entry.value()));
                cachedSpellEntry = null;
            });
            return true;
        }).orElse(false);
    }

    private boolean isInsideZoneIfExists() {
        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone == null) return true;

        float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);
        return HolyZoneHelper.inside(guard, zone, zr);
    }

    private boolean moveToTarget(LivingEntity target) {
        if (--pathDelay <= 0) {
            guard.getNavigation().startMovingTo(target, 1.1D);
            pathDelay = PATH_DELAY.get(guard.getRandom());
        }
        return false;
    }

    private boolean moveToClusterCenter(Cluster cluster) {
        if (--pathDelay <= 0) {
            guard.getNavigation().startMovingTo(
                    cluster.center.x, cluster.center.y, cluster.center.z, 1.1D
            );
            pathDelay = PATH_DELAY.get(guard.getRandom());
        }
        return false;
    }

    private void scheduleHealCast(RegistryEntry<Spell> entry, LivingEntity target) {
        cachedSpellEntry = entry;
        beginWindup(PendingSpell.HEAL_SINGLE, () -> {
            SpellContext context = createSpellContext(ID_HEAL, entry, target).build();
            SpellDelivery.castProjectile(context, 0);
            spellCooldowns.put(ID_HEAL, getCooldownTicks(entry.value()));
            cachedSpellEntry = null;
        });
    }

    private void scheduleCircleCast(RegistryEntry<Spell> entry) {
        cachedSpellEntry = entry;
        beginWindup(PendingSpell.CIRCLE, () -> {
            SpellContext context = createSpellContext(ID_CIRCLE, entry, null).build();
            SpellDelivery.castSelfCast(context);
            spellCooldowns.put(ID_CIRCLE, getCooldownTicks(entry.value()));
            cachedSpellEntry = null;
        });
    }

    private void beginWindup(PendingSpell type, Runnable onCast) {
        pendingType = type;
        pendingCast = onCast;
        windupTicks = 20;
    }

    private void spawnZoneMarker(String type, Vec3d pos, float radius, int durationTicks) {
        World w = guard.getWorld();
        if (w.isClient() || !(w instanceof net.minecraft.server.world.ServerWorld sw)) return;

        net.minecraft.entity.MarkerEntity marker = net.minecraft.entity.EntityType.MARKER.create(sw);
        if (marker == null) return;

        marker.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setNoGravity(true);
        marker.setInvulnerable(true);

        marker.addCommandTag("guard_priest_zone");
        marker.addCommandTag("zone:" + type);
        marker.addCommandTag("r:" + radius);

        sw.spawnEntity(marker);

        guard.delayedTasks.add(new com.mojang.datafixers.util.Pair<>(durationTicks, () -> {
            if (marker.isAlive()) marker.discard();
        }));
    }
}