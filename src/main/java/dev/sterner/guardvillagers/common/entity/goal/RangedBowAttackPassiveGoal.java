package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.ConditionalSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellContext;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.item.BowItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;

import java.util.EnumSet;
import java.util.Optional;

public class RangedBowAttackPassiveGoal<T extends GuardEntity & RangedAttackMob> extends BaseSpellGoal {
    private final T actor;
    private final double speed;
    private final float squaredRange;
    private final ConditionalSpellGoal conditionalSpells;

    private int cooldown = 0;
    private int targetSeeingTicker = 0;
    private int combatTicks = -1;
    private boolean movingToLeft = false;
    private boolean backward = false;

    public RangedBowAttackPassiveGoal(T actor, double speed, int attackInterval, float range) {
        super(actor);
        this.actor = actor;
        this.speed = speed;
        this.squaredRange = range * range;
        this.conditionalSpells = new ConditionalSpellGoal(actor);
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
        super.stop();
        actor.setAttacking(false);
        actor.clearActiveItem();
    }

    @Override
    public void tick() {
        LivingEntity target = actor.getTarget();
        if (target == null || !target.isAlive()) return;

        updateBowMovement(target);
        tickCooldowns();

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (actor.isUsingItem()) {
            handleBowDrawn(target);
        } else if (targetSeeingTicker >= -60) {
            actor.setCurrentHand(Hand.MAIN_HAND);
        }
    }

    private void updateBowMovement(LivingEntity target) {
        var mv = CombatMovementHelper.applyBowOrbitMovement(
                actor, target, this.speed, this.squaredRange,
                this.targetSeeingTicker, this.combatTicks,
                this.movingToLeft, this.backward
        );

        this.targetSeeingTicker = mv.targetSeeingTicker();
        this.combatTicks = mv.combatTicks();
        this.movingToLeft = mv.movingToLeft();
        this.backward = mv.backward();
    }

    private void handleBowDrawn(LivingEntity target) {
        int useTime = actor.getItemUseTime();
        boolean canSee = actor.getVisibilityCache().canSee(target);

        if (!canSee || useTime < 30) return;

        if (cachedSpellEntry != null && useTime % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (useTime >= 30) {
            actor.clearActiveItem();

            if (trySpellCast(target)) {
                cooldown = 0;
            } else {
                ((RangedAttackMob) actor).shootAt(target, BowItem.getPullProgress(useTime));
            }
        }
    }

    private boolean trySpellCast(LivingEntity target) {
        Identifier stashSpellId = getStashEffectSpellId();
        if (stashSpellId != null && !isSpellOnCooldown(stashSpellId)) {
            return castSpell(stashSpellId, target);
        }

        Identifier supportSpellId = getSupportSpellId();
        if (supportSpellId != null && !isSpellOnCooldown(supportSpellId)) {
            return castSpell(supportSpellId, target);
        }

        float spellChance = getScaledSpellChance();
        if (actor.getRandom().nextFloat() >= spellChance) return false;

        Identifier spellId = getBowSpellId();
        if (spellId == null || isSpellOnCooldown(spellId)) return false;

        return castSpell(spellId, target);
    }

    private Identifier getStashEffectSpellId() {
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = actor.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                spell -> {
                    Spell s = spell.entry().value();
                    return s.deliver != null
                            && s.deliver.type == Spell.Delivery.Type.STASH_EFFECT
                            && !isSpellOnCooldown(spell.spellId());
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean castSpell(Identifier spellId, LivingEntity target) {
        return getSpellEntry(spellId).map(entry -> {
            cachedSpellEntry = entry;
            currentSpellId = spellId;

            SpellContext context = createSpellContext(spellId, entry, target).build();
            Spell spell = entry.value();

            if (spell.deliver != null) {
                switch (spell.deliver.type) {
                    case SHOOT_ARROW, PROJECTILE -> SpellDelivery.castProjectile(context, 0);
                    case METEOR -> SpellDelivery.castMeteor(context);
                    case CLOUD -> SpellDelivery.castCloud(context);
                    case DIRECT -> SpellDelivery.castDirect(context);
                    case STASH_EFFECT -> SpellDelivery.castStashEffect(context);
                    case MELEE -> SpellDelivery.castDirect(context);
                    case CUSTOM -> SpellDelivery.castCustom(context);
                }
            } else {
                SpellDelivery.castDirect(context);
            }

            spellCooldowns.put(spellId, getCooldownTicks(spell));
            cachedSpellEntry = null;
            currentSpellId = null;
            return true;
        }).orElse(false);
    }

    private float getScaledSpellChance() {
        double rangedDamage = 0.0;

        Optional<RegistryEntry.Reference<net.minecraft.entity.attribute.EntityAttribute>> attrOpt =
                Registries.ATTRIBUTE.getEntry(Identifier.of("ranged_weapon", "damage"));

        if (attrOpt.isPresent()) {
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr = attrOpt.get();
            if (actor.getAttributes().hasAttribute(attr)) {
                rangedDamage = actor.getAttributeValue(attr);
            }
        }

        double minChance = 0.05;
        double maxChance = 0.5;
        double maxDamage = 50.0;

        double chance = minChance + (Math.min(rangedDamage, maxDamage) / maxDamage) * (maxChance - minChance);
        return (float) chance;
    }

    private Identifier getSupportSpellId() {
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = actor.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.SUPPORT,
                spell -> {
                    Spell s = spell.entry().value();
                    boolean isNotStashEffect = s.deliver == null || s.deliver.type != Spell.Delivery.Type.STASH_EFFECT;
                    return isNotStashEffect && conditionalSpells.canCastSpell(spell.spellId()) && !isSpellOnCooldown(spell.spellId());
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private Identifier getBowSpellId() {
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = actor.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                spell -> {
                    Spell s = spell.entry().value();
                    boolean isNotStashEffect = s.deliver == null || s.deliver.type != Spell.Delivery.Type.STASH_EFFECT;
                    return isNotStashEffect
                            && !isSpellOnCooldown(spell.spellId())
                            && isArchetypeCompatibleWithBow(spell);
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean isArchetypeCompatibleWithBow(GuardSpellManager.CategorizedSpell spell) {
        Spell s = spell.entry().value();

        if (s.school == null || s.school.archetype == null) {
            return true;
        }
        return s.school.archetype == net.spell_power.api.SpellSchool.Archetype.ARCHERY;
    }

    private boolean isHoldingBow() {
        return actor.getMainHandStack().getItem() instanceof BowItem;
    }
}