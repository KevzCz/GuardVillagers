package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.ai.HolyZoneHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

import java.util.*;

public class PriestRangedHealerGoal extends Goal {
    private static final float HEAL_CLUSTER_RADIUS = 6.0F;
    private static final float HEAL_CAP_IN_COMBAT = 0.80F;
    private static final float HEAL_CAP_OOC = 1.00F;
    private static final float SEEK_RADIUS = 16.0F;
    private static final float CLUSTER_SEARCH_RADIUS = 12.0F;
    private static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);

    private static final Identifier ID_CIRCLE  = Identifier.of("paladins", "circle_of_healing");
    private static final Identifier ID_HEAL    = Identifier.of("paladins", "heal");
    private static final Identifier ID_HSHOCK  = Identifier.of("paladins", "holy_shock");
    private static final Identifier ID_BARRIER = Identifier.of("paladins", "barrier");

    private static final int   COOLDOWN_BARRIER        = 20 * 20;
    private static final int   TTL_BARRIER_MARKER      = 20 * 10;
    private static final float DEFAULT_BARRIER_RADIUS  = 5.0F;
    private static final float HSHOCK_MAX_RANGE     = 6.0F;
    private static final float HSHOCK_MAX_RANGE_SQR = HSHOCK_MAX_RANGE * HSHOCK_MAX_RANGE;

    private final GuardEntity guard;

    private int seeTime = 0;
    private int updatePathDelay = 0;
    private int pathDelay = 0;

    private Runnable pendingCast = null;
    private int windupTicks = 0;

    private final Map<Identifier, Integer> spellCooldowns = new HashMap<>();
    private enum PendingSpell { NONE, HEAL_SINGLE, CIRCLE, HOLY_SHOCK, BARRIER }
    private PendingSpell pendingType = PendingSpell.NONE;

    public PriestRangedHealerGoal(GuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LivingEntity tgt = guard.getTarget();
        boolean hasEnemy = tgt != null && tgt.isAlive();
        boolean selfHurt = guard.getHealth() < guard.getMaxHealth();
        boolean allyHurt = hasHurtAllyNearby(SEEK_RADIUS);
        return guard.isHoldingHolyFocus() && (hasEnemy || selfHurt || allyHurt);
    }

    @Override
    public boolean shouldContinue() {
        LivingEntity tgt = guard.getTarget();
        boolean hasEnemy = tgt != null && tgt.isAlive();
        boolean selfHurt = guard.getHealth() < guard.getMaxHealth();
        boolean allyHurt = hasHurtAllyNearby(SEEK_RADIUS);
        return guard.isHoldingHolyFocus() && (pendingCast != null || hasEnemy || selfHurt || allyHurt);
    }

    @Override
    public void stop() {
        guard.stopUsingItem();
        guard.setCastingSpell(false);
        guard.getNavigation().stop();
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

        spellCooldowns.replaceAll((id, t) -> Math.max(0, t - 1));

        if (pendingCast != null) {
            if (!guard.isUsingItem()) {
                guard.setCurrentHand(Hand.MAIN_HAND);
                guard.setCastingSpell(true);
            }
            if (--windupTicks <= 0) {
                guard.stopUsingItem();
                guard.setCastingSpell(false);
                Runnable cast = pendingCast;
                pendingCast = null;
                PendingSpell executed = pendingType;
                pendingType = PendingSpell.NONE;
                cast.run();
            }
        }

        if (pendingCast != null && pendingType == PendingSpell.BARRIER) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            guard.setVelocity(guard.getVelocity().multiply(0.2D));
            if (enemy != null) {
                guard.lookAtEntity(enemy, 30.0F, 30.0F);
                guard.getLookControl().lookAt(enemy, 30.0F, 30.0F);
            }
            return;
        }

        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone != null) {
            float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);
            if (!HolyZoneHelper.inside(guard, zone, zr)) {
                if (--pathDelay <= 0) {
                    HolyZoneHelper.steerTowardsIfOutside(guard, zone, zr, 1.1D);
                    pathDelay = PATH_DELAY.get(guard.getRandom());
                }
                return;
            } else {
                HolyZoneHelper.softLeashInside(guard, zone, zr);
            }
        }

        if (enemy == null || !enemy.isAlive()) {
            guard.getNavigation().stop();
            if (tryHealSingle(HEAL_CAP_OOC, SEEK_RADIUS)) return;
            if (tryCircleForCluster(CLUSTER_SEARCH_RADIUS)) return;
            return;
        }

        boolean canSee = guard.getVisibilityCache().canSee(enemy);
        boolean inAimPhase = guard.isUsingItem() || pendingCast != null;
        boolean canRun = (pendingCast == null);

        var mv = CombatMovementHelper.applyRangedCombatMovement(
                guard,
                enemy,
                canSee,
                this.seeTime,
                this.updatePathDelay,
                0,
                inAimPhase,
                canRun,
                1.15D,
                HSHOCK_MAX_RANGE
        );
        this.seeTime = mv.seeTime();
        this.updatePathDelay = mv.updatePathDelay();

        if (enemy instanceof PlayerEntity) {
            if (tryHealSingleGuardOnly(HEAL_CAP_IN_COMBAT, SEEK_RADIUS)) return;
            if (tryCircleForGuardCluster(CLUSTER_SEARCH_RADIUS)) return;
            if (isBarrierPriest() && tryBarrier()) return;
            return;
        }

        if (tryHealSingle(HEAL_CAP_IN_COMBAT, SEEK_RADIUS)) return;
        if (tryCircleForCluster(CLUSTER_SEARCH_RADIUS)) return;
        if (isBarrierPriest() && tryBarrier()) return;
        tryHolyShock(enemy);
    }


    private boolean isBarrierPriest() {
        String skill = guard.getHolySkill();
        return "paladins:barrier".equals(skill);
    }

    /* ---------- healing (general) ---------- */

    private boolean tryHealSingle(float capFrac, float radius) {
        if (pendingCast != null) return false;
        if (onCooldown(ID_HEAL)) return false;
        RegistryEntry<Spell> entry = spellEntry(ID_HEAL);
        if (entry == null) return false;

        LivingEntity target = findBestHealableAlly(radius, capFrac);
        if (target == null) return false;

        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone != null) {
            float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);
            if (!HolyZoneHelper.inside(guard, zone, zr)) return false;
        }

        if (guard.distanceTo(target) > 6.0F) {
            if (--pathDelay <= 0) {
                guard.getNavigation().startMovingTo(target, 1.1D);
                pathDelay = PATH_DELAY.get(guard.getRandom());
            }
        } else {
            beginWindup(PendingSpell.HEAL_SINGLE, () -> {
                castAtTarget(entry, target);
                spellCooldowns.put(ID_HEAL, getSpellCooldownTicks(entry.value(), 40));
            });
            return true;
        }
        return false;
    }

    private boolean tryCircleForCluster(float searchRadius) {
        if (pendingCast != null) return false;
        if (onCooldown(ID_CIRCLE)) return false;
        RegistryEntry<Spell> entry = spellEntry(ID_CIRCLE);
        if (entry == null) return false;

        Cluster cluster = findHurtAllyCluster(searchRadius);
        if (!cluster.valid) return false;

        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone != null) {
            float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);
            if (!HolyZoneHelper.inside(guard, zone, zr)) return false;
        }

        if (guard.getPos().squaredDistanceTo(cluster.center) > (3.0D * 3.0D)) {
            if (--pathDelay <= 0) {
                guard.getNavigation().startMovingTo(cluster.center.x, cluster.center.y, cluster.center.z, 1.1D);
                pathDelay = PATH_DELAY.get(guard.getRandom());
            }
            return false;
        }

        beginWindup(PendingSpell.CIRCLE, () -> {
            castSelfArea(entry);
            spellCooldowns.put(ID_CIRCLE, getSpellCooldownTicks(entry.value(), 200));
        });
        return true;
    }

    /* ---------- healing (GUARD-ONLY when enemy is a player) ---------- */

    private boolean tryHealSingleGuardOnly(float capFrac, float radius) {
        if (pendingCast != null) return false;
        if (onCooldown(ID_HEAL)) return false;
        RegistryEntry<Spell> entry = spellEntry(ID_HEAL);
        if (entry == null) return false;

        LivingEntity target = findBestHealableGuardAlly(radius, capFrac);
        if (target == null) return false;

        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone != null) {
            float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);
            if (!HolyZoneHelper.inside(guard, zone, zr)) return false;
        }

        if (guard.distanceTo(target) > 6.0F) {
            if (--pathDelay <= 0) {
                guard.getNavigation().startMovingTo(target, 1.1D);
                pathDelay = PATH_DELAY.get(guard.getRandom());
            }
        } else {
            beginWindup(PendingSpell.HEAL_SINGLE, () -> {
                castAtTarget(entry, target);
                spellCooldowns.put(ID_HEAL, getSpellCooldownTicks(entry.value(), 40));
            });
            return true;
        }
        return false;
    }

    private boolean tryCircleForGuardCluster(float searchRadius) {
        if (pendingCast != null) return false;
        if (onCooldown(ID_CIRCLE)) return false;
        RegistryEntry<Spell> entry = spellEntry(ID_CIRCLE);
        if (entry == null) return false;

        Cluster cluster = findHurtGuardCluster(searchRadius);
        if (!cluster.valid) return false;

        MarkerEntity zone = HolyZoneHelper.findNearby(guard, 32.0D);
        if (zone != null) {
            float zr = HolyZoneHelper.radiusOf(zone, DEFAULT_BARRIER_RADIUS);
            if (!HolyZoneHelper.inside(guard, zone, zr)) return false;
        }

        if (guard.getPos().squaredDistanceTo(cluster.center) > (3.0D * 3.0D)) {
            if (--pathDelay <= 0) {
                guard.getNavigation().startMovingTo(cluster.center.x, cluster.center.y, cluster.center.z, 1.1D);
                pathDelay = PATH_DELAY.get(guard.getRandom());
            }
            return false;
        }

        beginWindup(PendingSpell.CIRCLE, () -> {
            castSelfArea(entry);
            spellCooldowns.put(ID_CIRCLE, getSpellCooldownTicks(entry.value(), 200));
        });
        return true;
    }

    /* ---------- barrier ---------- */

    private boolean tryBarrier() {
        if (pendingCast != null) return false;
        if (onCooldown(ID_BARRIER)) return false;
        RegistryEntry<Spell> entry = spellEntry(ID_BARRIER);
        if (entry == null) return false;

        beginWindup(PendingSpell.BARRIER, () -> {
            Vec3d pos = guard.getPos();
            castBarrierAtPosition(entry, pos);
            spellCooldowns.put(ID_BARRIER, COOLDOWN_BARRIER);
        });
        return true;
    }

    private void castBarrierAtPosition(RegistryEntry<Spell> entry, Vec3d pos) {
        castAtPosition(entry, pos);
        spawnZoneMarker("barrier", pos, DEFAULT_BARRIER_RADIUS, TTL_BARRIER_MARKER);
        HolyZoneHelper.debugLog(guard, "Spawned BARRIER at " + pos);
    }

    /* ---------- holy shock ---------- */

    private boolean tryHolyShock(LivingEntity enemy) {
        if (pendingCast != null) return false;
        if (enemy == null || !enemy.isAlive()) return false;
        if (enemy instanceof PlayerEntity) return false;
        if (onCooldown(ID_HSHOCK)) return false;
        RegistryEntry<Spell> entry = spellEntry(ID_HSHOCK);
        if (entry == null) return false;

        if (guard.squaredDistanceTo(enemy) > HSHOCK_MAX_RANGE_SQR) {
            return false;
        }

        beginWindup(PendingSpell.HOLY_SHOCK, () -> {
            castAtTarget(entry, enemy);
            spellCooldowns.put(ID_HSHOCK, getSpellCooldownTicks(entry.value(), 40));
        });
        return true;
    }

    private void beginWindup(PendingSpell type, Runnable onCast) {
        pendingType = type;
        pendingCast = onCast;
        windupTicks = 20;
    }

    private void castSelfArea(RegistryEntry<Spell> spellEntry) {
        Spell spell = spellEntry.value();
        SpellHelper.ImpactContext ctx = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard))
                .position(guard.getPos());
        SpellHelper.performImpacts(guard.getWorld(), guard, guard, guard, spellEntry, spell.impacts, ctx);
        playReleaseSound(spell);
        guard.swingHand(Hand.MAIN_HAND, true);
    }

    private void castAtTarget(RegistryEntry<Spell> spellEntry, LivingEntity target) {
        if (target == null || !target.isAlive()) return;
        Spell spell = spellEntry.value();

        SpellHelper.ImpactContext ctx = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard))
                .position(guard.getEyePos())
                .target(SpellHelper.focusMode(spell));

        String type = String.valueOf(spell.deliver.type).toUpperCase();
        switch (type) {
            case "PROJECTILE" -> SpellHelper.shootProjectile(guard.getWorld(), guard, target, spellEntry, ctx, 0);
            case "DIRECT"     -> SpellHelper.performImpacts(guard.getWorld(), guard, target, guard, spellEntry, spell.impacts, ctx);
            case "AREA", "METEOR" -> castAtPosition(spellEntry, target.getPos());
            default -> SpellHelper.performImpacts(guard.getWorld(), guard, target, guard, spellEntry, spell.impacts, ctx);
        }
        playReleaseSound(spell);
        guard.swingHand(Hand.MAIN_HAND, true);
    }

    private void castAtPosition(RegistryEntry<Spell> spellEntry, Vec3d pos) {
        Spell spell = spellEntry.value();
        SpellHelper.ImpactContext ctx = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard))
                .position(pos);

        String type = String.valueOf(spell.deliver.type).toUpperCase();
        if ("METEOR".equals(type)) {
            LivingEntity tgt = guard.getTarget();
            SpellHelper.fallProjectile(guard.getWorld(), guard, tgt, pos, spellEntry, ctx);
        } else {
            SpellHelper.performImpacts(guard.getWorld(), guard, guard, guard, spellEntry, spell.impacts, ctx);
        }
        playReleaseSound(spell);
        guard.swingHand(Hand.MAIN_HAND, true);
    }

    private void playReleaseSound(Spell spell) {
        if (spell.release != null && spell.release.sound != null) {
            Identifier sid = Identifier.tryParse(spell.release.sound.id());
            if (sid != null) {
                SoundEvent se = Registries.SOUND_EVENT.get(sid);
                if (se != null) {
                    guard.getWorld().playSound(null, guard.getBlockPos(), se, SoundCategory.PLAYERS, 1.0F, 1.0F);
                }
            }
        }
    }

    /* -------------------------- utils -------------------------- */

    private LivingEntity findBestHealableAlly(float radius, float capFrac) {
        Box box = guard.getBoundingBox().expand(radius);

        List<LivingEntity> allies = guard.getWorld().getEntitiesByClass(
                LivingEntity.class,
                box,
                e -> isAlly(guard, e) && (e.getHealth() / e.getMaxHealth()) < capFrac
        );

        if ((guard.getHealth() / guard.getMaxHealth()) < capFrac) {
            allies.add(guard);
        }
        if (allies.isEmpty()) return null;

        return allies.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);
    }

    private LivingEntity findBestHealableGuardAlly(float radius, float capFrac) {
        Box box = guard.getBoundingBox().expand(radius);

        List<GuardEntity> guards = guard.getWorld().getEntitiesByClass(
                GuardEntity.class,
                box,
                g -> g.isAlive() && g != guard && (g.getHealth() / g.getMaxHealth()) < capFrac
        );

        if ((guard.getHealth() / guard.getMaxHealth()) < capFrac) {
            guards.add(guard);
        }
        if (guards.isEmpty()) return null;

        return guards.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);
    }

    private Cluster findHurtAllyCluster(float searchRadius) {
        Box box = guard.getBoundingBox().expand(searchRadius);
        List<LivingEntity> hurtAllies = guard.getWorld().getEntitiesByClass(
                LivingEntity.class, box,
                e -> isAlly(guard, e) && e.getHealth() < e.getMaxHealth()
        );

        if (hurtAllies.isEmpty()) return Cluster.invalid();

        LivingEntity seed = hurtAllies.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);
        if (seed == null) return Cluster.invalid();

        Vec3d sum = Vec3d.ZERO;
        int count = 0;
        for (LivingEntity a : hurtAllies) {
            if (a.squaredDistanceTo(seed) <= (HEAL_CLUSTER_RADIUS * HEAL_CLUSTER_RADIUS)) {
                sum = sum.add(a.getPos());
                count++;
            }
        }
        if (count == 0) return Cluster.invalid();

        return new Cluster(true, sum.multiply(1.0 / count), count);
    }

    private Cluster findHurtGuardCluster(float searchRadius) {
        Box box = guard.getBoundingBox().expand(searchRadius);
        List<GuardEntity> hurtGuards = guard.getWorld().getEntitiesByClass(
                GuardEntity.class, box,
                g -> g.isAlive() && g.getHealth() < g.getMaxHealth()
        );

        if (guard.getHealth() < guard.getMaxHealth()) {
            if (!hurtGuards.contains(guard)) hurtGuards.add(guard);
        }

        if (hurtGuards.isEmpty()) return Cluster.invalid();

        GuardEntity seed = hurtGuards.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);
        if (seed == null) return Cluster.invalid();

        Vec3d sum = Vec3d.ZERO;
        int count = 0;
        for (GuardEntity a : hurtGuards) {
            if (a.squaredDistanceTo(seed) <= (HEAL_CLUSTER_RADIUS * HEAL_CLUSTER_RADIUS)) {
                sum = sum.add(a.getPos());
                count++;
            }
        }
        if (count == 0) return Cluster.invalid();

        return new Cluster(true, sum.multiply(1.0 / count), count);
    }

    private boolean hasHurtAllyNearby(float radius) {
        Box box = guard.getBoundingBox().expand(radius);
        if (guard.getHealth() < guard.getMaxHealth()) return true;
        return !guard.getWorld().getEntitiesByClass(
                LivingEntity.class, box,
                e -> isAlly(guard, e) && e.getHealth() < e.getMaxHealth()
        ).isEmpty();
    }

    private static boolean isAlly(GuardEntity self, LivingEntity e) {
        if (e == null || !e.isAlive()) return false;
        if (e == self) return false;
        return (e instanceof net.minecraft.entity.passive.VillagerEntity)
                || (e instanceof dev.sterner.guardvillagers.common.entity.GuardEntity)
                || (e instanceof net.minecraft.entity.passive.IronGolemEntity);
    }

    private boolean onCooldown(Identifier id) {
        return spellCooldowns.getOrDefault(id, 0) > 0;
    }

    private int getSpellCooldownTicks(Spell spell, int fallback) {
        if (spell.cost != null && spell.cost.cooldown != null) {
            return (int) (spell.cost.cooldown.duration * 20);
        }
        return fallback;
    }

    private RegistryEntry<Spell> spellEntry(Identifier id) {
        return SpellRegistry.from(guard.getWorld()).getEntry(id).orElse(null);
    }

    private void spawnZoneMarker(String type, Vec3d pos, float radius, int durationTicks) {
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
        marker.addCommandTag("r:" + radius);

        sw.spawnEntity(marker);

        guard.delayedTasks.add(new com.mojang.datafixers.util.Pair<>(durationTicks, () -> {
            if (marker.isAlive()) marker.discard();
        }));
    }

    private static final class Cluster {
        final boolean valid; final Vec3d center; final int size;
        Cluster(boolean v, Vec3d c, int s) { valid = v; center = c; size = s; }
        static Cluster invalid() { return new Cluster(false, Vec3d.ZERO, 0); }
    }
}
