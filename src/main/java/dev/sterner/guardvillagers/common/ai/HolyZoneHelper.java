package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

public final class HolyZoneHelper {
    public static final boolean DEBUG = false;

    private HolyZoneHelper() {}

    public static MarkerEntity findNearby(GuardEntity guard, double range) {
        List<MarkerEntity> markers = guard.getWorld().getEntitiesByClass(
                MarkerEntity.class,
                new Box(guard.getBlockPos()).expand(range),
                m -> m.isAlive() && m.getCommandTags().contains("guard_priest_zone")
        );
        if (markers.isEmpty()) return null;

        MarkerEntity bestBarrier = null, bestHeal = null;
        for (MarkerEntity m : markers) {
            var tags = m.getCommandTags();
            if (tags.contains("zone:barrier")) {
                if (bestBarrier == null || guard.squaredDistanceTo(m) < guard.squaredDistanceTo(bestBarrier))
                    bestBarrier = m;
            } else if (tags.contains("zone:healing")) {
                if (bestHeal == null || guard.squaredDistanceTo(m) < guard.squaredDistanceTo(bestHeal))
                    bestHeal = m;
            }
        }

        MarkerEntity found = bestBarrier != null ? bestBarrier : bestHeal;
        if (DEBUG && found != null && guard.age % 20 == 0) {
            ensureDebugVisuals(found, radiusOf(found, 5.0F));
            debugParticles(guard.getWorld(), found, radiusOf(found, 5.0F));
        }
        return found;
    }

    public static float radiusOf(MarkerEntity m, float def) {
        for (String t : m.getCommandTags()) {
            if (t.startsWith("r:")) {
                try { return Float.parseFloat(t.substring(2)); } catch (Exception ignored) {}
            }
        }
        return def;
    }

    public static boolean inside(GuardEntity g, MarkerEntity m, float r) {
        return g.squaredDistanceTo(m) <= (double)(r * r);
    }

    /** While outside, gently path toward the marker without blocking other goals. */
    public static void steerTowardsIfOutside(GuardEntity guard, MarkerEntity m, float r, double speed) {
        if (!inside(guard, m, r)) {
            guard.getNavigation().startMovingTo(m, speed);
            if (DEBUG) debugLog(guard, "Steering TOWARD holy zone (r=" + r + ") at " + fmt(m.getPos()));
        }
    }

    /** Inside the zone, damp strafing drift near the rim. */
    public static void softLeashInside(GuardEntity guard, MarkerEntity m, float r) {
        if (!inside(guard, m, r)) return;
        double d2 = guard.squaredDistanceTo(m);
        double rim2 = r * r;
        if (DEBUG) debugLog(guard, "Inside holy zone (d^2=" + round(d2) + " / r^2=" + round(rim2) + ")");
        if (d2 > rim2 * 0.55) {
            guard.getNavigation().startMovingTo(m, 1.15D);
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            if (DEBUG) debugLog(guard, "Near rim; biasing inward to " + fmt(m.getPos()));
        }
    }


    /** Hard stop while inside. */
    public static boolean stopInside(GuardEntity guard, MarkerEntity m, float r) {
        if (inside(guard, m, r)) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            return true;
        }
        return false;
    }

    /* ---------- DEBUG VISUALS ---------- */

    private static void ensureDebugVisuals(MarkerEntity m, float r) {
        if (!DEBUG) return;
        if (!m.hasCustomName()) {
            m.setCustomName(Text.literal("HolyZone r=" + r));
            m.setCustomNameVisible(true);
        }
        m.setGlowing(true);
    }

    private static void debugParticles(World w, MarkerEntity m, float r) {
        if (!DEBUG) return;
        if (!(w instanceof ServerWorld sw)) return;

        int points = 32;
        double y = m.getY() + 0.05;
        for (int i = 0; i < points; i++) {
            double ang = (Math.PI * 2.0) * (i / (double) points);
            double x = m.getX() + r * Math.cos(ang);
            double z = m.getZ() + r * Math.sin(ang);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    public static void debugLog(GuardEntity guard, String msg) {
        if (!DEBUG) return;
        String name = guard.getName() != null ? guard.getName().getString() : ("Guard#" + guard.getId());
        GuardVillagers.LOGGER.info("[HolyZone] " + name + " -> " + msg);
    }

    private static String fmt(Vec3d v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    private static String round(double d) {
        return String.format("%.2f", d);
    }
}
