package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BowItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.entity.SpellProjectile;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RangedBowAttackPassiveGoal<T extends GuardEntity & RangedAttackMob> extends Goal {
    private final T actor;
    private final double speed;
    private final float squaredRange;
    private final int attackInterval;

    private int cooldown = 0;
    private int targetSeeingTicker = 0;
    private int combatTicks = -1;
    private boolean movingToLeft = false;
    private boolean backward = false;

    private final Map<Identifier, Integer> spellCooldowns = new HashMap<>();
    private boolean spellFired;
    private int windUpTicks;
    private int channelTicksLeft;
    private int castingDelayTicks;
    private boolean isChanneled;

    private Identifier currentSpellId;
    private RegistryEntry<Spell> cachedSpellEntry;

    public RangedBowAttackPassiveGoal(T actor, double speed, int attackInterval, float range) {
        this.actor = actor;
        this.speed = speed;
        this.attackInterval = attackInterval;
        this.squaredRange = range * range;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return actor.getTarget() != null && isHoldingBow();
    }

    @Override
    public boolean shouldContinue() {
        return canStart() || !actor.getNavigation().isIdle();
    }

    @Override
    public void start() {
        actor.setAttacking(true);
    }

    @Override
    public void stop() {
        actor.setAttacking(false);
        actor.clearActiveItem();
        spellCooldowns.replaceAll((id, time) -> Math.max(time - 1, 0));
    }

    private boolean isHoldingBow() {
        return actor.getMainHandStack().getItem() instanceof BowItem;
    }
    private float getScaledSpellChance() {
        double rangedDamage = 0.0;
        Optional<RegistryEntry.Reference<net.minecraft.entity.attribute.EntityAttribute>> rangedAttrEntryOpt =
                Registries.ATTRIBUTE.getEntry(Identifier.of("ranged_weapon", "damage"));

        if (rangedAttrEntryOpt.isPresent()) {
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> rangedAttrEntry = rangedAttrEntryOpt.get();
            if (actor.getAttributes().hasAttribute(rangedAttrEntry)) {
                rangedDamage = actor.getAttributeValue(rangedAttrEntry);
            }
        }

        double minChance = 0.05;
        double maxChance = 0.15;
        double maxDamage = 10.0;

        double chance = minChance + (Math.min(rangedDamage, maxDamage) / maxDamage) * (maxChance - minChance);
        return (float) chance;
    }
    @Override
    public void tick() {
        LivingEntity target = actor.getTarget();
        if (target == null || !target.isAlive()) return;
        boolean canSee = actor.getVisibilityCache().canSee(target);
        var mv = CombatMovementHelper.applyBowOrbitMovement(
                actor,
                target,
                this.speed,
                this.squaredRange,
                this.targetSeeingTicker,
                this.combatTicks,
                this.movingToLeft,
                this.backward
        );
        this.targetSeeingTicker = mv.targetSeeingTicker();
        this.combatTicks = mv.combatTicks();
        this.movingToLeft = mv.movingToLeft();
        this.backward = mv.backward();

        spellCooldowns.replaceAll((id, time) -> Math.max(time - 1, 0));
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        Identifier spellId = getBowSpellId();

        if (actor.isUsingItem()) {
            int useTime = actor.getItemUseTime();

            if (canSee && useTime >= 30) {
                actor.clearActiveItem();

                float spellChance = getScaledSpellChance();
                if (spellId != null && !isSpellOnCooldown(spellId) && actor.getRandom().nextFloat() < spellChance) {

                    Optional<RegistryEntry.Reference<Spell>> spellOpt = SpellRegistry.from(actor.getWorld()).getEntry(spellId);
                    if (spellOpt.isPresent()) {
                        cachedSpellEntry = spellOpt.get();
                        Spell spell = cachedSpellEntry.value();
                        currentSpellId = spellId;

                        windUpTicks = getWindUpTicks(spell);
                        isChanneled = isSpellChanneled(spell);
                        channelTicksLeft = getChannelDuration(spell);
                        castingDelayTicks = 0;
                        spellFired = false;

                        castSpell(target, spell, cachedSpellEntry);

                        spellCooldowns.put(spellId, 10);
                        cooldown = 0;
                        return;
                    }
                }

                ((RangedAttackMob) actor).shootAt(target, BowItem.getPullProgress(useTime));
            }

        } else if (cooldown <= 0 && targetSeeingTicker >= -60) {
            actor.setCurrentHand(Hand.MAIN_HAND);
        }
    }


    private void castSpell(LivingEntity target, Spell spell, RegistryEntry<Spell> spellEntry) {
        SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, actor))
                .channeled(isChanneled ? 1.0f : 0.0f)
                .position(actor.getEyePos())
                .target(SpellHelper.focusMode(spell))
                .distance(1.0f);

        switch (String.valueOf(spell.deliver.type).toUpperCase()) {
            case "PROJECTILE" -> {
                int totalProjectiles = 1 + spell.deliver.projectile.launch_properties.extra_launch_count;

                for (int i = 0; i < totalProjectiles; i++) {
                    Spell.ProjectileData.Perks perks = spell.deliver.projectile.projectile.perks != null
                            ? spell.deliver.projectile.projectile.perks.copy()
                            : new Spell.ProjectileData.Perks();

                    Vec3d launchPos = SpellHelper.launchPoint(actor);
                    Vec3d direction = target.getEyePos().subtract(launchPos).normalize();

                    float yawOffset = 0;
                    if (spell.deliver.projectile.direction_offsets != null &&
                            i < spell.deliver.projectile.direction_offsets.length) {
                        yawOffset = spell.deliver.projectile.direction_offsets[i].yaw;

                    }

                    Vec3d rotatedDirection = rotateYaw(direction, yawOffset);

                    SpellProjectile projectile = new SpellProjectile(
                            actor.getWorld(),
                            actor,
                            launchPos.x,
                            launchPos.y,
                            launchPos.z,
                            SpellProjectile.Behaviour.FLY,
                            spellEntry,
                            context,
                            perks
                    );

                    projectile.setVelocity(rotatedDirection.x, rotatedDirection.y, rotatedDirection.z,
                            spell.deliver.projectile.launch_properties.velocity,
                            spell.deliver.projectile.projectile.divergence
                    );

                    projectile.range = spell.range;

                    actor.getWorld().spawnEntity(projectile);
                }

                playSpellSound(spell);
            }

            case "METEOR" -> {
                SpellHelper.fallProjectile(
                        actor.getWorld(),
                        actor,
                        target,
                        target.getPos(),
                        spellEntry,
                        context
                );
                playSpellSound(spell);
            }

            case "DIRECT" -> {
                SpellHelper.performImpacts(
                        actor.getWorld(),
                        actor,
                        target,
                        actor,
                        spellEntry,
                        spell.impacts,
                        context
                );
                playSpellSound(spell);
            }
        }
    }
    private Vec3d rotateYaw(Vec3d vec, float degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = vec.x * cos - vec.z * sin;
        double z = vec.x * sin + vec.z * cos;
        return new Vec3d(x, vec.y, z);
    }

    private Identifier getBowSpellId() {
        return actor.getBowSkill() != null && !actor.getBowSkill().equals("none")
                ? Identifier.tryParse(actor.getBowSkill())
                : null;
    }

    private boolean isSpellOnCooldown(Identifier spellId) {
        return spellCooldowns.getOrDefault(spellId, 0) > 0;
    }

    private boolean isSpellChanneled(Spell spell) {
        return spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0;
    }

    private int getWindUpTicks(Spell spell) {
        return spell.active != null && spell.active.cast != null
                ? (int) (spell.active.cast.duration * 20)
                : 20;
    }

    private int getChannelDuration(Spell spell) {
        return spell.active != null && spell.active.cast != null
                ? spell.active.cast.channel_ticks
                : 0;
    }

    private int getCooldownTicks(Spell spell) {
        return spell.cost != null && spell.cost.cooldown != null
                ? (int) (spell.cost.cooldown.duration * 20)
                : 20;
    }

    private void playSpellSound(Spell spell) {
        if (spell.release != null && spell.release.sound != null) {
            Identifier soundId = Identifier.tryParse(spell.release.sound.id());
            if (soundId != null) {
                SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
                actor.getWorld().playSound(null, actor.getBlockPos(), sound, net.minecraft.sound.SoundCategory.HOSTILE, 1.0F, 1.0F);
            }
        }
    }
}

