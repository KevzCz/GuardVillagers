package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellHelper;
import net.spell_engine.internals.arrow.ArrowHelper;
import net.spell_engine.internals.arrow.ArrowShootContext;
import net.spell_engine.mixin.item.RangedWeaponAccessor;
import net.spell_engine.utils.WorldScheduler;

import java.util.List;

public final class GuardSpellArrowDelivery {

    private GuardSpellArrowDelivery() {}

    public static void shoot(SpellContext context, int channelOffset) {
        LivingEntity caster = context.caster();
        World world = caster.getWorld();
        if (world.isClient()) {
            return;
        }

        Spell spell = context.spell();
        if (spell.deliver == null || spell.deliver.shoot_arrow == null) {
            return;
        }

        ItemStack weapon = caster.getMainHandStack();
        if (weapon.getItem() instanceof BowItem) {
            shootWithBow(context, channelOffset);
            return;
        }

        if (weapon.getItem() instanceof CrossbowItem && caster instanceof GuardEntity guard) {
            shootWithCrossbow(guard, context, channelOffset);
            return;
        }

        throw new IllegalStateException(
                "SHOOT_ARROW requires a bow or crossbow in main hand, got "
                        + weapon.getItem().getName().getString());
    }

    private static void shootWithBow(SpellContext context, int channelOffset) {
        LivingEntity caster = context.caster();
        ArrowShootContext shootContext = new ArrowShootContext();
        shootContext.firedBySpell = true;
        shootContext.activeSpells.add(context.entry());

        if (caster instanceof net.spell_engine.internals.casting.SpellCasterEntity spellCaster) {
            spellCaster.setArrowShootContext(shootContext);
            try {
                ArrowHelper.shootArrow(
                        caster.getWorld(),
                        caster,
                        context.entry(),
                        context.impactContext(),
                        channelOffset
                );
            } finally {
                spellCaster.setArrowShootContext(ArrowShootContext.empty());
            }
        } else {
            ArrowHelper.shootArrow(
                    caster.getWorld(),
                    caster,
                    context.entry(),
                    context.impactContext(),
                    channelOffset
            );
        }
    }

    private static void shootWithCrossbow(GuardEntity guard, SpellContext context, int channelOffset) {
        ServerWorld world = (ServerWorld) guard.getWorld();
        Spell spell = context.spell();
        Spell.Delivery.ShootArrow shootArrow = spell.deliver.shoot_arrow;
        Spell.LaunchProperties launch = shootArrow.launch_properties.copy();
        ItemStack crossbow = guard.getMainHandStack();

        ensureCrossbowCharged(guard, crossbow);

        ItemStack ammo = new ItemStack(Items.ARROW);
        List<ItemStack> projectiles = RangedWeaponAccessor.load_SpellEngine(crossbow, ammo, guard);
        if (projectiles.isEmpty()) {
            return;
        }

        ArrowShootContext shootContext = new ArrowShootContext();
        shootContext.firedBySpell = true;
        shootContext.activeSpells.add(context.entry());
        guard.setArrowShootContext(shootContext);

        try {
            float divergence = channelOffset > 0 ? shootArrow.divergence : 0f;
            RangedWeaponAccessor accessor = (RangedWeaponAccessor) crossbow.getItem();
            accessor.shootAll_SpellEngine(
                    world,
                    guard,
                    Hand.MAIN_HAND,
                    crossbow,
                    projectiles,
                    launch.velocity,
                    divergence,
                    shootArrow.arrow_critical_strike,
                    context.target()
            );
            world.playSound(
                    null,
                    guard.getX(),
                    guard.getY(),
                    guard.getZ(),
                    SoundEvents.ITEM_CROSSBOW_SHOOT,
                    SoundCategory.PLAYERS,
                    1.0F,
                    1.0F
            );
            scheduleExtraLaunches(guard, context, channelOffset, launch);
        } finally {
            guard.setArrowShootContext(ArrowShootContext.empty());
        }
    }

    private static void ensureCrossbowCharged(GuardEntity guard, ItemStack crossbow) {
        if (CrossbowItem.isCharged(crossbow)) {
            return;
        }
        ItemStack ammo = guard.getProjectileType(crossbow);
        if (ammo.isEmpty()) {
            ammo = new ItemStack(Items.ARROW);
        }
        crossbow.set(
                DataComponentTypes.CHARGED_PROJECTILES,
                ChargedProjectilesComponent.of(List.of(ammo.copyWithCount(1)))
        );
    }

    private static void scheduleExtraLaunches(
            GuardEntity guard,
            SpellContext context,
            int channelOffset,
            Spell.LaunchProperties launch
    ) {
        if (channelOffset > 0 || launch.extra_launch_count <= 0) {
            return;
        }
        World world = guard.getWorld();
        if (!(world instanceof WorldScheduler scheduler)) {
            return;
        }

        RegistryEntry<Spell> entry = context.entry();
        SpellHelper.ImpactContext impactContext = context.impactContext();

        for (int i = 0; i < launch.extra_launch_count; i++) {
            int delay = (i + 1) * Math.max(1, launch.extra_launch_delay);
            int nextOffset = i + 1;
            scheduler.schedule(delay, () -> {
                if (!guard.isAlive()) {
                    return;
                }
                SpellContext retry = SpellContext.builder()
                        .spellId(context.spellId())
                        .entry(entry)
                        .caster(guard)
                        .target(context.target())
                        .buildImpactContext()
                        .build();
                shoot(retry, nextOffset);
            });
        }
    }
}
