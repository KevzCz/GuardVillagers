package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.mixin.accessor.CrossbowItemAccessor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.entity.SpellProjectile;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class RangedCrossbowAttackPassiveGoal<T extends PathAwareEntity & RangedAttackMob & CrossbowUser> extends Goal {
    public static final UniformIntProvider PATHFINDING_DELAY_RANGE = TimeHelper.betweenSeconds(1, 2);
    private final T mob;
    private int chargingTime = 0;
    private final double speedModifier;
    private final float attackRadiusSqr;
    protected double wantedX;
    protected double wantedY;
    protected double wantedZ;
    private CrossbowState crossbowState = CrossbowState.UNCHARGED;
    private int seeTime;
    private int attackDelay;
    private int updatePathDelay;

    // Spell system
    private final Map<Identifier, Integer> spellCooldowns = new HashMap<>();
    private Identifier currentSpellId;
    private RegistryEntry<Spell> cachedSpellEntry;

    public RangedCrossbowAttackPassiveGoal(T mob, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return this.isValidTarget() && this.isHoldingCrossbow();
    }

    private boolean isHoldingCrossbow() {
        return this.mob.isHolding(is -> is.getItem() instanceof CrossbowItem);
    }

    @Override
    public boolean shouldContinue() {
        return this.isValidTarget() && (this.canStart() || !this.mob.getNavigation().isIdle()) && this.isHoldingCrossbow();
    }

    private boolean isValidTarget() {
        return this.mob.getTarget() != null && this.mob.getTarget().isAlive();
    }

    @Override
    public void stop() {
        super.stop();
        this.mob.setAttacking(false);
        this.mob.setTarget(null);
        this.seeTime = 0;
        if (this.mob.isUsingItem()) {
            this.mob.stopUsingItem();
            this.mob.setCharging(false);
        }
        this.mob.setPose(EntityPose.STANDING);
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mob.setAttacking(true);
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();

        if (target != null) {
            double distance = this.mob.squaredDistanceTo(target);
            boolean canSee = this.mob.getVisibilityCache().canSee(target);
            boolean hasSeenRecently = this.seeTime > 0;

            this.seeTime += canSee ? 1 : -1;

            if (distance <= 4.0D) {
                this.mob.getMoveControl().strafeTo(this.mob.isUsingItem() ? -0.5F : -3.0F, 0.0F);
            }

            if (this.mob.getRandom().nextInt(50) == 0) {
                this.mob.setPose(this.mob.getPose() == EntityPose.STANDING ? EntityPose.CROUCHING : EntityPose.STANDING);
            }

            boolean shouldMove = (distance > this.attackRadiusSqr || this.seeTime < 5) && this.attackDelay == 0;
            if (shouldMove) {
                if (--this.updatePathDelay <= 0) {
                    this.mob.getNavigation().startMovingTo(target, this.canRun() ? this.speedModifier : this.speedModifier * 0.5D);
                    this.updatePathDelay = PATHFINDING_DELAY_RANGE.get(this.mob.getRandom());
                }
            } else {
                this.updatePathDelay = 0;
                this.mob.getNavigation().stop();
            }

            this.mob.lookAtEntity(target, 30.0F, 30.0F);
            this.mob.getLookControl().lookAt(target, 30.0F, 30.0F);
            spellCooldowns.replaceAll((id, t) -> Math.max(t - 1, 0));

            if (this.friendlyInLineOfSight() && GuardVillagersConfig.friendlyFire) {
                this.crossbowState = CrossbowState.FIND_NEW_POSITION;
            }

            switch (this.crossbowState) {
                case FIND_NEW_POSITION -> {
                    this.mob.stopUsingItem();
                    this.mob.setCharging(false);
                    if (this.findPosition())
                        this.mob.getNavigation().startMovingTo(this.wantedX, this.wantedY, this.wantedZ, this.mob.isSneaking() ? 0.5F : 1.2D);
                    this.crossbowState = CrossbowState.UNCHARGED;
                }

                case UNCHARGED -> {
                    if (hasSeenRecently) {
                        this.mob.setCurrentHand(GuardVillagers.getHandWith(this.mob, item -> item instanceof CrossbowItem));
                        this.mob.setCharging(true);
                        this.crossbowState = CrossbowState.CHARGING;
                    }
                }

                case CHARGING -> {
                    chargingTime++;
                    int requiredPullTime = 25;
                    int useTime = this.mob.getItemUseTime();
                    ItemStack itemStack = this.mob.getActiveItem();

                    if (useTime >= requiredPullTime || CrossbowItem.isCharged(itemStack) || chargingTime > 60) {
                        this.mob.stopUsingItem();
                        this.mob.setCharging(false);
                        this.attackDelay = 10 + this.mob.getRandom().nextInt(5);
                        this.crossbowState = CrossbowState.CHARGED;
                        chargingTime = 0;
                    }
                }


                case CHARGED -> {
                    if (--this.attackDelay <= 0) {
                        this.crossbowState = CrossbowState.READY_TO_ATTACK;
                    }
                }

                case READY_TO_ATTACK -> {
                    if (!canSee) return;

                    // SPELL CAST ATTEMPT
                    Identifier spellId = getCrossbowSpellId();
                    if (spellId != null && !isSpellOnCooldown(spellId) && this.mob.getRandom().nextFloat() < 0.15f) {
                        Optional<RegistryEntry.Reference<Spell>> optSpell = SpellRegistry.from(this.mob.getWorld()).getEntry(spellId);
                        if (optSpell.isPresent()) {
                            cachedSpellEntry = optSpell.get();
                            Spell spell = cachedSpellEntry.value();

                            castCrossbowSpell(target, spell, cachedSpellEntry);
                            spellCooldowns.put(spellId, 60); // cooldown in ticks
                            this.crossbowState = CrossbowState.UNCHARGED;
                            return;
                        }
                    }

                    // NORMAL SHOOT
                    ItemStack crossbowStack = this.mob.getStackInHand(GuardVillagers.getHandWith(this.mob, item -> item instanceof CrossbowItem));
                    Hand hand = GuardVillagers.getHandWith(this.mob, item -> item instanceof CrossbowItem);
                    CrossbowItem crossbowItem = (CrossbowItem) crossbowStack.getItem();

                    if (!CrossbowItem.isCharged(crossbowStack)) {
                        ItemStack ammo = this.mob.getProjectileType(crossbowStack);
                        if (ammo.isEmpty()) ammo = new ItemStack(Items.ARROW);

                        crossbowStack.set(
                                net.minecraft.component.DataComponentTypes.CHARGED_PROJECTILES,
                                net.minecraft.component.type.ChargedProjectilesComponent.of(List.of(ammo.copyWithCount(1)))
                        );
                    }

                    ((CrossbowItemAccessor) crossbowItem).callShootAll(
                            this.mob.getWorld(), this.mob, hand, crossbowStack,
                            2.0F, 1.0F, target
                    );

                    ((CrossbowUser) this.mob).setCharging(false);
                    this.crossbowState = CrossbowState.UNCHARGED;
                }
            }
        }
    }

    private Identifier getCrossbowSpellId() {
        if (mob instanceof GuardEntity guard) {
            String raw = guard.getBowSkill();
            return raw != null && !raw.equals("none") ? Identifier.tryParse(raw) : null;
        }
        return null;
    }

    private boolean isSpellOnCooldown(Identifier spellId) {
        return spellCooldowns.getOrDefault(spellId, 0) > 0;
    }

    private void castCrossbowSpell(LivingEntity target, Spell spell, RegistryEntry<Spell> spellEntry) {
        SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, mob))
                .position(mob.getEyePos())
                .target(SpellHelper.focusMode(spell))
                .distance(1.0f);

        switch (String.valueOf(spell.deliver.type).toUpperCase()) {
            case "PROJECTILE" -> {
                Spell.ProjectileData.Perks perks = spell.deliver.projectile.projectile.perks != null
                        ? spell.deliver.projectile.projectile.perks.copy()
                        : new Spell.ProjectileData.Perks();

                Vec3d launchPos = SpellHelper.launchPoint(mob);
                Vec3d direction = target.getEyePos().subtract(launchPos).normalize();
                float velocity = spell.deliver.projectile.launch_properties.velocity;
                float divergence = spell.deliver.projectile.projectile.divergence;

                SpellProjectile projectile = new SpellProjectile(
                        mob.getWorld(), mob, launchPos.x, launchPos.y, launchPos.z,
                        SpellProjectile.Behaviour.FLY, spellEntry, context, perks
                );

                projectile.setVelocity(direction.x, direction.y, direction.z, velocity, divergence);
                projectile.range = spell.range;

                mob.getWorld().spawnEntity(projectile);
                playSpellSound(spell);
            }

            case "METEOR" -> {
                SpellHelper.fallProjectile(mob.getWorld(), mob, target, target.getPos(), spellEntry, context);
                playSpellSound(spell);
            }

            case "DIRECT" -> {
                SpellHelper.performImpacts(mob.getWorld(), mob, target, mob, spellEntry, spell.impacts, context);
                playSpellSound(spell);
            }
        }
        ItemStack crossbow = mob.getStackInHand(GuardVillagers.getHandWith(mob, item -> item instanceof CrossbowItem));
        if (!crossbow.isEmpty()) {
            crossbow.remove(net.minecraft.component.DataComponentTypes.CHARGED_PROJECTILES);
        }
    }
    private void shootCrossbowProjectileWithScaling(LivingEntity target, ItemStack crossbowStack, Hand hand) {
        ItemStack arrowStack = mob.getProjectileType(crossbowStack);
        if (arrowStack.isEmpty()) arrowStack = new ItemStack(Items.ARROW);

        PersistentProjectileEntity projectile = ProjectileUtil.createArrowProjectile(mob, arrowStack, 1.0F, crossbowStack);

        // ➕ Apply attribute-based scaling like in your bow code
        final double[] rangedDamage = {0.0};
        Identifier attrId = Identifier.of("ranged_weapon", "damage");
        Registries.ATTRIBUTE.getEntry(attrId).ifPresent(attr -> {
            if (mob.getAttributes().hasAttribute(attr)) {
                rangedDamage[0] = mob.getAttributeValue(attr);
            }
        });

        projectile.setDamage(projectile.getDamage() + rangedDamage[0] / 2);

        // Aim and fire
        Vec3d targetPos = target.getEyePos();
        double dx = targetPos.x - mob.getX();
        double dy = targetPos.y - projectile.getY();
        double dz = targetPos.z - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        projectile.setVelocity(dx, dy + dist * 0.2D, dz, 2.0F, 1.0F);

        mob.getWorld().spawnEntity(projectile);

        // ✅ Remove used arrow from charged projectiles
        crossbowStack.remove(DataComponentTypes.CHARGED_PROJECTILES);

        // 🔊 Optional: play firing sound
        mob.playSound(SoundEvents.ITEM_CROSSBOW_SHOOT, 1.0F, 1.0F);
    }

    private void playSpellSound(Spell spell) {
        if (spell.release != null && spell.release.sound != null) {
            Identifier soundId = Identifier.tryParse(spell.release.sound.id());
            if (soundId != null) {
                SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
                mob.getWorld().playSound(null, mob.getBlockPos(), sound, net.minecraft.sound.SoundCategory.HOSTILE, 1.0F, 1.0F);
            }
        }
    }

    private boolean friendlyInLineOfSight() {
        List<Entity> list = this.mob.getWorld().getOtherEntities(this.mob, this.mob.getBoundingBox().expand(5.0D));
        for (Entity guard : list) {
            if (guard != this.mob.getTarget()) {
                boolean isVillager = ((GuardEntity) this.mob).getOwner() == guard || guard.getType() == EntityType.VILLAGER || guard.getType() == GuardVillagers.GUARD_VILLAGER || guard.getType() == EntityType.IRON_GOLEM;
                if (isVillager) {
                    Vec3d direction = this.mob.getRotationVector();
                    Vec3d toEntity = guard.getPos().relativize(this.mob.getPos()).normalize();
                    if (toEntity.dotProduct(direction) < 1.0D && this.mob.canSee(guard) && guard.distanceTo(this.mob) <= 4.0D)
                        return true;
                }
            }
        }
        return false;
    }

    public boolean findPosition() {
        Vec3d pos = this.getPosition();
        if (pos == null) return false;

        this.wantedX = pos.x;
        this.wantedY = pos.y;
        this.wantedZ = pos.z;
        return true;
    }

    @Nullable
    protected Vec3d getPosition() {
        if (this.isValidTarget())
            return NoPenaltyTargeting.findFrom(this.mob, 16, 7, this.mob.getTarget().getPos());
        else
            return NoPenaltyTargeting.find(this.mob, 16, 7);
    }

    private boolean canRun() {
        return this.crossbowState == CrossbowState.UNCHARGED;
    }

    public enum CrossbowState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        READY_TO_ATTACK,
        FIND_NEW_POSITION
    }
}
