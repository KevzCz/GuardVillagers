package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.entity.SpellProjectile;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuardCastSpellGoal extends Goal {
    private static final UniformIntProvider PATHFINDING_DELAY_RANGE = TimeHelper.betweenSeconds(1, 2);
    private static final float ATTACK_RADIUS = 16.0F;
    private static final float ATTACK_RADIUS_SQR = ATTACK_RADIUS * ATTACK_RADIUS;
    private boolean isChanneled;
    private int channelTicksLeft;
    private final Map<Identifier, Integer> spellCooldowns = new HashMap<>();
    private int castingDelayTicks;
    private Identifier currentSpellId;
    private RegistryEntry<Spell> cachedSpellEntry;

    private boolean spellFired;
    private final GuardEntity guard;
    private int seeTime;
    private int updatePathDelay;
    private int windUpTicks;
    private int cooldownTicks;
    private SpellState spellState = SpellState.UNCHARGED;

    private double wantedX;
    private double wantedY;
    private double wantedZ;

    private enum SpellState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        CASTING,
        COOLDOWN,
        FIND_NEW_POSITION
    }

    public GuardCastSpellGoal(GuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }
    private boolean isSpellOnCooldown(Identifier spellId) {
        return spellCooldowns.getOrDefault(spellId, 0) > 0;
    }

    @Override
    public boolean canStart() {
        return isValidTarget() && isHoldingWand();
    }

    @Override
    public boolean shouldContinue() {
        return isValidTarget() && (canStart() || !guard.getNavigation().isIdle()) && isHoldingWand();
    }

    @Override
    public void start() {
        this.guard.setAttacking(true);
    }

    @Override
    public void stop() {
        this.guard.setAttacking(false);
        this.guard.setCastingSpell(false);
        this.guard.stopUsingItem();
        this.guard.getNavigation().stop();
        this.spellState = SpellState.UNCHARGED;
        this.seeTime = 0;
        this.cachedSpellEntry = null;

    }
    private float getCooldownMultiplier(Identifier spellId) {
        String key = guard.getMainHandStack().getItem().getTranslationKey();

         if ((key.contains("staff_ruby_fire") && spellId.getPath().equals("twin_fireball")) ||
                (key.contains("staff_smaragdant_frost") && spellId.getPath().equals("twin_frostshard")) ||
                (key.contains("staff_crystal_arcane") && spellId.getPath().equals("twin_arcanebolt"))) {
            return 0.33f;
        }

        return 1.0f;
    }

    @Override
    public void tick() {
        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) return;
        spellCooldowns.replaceAll((id, time) -> Math.max(time - 1, 0));

        boolean canSee = guard.getVisibilityCache().canSee(target);
        boolean hasSeenRecently = this.seeTime > 0;

        if (canSee != hasSeenRecently) this.seeTime = 0;
        if (canSee) ++this.seeTime;
        else --this.seeTime;

        double distanceSq = guard.squaredDistanceTo(target);
        double distance = Math.sqrt(distanceSq);
        float forward = 0.0F;
        float sideways = 0.0F;


        if (spellState == SpellState.CHARGING || spellState == SpellState.CHARGED || spellState == SpellState.COOLDOWN) {
            if (guard.getRandom().nextInt(10) == 0) {
                sideways = guard.getRandom().nextBoolean() ? 0.5F : -0.5F;
            }
            forward = guard.isUsingItem() ? -0.5F : -0.1F;
            guard.getMoveControl().strafeTo(sideways, forward);
        }

        if (distance <= 4.0D) {
            guard.getMoveControl().strafeTo(guard.isUsingItem() ? -0.5F : -3.0F, 0.0F);
        }

        if (guard.getRandom().nextInt(50) == 0) {
            if (guard.isInPose(EntityPose.STANDING))
                guard.setPose(EntityPose.CROUCHING);
            else
                guard.setPose(EntityPose.STANDING);
        }


        boolean needsToMove = (distanceSq > ATTACK_RADIUS_SQR || this.seeTime < 5) && this.cooldownTicks == 0;
        if (needsToMove) {
            --this.updatePathDelay;
            if (this.updatePathDelay <= 0) {
                guard.getNavigation().startMovingTo(target, this.canRun() ? 1.0D : 0.5D);
                this.updatePathDelay = PATHFINDING_DELAY_RANGE.get(guard.getRandom());
            }
        } else {
            this.updatePathDelay = 0;
            guard.getNavigation().stop();
        }

        guard.lookAtEntity(target, 30.0F, 30.0F);
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);


        if (this.friendlyInLineOfSight() && GuardVillagersConfig.friendlyFire) {
            this.spellState = SpellState.FIND_NEW_POSITION;
        }

        switch (this.spellState) {
            case FIND_NEW_POSITION -> {
                guard.stopUsingItem();
                guard.setCastingSpell(false);
                if (this.findPosition()) {
                    guard.getNavigation().startMovingTo(wantedX, wantedY, wantedZ,
                            guard.isSneaking() ? 0.5D : 1.2D);
                }
                this.spellState = SpellState.UNCHARGED;
            }

            case UNCHARGED -> {
                if (hasSeenRecently) {
                    Identifier spellId = getPrimarySpellId();
                    if (spellId == null || isSpellOnCooldown(spellId)) return;

                    cachedSpellEntry = SpellRegistry.from(guard.getWorld()).getEntry(spellId).orElse(null);
                    if (cachedSpellEntry == null) return;

                    Spell spell = cachedSpellEntry.value();
                    windUpTicks = getWindUpTicks(spell);
                    currentSpellId = spellId;


                    guard.setCurrentHand(Hand.MAIN_HAND);
                    guard.setCastingSpell(true);
                    this.spellState = SpellState.CHARGING;
                }
            }


            case CHARGING -> {
                if (!guard.isUsingItem()) {
                    guard.setCurrentHand(Hand.MAIN_HAND);
                }
                --windUpTicks;
                if (windUpTicks <= 0) {
                    this.spellState = SpellState.CHARGED;
                }
            }

            case CHARGED -> {
                Identifier spellId = getPrimarySpellId();
                if (spellId == null || isSpellOnCooldown(spellId)) return;

                if (cachedSpellEntry == null) return;

                Spell spell = cachedSpellEntry.value();

                currentSpellId = spellId;
                isChanneled = isSpellChanneled(spell);
                channelTicksLeft = getChannelDuration(spell);
                castingDelayTicks = 0;
                spellFired = false;
                spellState = SpellState.CASTING;

            }





            case CASTING -> {
                Identifier spellId = currentSpellId;
                if (currentSpellId == null || cachedSpellEntry == null) return;

                Spell spell = cachedSpellEntry.value();


                if (isChanneled) {
                    if (channelTicksLeft > 0) {
                        if (channelTicksLeft == getChannelDuration(spell)) {

                            castSpellByWandType(spell, cachedSpellEntry, target);
                            castingDelayTicks = getChannelFireInterval(spell);
                            channelTicksLeft--;
                        } else {
                            if (--castingDelayTicks <= 0) {
                                castSpellByWandType(spell, cachedSpellEntry, target);
                                castingDelayTicks = getChannelFireInterval(spell);
                            }
                            channelTicksLeft--;
                        }
                    } else {
                        guard.stopUsingItem();
                        int baseCooldown = getCooldownTicks(spell);
                        int modifiedCooldown = Math.max(1, (int)(baseCooldown * getCooldownMultiplier(spellId)));
                        spellCooldowns.put(spellId, modifiedCooldown);


                        spellState = SpellState.UNCHARGED;
                        guard.setCastingSpell(false);
                    }
                } else {
                    if (!spellFired) {
                        castSpellByWandType(spell, cachedSpellEntry, target);
                        spellFired = true;
                    } else {
                        guard.stopUsingItem();
                        int baseCooldown = getCooldownTicks(spell);
                        int modifiedCooldown = Math.max(1, (int)(baseCooldown * getCooldownMultiplier(spellId)));
                        spellCooldowns.put(spellId, modifiedCooldown);

                        spellState = SpellState.UNCHARGED;
                        guard.setCastingSpell(false);
                    }
                }
            }
        }
    }
    private int getWindUpTicks(Spell spell) {
        if (spell.active != null && spell.active.cast != null) {
            return (int)(spell.active.cast.duration * 20);
        }
        return 20;
    }

    private boolean isSpellChanneled(Spell spell) {
        return spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0;
    }

    private void castSpellByWandType(Spell spell, RegistryEntry<Spell> spellEntry, LivingEntity target) {
        String type = String.valueOf(spell.deliver.type).toUpperCase();

        if ("PROJECTILE".equals(type)
                && spell.deliver.projectile != null
                && spell.deliver.projectile.projectile != null) {

            SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                    .power(SpellPower.getSpellPower(spell.school, guard))
                    .channeled(isChanneled ? 1.0f : 0.0f)
                    .position(guard.getEyePos())
                    .target(SpellHelper.focusMode(spell))
                    .distance(1.0f);

            SpellHelper.shootProjectile(
                    guard.getWorld(),
                    guard,
                    target,
                    spellEntry,
                    context,
                    isChanneled
                            ? (getChannelDuration(spell) - channelTicksLeft) / spell.active.cast.channel_ticks
                            : 0
            );
            guard.swingHand(Hand.MAIN_HAND, true);
        } else if ("METEOR".equals(type) || "AREA".equals(type)) {
            castAdvancedSpell(spell, spellEntry, target);
            guard.swingHand(Hand.MAIN_HAND, true);
        } else if ("DIRECT".equals(type)) {
            // NEW: For spells like aqua_water_whip
            SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                    .power(SpellPower.getSpellPower(spell.school, guard))
                    .channeled(isChanneled ? 1.0f : 0.0f)
                    .position(guard.getEyePos())
                    .target(SpellHelper.focusMode(spell))
                    .distance(1.0f);

            SpellHelper.performImpacts(
                    guard.getWorld(),
                    guard,
                    target,
                    guard,
                    spellEntry,
                    spell.impacts,
                    context
            );
            guard.swingHand(Hand.MAIN_HAND, true);
            if (spell.release != null && spell.release.sound != null) {
                Identifier soundId = Identifier.tryParse(spell.release.sound.id());
                if (soundId != null) {
                    SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundId);
                    guard.getWorld().playSound(null, guard.getBlockPos(), soundEvent, SoundCategory.HOSTILE, 1.0f, 1.0f);
                }
            }

        } else {
            // fallback to projectile casting for undefined types
            castBasicProjectile(target);
        }
    }


    private int getChannelDuration(Spell spell) {
        if (spell.active != null && spell.active.cast != null) {
            return (int)(spell.active.cast.duration * 20);
        }
        return 0;
    }

    private boolean isValidTarget() {
        LivingEntity target = this.guard.getTarget();
        return target != null && target.isAlive();
    }

    private boolean isHoldingWand() {
        String key = guard.getMainHandStack().getItem().getTranslationKey();
        return key.contains("wand_") || key.contains("staff_") ||key.contains("blade_");
    }
    private Identifier getSpellIdForWand() {
        String key = guard.getMainHandStack().getItem().getTranslationKey();
        // Basic spells
        if (key.contains("wand_fire") || key.contains("staff_fire")) return Identifier.of("wizards", "twin_fireball");
        if (key.contains("wand_frost") || key.contains("staff_frost")) return Identifier.of("wizards", "twin_frostshard");
        if (key.contains("wand_arcane") || key.contains("staff_arcane")) return Identifier.of("wizards", "twin_arcanebolt");

        if (key.contains("wand_aqua") || key.contains("staff_aqua")) return Identifier.of("elemental_wizards_rpg", "twin_whip");
        if (key.contains("wand_terra") || key.contains("staff_terra")) return Identifier.of("elemental_wizards_rpg", "twin_spear");
        if (key.contains("wand_wind") || key.contains("staff_wind")) return Identifier.of("elemental_wizards_rpg", "twin_cutter");

        //Advanced spells
        if (key.contains("wand_netherite_fire") || key.contains("staff_netherite_fire") || key.contains("staff_ruby_fire")) return Identifier.of("wizards", "fire_meteor");
        if (key.contains("wand_netherite_frost") || key.contains("staff_netherite_frost") || key.contains("staff_smaragdant_frost")) return Identifier.of("wizards", "frost_blizzard");
        if (key.contains("wand_netherite_arcane") || key.contains("staff_netherite_arcane") || key.contains("staff_crystal_arcane")) return Identifier.of("wizards", "arcane_missile");

        if (key.contains("wand_netherite_aqua") || key.contains("staff_netherite_aqua") || key.contains("staff_crystal_aqua")) return Identifier.of("elemental_wizards_rpg", "aqua_explosive_bubbles_channeling");
        if (key.contains("wand_netherite_terra") || key.contains("staff_netherite_terra") || key.contains("staff_ruby_terra")) return Identifier.of("elemental_wizards_rpg", "terra_shattering_stone_channeling");
        if (key.contains("wand_netherite_wind") || key.contains("staff_netherite_wind") || key.contains("staff_aeternium_wind")) return Identifier.of("elemental_wizards_rpg", "wind_aeroburst_channeling");

        return null;
    }

    private Identifier getPrimarySpellId() {
        return getSpellIdForWand();
    }


    private int getChannelFireInterval(Spell spell) {
        if (spell.active != null && spell.active.cast != null) {
            if (spell.active.cast.channel_ticks > 0) {
                return spell.active.cast.channel_ticks;
            }
        }
        return 6;
    }


    private void castBasicProjectile(LivingEntity target) {
        Identifier spellId = currentSpellId;
        if (spellId == null) return;

        World world = guard.getWorld();
        RegistryEntry<Spell> spellEntry = SpellRegistry.from(world).getEntry(spellId).orElse(null);
        if (spellEntry == null) return;

        Spell spell = spellEntry.value();
        var deliver = spell.deliver;
        if (deliver == null || deliver.projectile == null || deliver.projectile.projectile == null) return;

        Spell.ProjectileData.Perks perks = deliver.projectile.projectile.perks != null
                ? deliver.projectile.projectile.perks.copy()
                : new Spell.ProjectileData.Perks();

        SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard));

        Vec3d spawnPos = guard.getEyePos();
        Vec3d direction = target.getEyePos().subtract(spawnPos).normalize().multiply(1.25);

        SpellProjectile projectile = new SpellProjectile(world, guard,
                spawnPos.x, spawnPos.y, spawnPos.z,
                SpellProjectile.Behaviour.FLY, spellEntry, context, perks);

        projectile.setVelocity(direction);
        projectile.range = 64.0F;
        world.spawnEntity(projectile);

        var sound = deliver.projectile.launch_properties.sound;
        if (sound != null) {
            Identifier soundId = Identifier.tryParse(sound.id());
            if (soundId != null) {
                SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundId);
                world.playSound(null, guard.getBlockPos(), soundEvent, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
        }
    }
    private void castAdvancedSpell(Spell spell, RegistryEntry<Spell> spellEntry, LivingEntity target) {
        SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard));

        var deliver = spell.deliver;
        if (deliver == null) return;

        if ("METEOR".equalsIgnoreCase(String.valueOf(deliver.type))) {
            Vec3d targetPos = target.getPos();
            SpellHelper.fallProjectile(
                    guard.getWorld(),
                    guard,
                    target,
                    targetPos,
                    spellEntry,
                    context
            );
        } else if ("PROJECTILE".equalsIgnoreCase(String.valueOf(deliver.type))) {
            castBasicProjectile(target);
        }

        if (spell.release != null && spell.release.sound != null) {
            Identifier soundId = Identifier.tryParse(spell.release.sound.id());
            if (soundId != null) {
                SoundEvent soundEvent = Registries.SOUND_EVENT.get(soundId);
                guard.getWorld().playSound(null, guard.getBlockPos(), soundEvent, SoundCategory.HOSTILE, 1.0f, 1.0f);
            }
        }
    }
    private int getCooldownTicks(Spell spell) {
        if (spell.cost != null && spell.cost.cooldown != null) {
            return (int) (spell.cost.cooldown.duration * 20);
        }
        if (spell.active != null && spell.active.cast != null) {
            return (int) (spell.active.cast.duration * 20);
        }

        return 20;
    }

    private boolean friendlyInLineOfSight() {
        List<Entity> nearby = guard.getWorld().getOtherEntities(guard, guard.getBoundingBox().expand(5.0D));
        for (Entity entity : nearby) {
            if (entity == guard.getTarget()) continue;
            boolean isFriendly = entity.getType() == EntityType.VILLAGER
                    || entity.getType() == GuardVillagers.GUARD_VILLAGER
                    || entity.getType() == EntityType.IRON_GOLEM
                    || entity == guard.getOwner();

            if (isFriendly && guard.canSee(entity) && guard.distanceTo(entity) <= 4.0D) {
                Vec3d toFriend = entity.getPos().subtract(guard.getPos()).normalize();
                Vec3d facing = guard.getRotationVector();
                if (facing.dotProduct(toFriend) > 0.9D) return true;
            }
        }
        return false;
    }

    private boolean findPosition() {
        Vec3d pos = getPosition();
        if (pos != null) {
            this.wantedX = pos.x;
            this.wantedY = pos.y;
            this.wantedZ = pos.z;
            return true;
        }
        return false;
    }

    private Vec3d getPosition() {
        return this.isValidTarget()
                ? NoPenaltyTargeting.findFrom(this.guard, 16, 7, this.guard.getTarget().getPos())
                : NoPenaltyTargeting.find(this.guard, 16, 7);
    }

    private boolean canRun() {
        return this.spellState == SpellState.UNCHARGED;
    }
}
