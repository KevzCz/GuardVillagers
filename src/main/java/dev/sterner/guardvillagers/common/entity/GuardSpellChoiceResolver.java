package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.SpellDataComponents;
import net.spell_engine.api.spell.container.SpellChoice;
import net.spell_engine.api.spell.container.SpellContainer;
import net.spell_engine.api.spell.container.SpellContainerHelper;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.api.tags.SpellEngineItemTags;
import net.spell_engine.item.ScrollItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GuardSpellChoiceResolver {

    private GuardSpellChoiceResolver() {}

    
    public static boolean resolveForGuard(GuardEntity guard) {
        if (guard.getWorld().isClient() || !(guard.getWorld() instanceof ServerWorld)) {
            return false;
        }
        if (guard.guardInventory == null) {
            return false;
        }

        boolean changed = false;
        changed |= resolveInventorySlot(guard, GuardEntity.SPELL_SLOT_INDEX);
        changed |= resolveInventorySlot(guard, 5);
        changed |= resolveInventorySlot(guard, 4);
        return changed;
    }

    private static boolean resolveInventorySlot(GuardEntity guard, int slot) {
        ItemStack stack = guard.guardInventory.getStack(slot);
        if (!shouldResolve(stack)) {
            return false;
        }
        ItemStack before = stack.copy();
        resolveStack(guard, stack);
        if (ItemStack.areEqual(before, stack)) {
            return false;
        }
        guard.guardInventory.setStack(slot, stack);
        return true;
    }

    private static void resolveStack(GuardEntity guard, ItemStack stack) {
        SpellChoice choice = stack.get(SpellDataComponents.SPELL_CHOICE);
        if (choice != null && !choice.isEmpty()) {
            bindRandomFromChoicePool(guard, stack, choice);
            return;
        }

        trimExcessBoundSpells(guard, stack);
    }

    private static void bindRandomFromChoicePool(GuardEntity guard, ItemStack stack, SpellChoice choice) {
        List<Identifier> poolSpells = collectPoolSpells((ServerWorld) guard.getWorld(), choice.pool());
        if (poolSpells.isEmpty()) {
            return;
        }

        Identifier picked = poolSpells.get(guard.getRandom().nextInt(poolSpells.size()));
        SpellContainer container = SpellContainerHelper.containerFromItemStack(stack);
        if (container == null) {
            container = new SpellContainer(SpellContainer.ContentType.MAGIC, "", "", 0, List.of());
        }

        stack.set(SpellDataComponents.SPELL_CONTAINER, container.withSpellId(picked));
        stack.remove(SpellDataComponents.SPELL_CHOICE);

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "🎲 Rolled spell choice → " + picked.getPath(),
                    Formatting.AQUA);
        }
    }

    private static void trimExcessBoundSpells(GuardEntity guard, ItemStack stack) {
        SpellContainer container = SpellContainerHelper.containerFromItemStack(stack);
        if (container == null || container.spell_ids().size() <= 1) {
            return;
        }

        int allowed = container.max_spell_count();
        if (allowed != 1) {
            return;
        }

        List<String> bound = container.spell_ids();
        String picked = bound.get(guard.getRandom().nextInt(bound.size()));
        stack.set(SpellDataComponents.SPELL_CONTAINER, container.withSpell(picked));

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "🎲 Picked weapon spell → " + picked,
                    Formatting.AQUA);
        }
    }

    private static List<Identifier> collectPoolSpells(ServerWorld world, String pool) {
        if (pool == null || pool.isBlank()) {
            return List.of();
        }

        Identifier poolId = Identifier.tryParse(pool);
        if (poolId == null) {
            return List.of();
        }

        Set<Identifier> spells = new LinkedHashSet<>();
        for (Identifier resolved : resolvePoolIds(poolId)) {
            for (RegistryEntry<Spell> entry : SpellRegistry.entries(world, resolved)) {
                entry.getKey().ifPresent(key -> spells.add(key.getValue()));
            }
        }
        return new ArrayList<>(spells);
    }

    private static List<Identifier> resolvePoolIds(Identifier poolId) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        ids.add(poolId);
        if (!poolId.getPath().contains("/")) {
            ids.add(Identifier.of(poolId.getNamespace(), "spell_scroll/" + poolId.getPath()));
        } else if (poolId.getPath().startsWith("spell_scroll/")) {
            ids.add(Identifier.of(poolId.getNamespace(), poolId.getPath().substring("spell_scroll/".length())));
        }
        return List.copyOf(ids);
    }

    private static boolean shouldResolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof ScrollItem) {
            return false;
        }
        return !stack.isIn(SpellEngineItemTags.SPELL_BOOK);
    }
}
