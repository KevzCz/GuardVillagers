package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.common.ai.GuardWeaponArchetype;
import dev.sterner.guardvillagers.common.debug.*;
import net.minecraft.component.type.*;
import net.minecraft.entity.*;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.*;
import net.minecraft.registry.tag.*;
import net.minecraft.util.*;
import net.spell_engine.api.item.set.*;
import net.spell_engine.api.spell.*;
import net.spell_engine.api.spell.container.*;
import net.spell_engine.api.spell.registry.*;
import net.spell_engine.api.tags.*;
import net.spell_engine.item.*;
import net.spell_power.api.*;

import java.util.*;

public class GuardSpellManager {
    private final GuardEntity guard;

    private final List<CategorizedSpell> activeSpells = new ArrayList<>();
    private final List<CategorizedSpell> passiveSpells = new ArrayList<>();
    private final EnumMap<SpellCategory, List<CategorizedSpell>> activeByCategory = new EnumMap<>(SpellCategory.class);
    private final EnumMap<SpellCategory, List<CategorizedSpell>> passiveByCategory = new EnumMap<>(SpellCategory.class);
    private final Set<Identifier> knownSpellIds = new HashSet<>();
    private List<CategorizedSpell> cachedMeleeOnHitSpells = List.of();

    private ItemStack lastMainhand = ItemStack.EMPTY;
    private ItemStack lastOffhand = ItemStack.EMPTY;
    private ItemStack lastSpellSlot = ItemStack.EMPTY;
    private ItemStack lastHead = ItemStack.EMPTY;
    private ItemStack lastChest = ItemStack.EMPTY;
    private ItemStack lastLegs = ItemStack.EMPTY;
    private ItemStack lastFeet = ItemStack.EMPTY;
    private String lastLoadoutHash = "";

    private final List<RegistryEntry<Spell>> modifierSpells = new ArrayList<>();
    private final List<AttributeModifiersComponent> activeSetAttributes = new ArrayList<>();

    public enum SpellCategory {
        MELEE,
        RANGED_BOW,
        PROJECTILE,
        AREA,
        HEALING,
        SUPPORT,
        PASSIVE_DAMAGE,
        PASSIVE_DEFENSE
    }

    public record CategorizedSpell(
            Identifier spellId,
            RegistryEntry<Spell> entry,
            SpellCategory category,
            boolean isPassive,
            ItemSource source
    ) {}

    public enum ItemSource {
        MAINHAND,
        OFFHAND,
        SPELL_SLOT,
        ARMOR,
        EQUIPMENT_SET
    }

    public GuardSpellManager(GuardEntity guard) {
        this.guard = guard;
    }

    public void refresh() {
        if (!guard.getWorld().isClient()) {
            GuardSpellChoiceResolver.resolveForGuard(guard);
        }

        ItemStack mainhand = guard.getMainHandStack();
        ItemStack offhand = guard.getOffHandStack();
        ItemStack spellSlot = guard.getSpellSlotStack();
        ItemStack head = guard.getEquippedStack(EquipmentSlot.HEAD);
        ItemStack chest = guard.getEquippedStack(EquipmentSlot.CHEST);
        ItemStack legs = guard.getEquippedStack(EquipmentSlot.LEGS);
        ItemStack feet = guard.getEquippedStack(EquipmentSlot.FEET);

        String loadoutHash = computeLoadoutHash(mainhand, offhand, spellSlot, head, chest, legs, feet);
        boolean loadoutChanged = !loadoutHash.equals(lastLoadoutHash);

        if (!loadoutChanged) {
            return;
        }

        boolean mainhandChanged = !areItemsEqualIgnoringDurability(lastMainhand, mainhand);
        boolean offhandChanged = !areItemsEqualIgnoringDurability(lastOffhand, offhand);
        boolean spellSlotChanged = !areItemsEqualIgnoringDurability(lastSpellSlot, spellSlot);
        boolean headChanged = !areItemsEqualIgnoringDurability(lastHead, head);
        boolean chestChanged = !areItemsEqualIgnoringDurability(lastChest, chest);
        boolean legsChanged = !areItemsEqualIgnoringDurability(lastLegs, legs);
        boolean feetChanged = !areItemsEqualIgnoringDurability(lastFeet, feet);

        activeSpells.clear();
        passiveSpells.clear();
        activeByCategory.clear();
        passiveByCategory.clear();
        knownSpellIds.clear();
        cachedMeleeOnHitSpells = List.of();
        modifierSpells.clear();
        removeSetAttributeModifiers();
        activeSetAttributes.clear();

        if (!mainhand.isEmpty()) {
            categorizeSpellsFromItem(mainhand, ItemSource.MAINHAND);
        }

        if (!offhand.isEmpty() && isSpellItem(offhand)) {
            categorizeSpellsFromItem(offhand, ItemSource.OFFHAND);
        }

        if (!spellSlot.isEmpty() && isSpellItem(spellSlot)) {
            categorizeSpellsFromItem(spellSlot, ItemSource.SPELL_SLOT);
        }

        for (ItemStack armorStack : List.of(head, chest, legs, feet)) {
            if (!armorStack.isEmpty() && isSpellItem(armorStack)) {
                categorizeSpellsFromItem(armorStack, ItemSource.ARMOR);
            }
        }

        collectEquipmentSetSpells(mainhand, offhand, spellSlot, head, chest, legs, feet);
        applySetAttributeModifiers();

        lastMainhand = mainhand.copy();
        lastOffhand = offhand.copy();
        lastSpellSlot = spellSlot.copy();
        lastHead = head.copy();
        lastChest = chest.copy();
        lastLegs = legs.copy();
        lastFeet = feet.copy();
        lastLoadoutHash = loadoutHash;

        rebuildCategoryIndex();

        if (!guard.getWorld().isClient() && (mainhandChanged || offhandChanged || spellSlotChanged
                || headChanged || chestChanged || legsChanged || feetChanged)) {
            debugPrintSpells();
        }

        interruptCastIfSpellRemoved();
    }

    private void rebuildCategoryIndex() {
        for (CategorizedSpell s : activeSpells) {
            activeByCategory.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
            knownSpellIds.add(s.spellId());
        }
        for (CategorizedSpell s : passiveSpells) {
            passiveByCategory.computeIfAbsent(s.category(), k -> new ArrayList<>()).add(s);
            knownSpellIds.add(s.spellId());
        }
        List<CategorizedSpell> onHit = new ArrayList<>();
        onHit.addAll(getPassiveSpells(SpellCategory.PASSIVE_DAMAGE));
        activeByCategory.getOrDefault(SpellCategory.MELEE, List.of()).stream()
                .filter(s -> isOnHitMeleeSpell(s.entry().value()))
                .forEach(onHit::add);
        cachedMeleeOnHitSpells = onHit.isEmpty() ? List.of() : onHit;
    }

    private void interruptCastIfSpellRemoved() {
        if (guard.getWorld().isClient() || !guard.isSpellCastBusy()) {
            return;
        }
        if (activeSpells.isEmpty()) {
            guard.interruptSpellCast();
            return;
        }
        var process = guard.getSpellCastProcess();
        if (process != null && !knowsSpell(process.id())) {
            guard.interruptSpellCast();
        }
    }

    public boolean knowsSpell(Identifier spellId) {
        return spellId != null && knownSpellIds.contains(spellId);
    }

    private boolean areItemsEqualIgnoringDurability(ItemStack a, ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (!a.isOf(b.getItem())) {
            return false;
        }
        if (a.getCount() != b.getCount()) {
            return false;
        }
        return spellContainerFingerprint(a).equals(spellContainerFingerprint(b));
    }

    private static String computeLoadoutHash(
            ItemStack mainhand,
            ItemStack offhand,
            ItemStack spellSlot,
            ItemStack head,
            ItemStack chest,
            ItemStack legs,
            ItemStack feet
    ) {
        StringBuilder sb = new StringBuilder(128);
        appendStackLoadout(sb, "main", mainhand);
        appendStackLoadout(sb, "off", offhand);
        appendStackLoadout(sb, "slot", spellSlot);
        appendStackLoadout(sb, "head", head);
        appendStackLoadout(sb, "chest", chest);
        appendStackLoadout(sb, "legs", legs);
        appendStackLoadout(sb, "feet", feet);
        return sb.toString();
    }

    private static void appendStackLoadout(StringBuilder sb, String label, ItemStack stack) {
        sb.append(label).append('=');
        if (stack.isEmpty()) {
            sb.append("empty;");
            return;
        }
        sb.append(Registries.ITEM.getId(stack.getItem())).append(':');
        sb.append(spellContainerFingerprint(stack)).append(';');
    }

    private static String spellContainerFingerprint(ItemStack stack) {
        SpellContainer container = SpellContainerHelper.containerFromItemStack(stack);
        if (container == null || container.spell_ids().isEmpty()) {
            return "none";
        }
        return String.join(",", container.spell_ids());
    }

    private boolean isSpellItem(ItemStack stack) {
        if (stack.isIn(SpellEngineItemTags.SPELL_BOOK) || stack.getItem() instanceof ScrollItem) {
            return true;
        }
        SpellContainer container = SpellContainerHelper.containerFromItemStack(stack);
        return container != null && !container.spell_ids().isEmpty();
    }

    private void categorizeSpellsFromItem(ItemStack stack, ItemSource source) {
        SpellContainer container = SpellContainerHelper.containerFromItemStack(stack);
        categorizeSpellsFromContainer(container, source, stack.getItem().getName().getString());
    }

    private void categorizeSpellsFromContainer(SpellContainer container, ItemSource source, String displayName) {
        if (container == null || container.spell_ids().isEmpty()) {
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "⚠ No spells found in: " + displayName,
                        Formatting.RED);
            }
            return;
        }

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "📖 Found " + container.spell_ids().size() + " spells in " + displayName,
                    Formatting.GREEN);
        }

        for (String spellIdString : container.spell_ids()) {
            Identifier spellId = Identifier.tryParse(spellIdString);
            if (spellId == null) {
                if (!guard.getWorld().isClient()) {
                    GuardDebugManager.broadcast(guard,
                            "❌ Invalid spell ID: " + spellIdString,
                            Formatting.RED);
                }
                continue;
            }

            Optional<RegistryEntry.Reference<Spell>> optEntry =
                    SpellRegistry.from(guard.getWorld()).getEntry(spellId);

            if (optEntry.isEmpty()) {
                if (!guard.getWorld().isClient()) {
                    GuardDebugManager.broadcast(guard,
                            "❌ Spell not found in registry: " + spellId,
                            Formatting.RED);
                }
                continue;
            }

            RegistryEntry<Spell> entry = optEntry.get();
            Spell spell = entry.value();

            if (!guard.getWorld().isClient()) {
                String deliverType = spell.deliver != null ? spell.deliver.type.name() : "NONE";
                String targetType = spell.target != null ? spell.target.type.name() : "NONE";
                GuardDebugManager.broadcast(guard,
                        "🔍 Processing: " + spellId.getPath() + " | Deliver: " + deliverType + " | Target: " + targetType,
                        Formatting.GRAY);
            }

            categorizeSpell(spellId, entry, spell, source);
        }
    }

    private void collectEquipmentSetSpells(ItemStack mainhand, ItemStack offhand, ItemStack spellSlot,
                                           ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {
        List<EquipmentSet.SourcedItemStack> sourcedStacks = new ArrayList<>();
        if (!head.isEmpty())      sourcedStacks.add(new EquipmentSet.SourcedItemStack(head,     "head"));
        if (!chest.isEmpty())     sourcedStacks.add(new EquipmentSet.SourcedItemStack(chest,    "chest"));
        if (!legs.isEmpty())      sourcedStacks.add(new EquipmentSet.SourcedItemStack(legs,     "legs"));
        if (!feet.isEmpty())      sourcedStacks.add(new EquipmentSet.SourcedItemStack(feet,     "feet"));
        if (!mainhand.isEmpty())  sourcedStacks.add(new EquipmentSet.SourcedItemStack(mainhand, "mainhand"));
        if (!offhand.isEmpty())   sourcedStacks.add(new EquipmentSet.SourcedItemStack(offhand,  "offhand"));
        if (!spellSlot.isEmpty()) sourcedStacks.add(new EquipmentSet.SourcedItemStack(spellSlot,"spell_slot"));

        if (sourcedStacks.isEmpty()) return;

        List<EquipmentSet.Result> results = EquipmentSet.collectFrom(sourcedStacks, guard.getWorld());

        for (EquipmentSet.Result result : results) {
            EquipmentSet.Definition definition = result.set().value();
            int pieceCount = result.items().size();

            for (EquipmentSet.Bonus bonus : definition.bonuses()) {
                if (pieceCount >= bonus.requiredPieceCount()) {
                    bonus.getSpells().ifPresent(container -> {
                        if (container.spell_ids() != null && !container.spell_ids().isEmpty()) {
                            String setLabel = "Set: " + definition.name()
                                    + " (" + pieceCount + "/" + bonus.requiredPieceCount() + ")";
                            categorizeSpellsFromContainer(container, ItemSource.EQUIPMENT_SET, setLabel);
                        }
                    });
                    bonus.getAttributes().ifPresent(attrComponent -> {
                        activeSetAttributes.add(attrComponent);
                    });
                }
            }
        }
    }

    private void removeSetAttributeModifiers() {
        if (guard.getWorld().isClient()) return;
        for (AttributeModifiersComponent component : activeSetAttributes) {
            for (var entry : component.modifiers()) {
                var instance = guard.getAttributeInstance(entry.attribute());
                if (instance != null) {
                    instance.removeModifier(entry.modifier().id());
                }
            }
        }
    }

    private void applySetAttributeModifiers() {
        if (guard.getWorld().isClient()) return;
        for (AttributeModifiersComponent component : activeSetAttributes) {
            for (var entry : component.modifiers()) {
                var instance = guard.getAttributeInstance(entry.attribute());
                if (instance != null) {
                    instance.removeModifier(entry.modifier().id());
                    instance.addTemporaryModifier(entry.modifier());
                }
            }
        }
    }

    public boolean mainhandHasSpells() {
        ItemStack mainhand = guard.getMainHandStack();
        if (mainhand.isEmpty()) return false;

        SpellContainer container = SpellContainerHelper.containerFromItemStack(mainhand);
        return container != null && !container.spell_ids().isEmpty();
    }

    public static boolean isValidSpellSource(ItemStack stack) {
        if (stack.isEmpty()) return false;

        SpellContainer container = SpellContainerHelper.containerFromItemStack(stack);
        return container != null && !container.spell_ids().isEmpty();
    }

    private void categorizeSpell(Identifier id, RegistryEntry<Spell> entry, Spell spell, ItemSource source) {
        if (spell.type == Spell.Type.MODIFIER) {
            modifierSpells.add(entry);
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  → Modifier spell registered: " + id.getPath(),
                        Formatting.GRAY);
            }
            return;
        }

        Set<SpellCategory> categories = determineCategories(spell);

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "  → Initial categories for " + id.getPath() + ": " + categories,
                    Formatting.GRAY);
        }

        boolean isPassive = spell.type == Spell.Type.PASSIVE;

        categories = filterCategoriesBySource(spell, categories, source);
        if (!isPassive) {
            boolean skipArchetypeFilter = spell.deliver != null
                    && spell.deliver.type == Spell.Delivery.Type.STASH_EFFECT
                    && isMeleeArchetype(spell);
            if (!skipArchetypeFilter) {
                categories = GuardWeaponArchetype.filterActiveCategories(
                        GuardWeaponArchetype.resolve(guard.getMainHandStack()),
                        categories);
            }
        }

        if (!guard.getWorld().isClient()) {
            GuardDebugManager.broadcast(guard,
                    "  → Filtered categories for " + id.getPath() + ": " + categories,
                    Formatting.GRAY);
        }

        if (categories.isEmpty()) {
            if (!guard.getWorld().isClient()) {
                GuardDebugManager.broadcast(guard,
                        "  ⚠ No categories assigned to " + id.getPath() + "!",
                        Formatting.YELLOW);
            }
            return;
        }

        for (SpellCategory cat : categories) {
            CategorizedSpell categorized = new CategorizedSpell(id, entry, cat, isPassive, source);

            if (isPassive) {
                passiveSpells.add(categorized);
            } else {
                activeSpells.add(categorized);
            }
        }
    }

    private Set<SpellCategory> determineCategories(Spell spell) {
        Set<SpellCategory> categories = EnumSet.noneOf(SpellCategory.class);

        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.STASH_EFFECT) {
            categories.add(SpellCategory.SUPPORT);
            if (spell.school != null && spell.school.archetype == SpellSchool.Archetype.ARCHERY) {
                categories.add(SpellCategory.RANGED_BOW);
            }
            if (hasHealingImpact(spell)) {
                categories.add(SpellCategory.HEALING);
            }
            return categories;
        }

        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.AFFECT_ARROW) {

            categories.add(SpellCategory.RANGED_BOW);
            categories.add(SpellCategory.SUPPORT);
            return categories;
        }

        if (spell.target != null) {
            switch (spell.target.type) {
                case AREA -> {
                    categories.add(SpellCategory.AREA);
                    if (hasHealingImpact(spell)) {
                        categories.add(SpellCategory.HEALING);
                    }
                    if (isMeleeArchetype(spell)) {
                        categories.add(SpellCategory.MELEE);
                    }
                    if (hasStatusEffectImpact(spell) || hasSupportImpact(spell)) {
                        categories.add(SpellCategory.SUPPORT);
                    }
                }
                case AIM -> categories.add(SpellCategory.PROJECTILE);
                case BEAM -> categories.add(SpellCategory.PROJECTILE);
                default -> {}
            }
        }

        if (spell.deliver == null && hasDamageImpact(spell)) {
            if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
                categories.add(SpellCategory.AREA);
            } else if (spell.target != null && spell.target.type == Spell.Target.Type.AIM) {
                categories.add(SpellCategory.PROJECTILE);
            }
        }

        SpellSchool school = spell.school;

        if (school != null) {
            switch (school.archetype) {
                case ARCHERY -> {
                    categories.add(SpellCategory.RANGED_BOW);

                    if (spell.deliver != null) {
                        switch (spell.deliver.type) {
                            case METEOR -> categories.add(SpellCategory.AREA);
                            case PROJECTILE, SHOOT_ARROW -> categories.add(SpellCategory.PROJECTILE);
                            case CLOUD -> categories.add(SpellCategory.AREA);
                            case CUSTOM -> categories.add(SpellCategory.PROJECTILE);
                            case AFFECT_ARROW -> categories.add(SpellCategory.SUPPORT);
                            default -> {}
                        }
                    }
                }
                case MAGIC -> {
                    if (spell.deliver != null) {
                        switch (spell.deliver.type) {
                            case PROJECTILE -> categories.add(SpellCategory.PROJECTILE);
                            case SHOOT_ARROW -> {
                                categories.add(SpellCategory.PROJECTILE);
                                categories.add(SpellCategory.RANGED_BOW);
                            }
                            case AFFECT_ARROW -> {
                                categories.add(SpellCategory.RANGED_BOW);
                                categories.add(SpellCategory.SUPPORT);
                            }
                            case METEOR, CLOUD -> categories.add(SpellCategory.AREA);
                            case CUSTOM -> {
                                if (spell.type != Spell.Type.PASSIVE) {
                                    categories.add(SpellCategory.PROJECTILE);
                                }
                            }
                            case DIRECT -> {
                                if (spell.target != null && spell.target.type == Spell.Target.Type.AIM) {
                                    categories.add(SpellCategory.PROJECTILE);
                                } else if (spell.target != null && spell.target.type == Spell.Target.Type.FROM_TRIGGER) {
                                    categories.add(SpellCategory.SUPPORT);
                                } else if (spell.target != null && spell.target.type == Spell.Target.Type.CASTER) {
                                    categories.add(SpellCategory.SUPPORT);
                                }
                            }
                            default -> {}
                        }
                    } else {
                        if (hasSupportImpact(spell) || appliesStatusEffectToCaster(spell) || hasSummonImpact(spell)) {
                            categories.add(SpellCategory.SUPPORT);
                        }
                    }
                }
                case MELEE -> {
                    categories.add(SpellCategory.MELEE);

                    if (spell.deliver != null) {
                        switch (spell.deliver.type) {
                            case CLOUD, METEOR -> categories.add(SpellCategory.AREA);
                            case PROJECTILE -> categories.add(SpellCategory.PROJECTILE);
                            case SHOOT_ARROW -> {
                                categories.add(SpellCategory.PROJECTILE);
                                categories.add(SpellCategory.RANGED_BOW);
                            }
                            case AFFECT_ARROW -> {
                                categories.add(SpellCategory.RANGED_BOW);
                                categories.add(SpellCategory.SUPPORT);
                            }
                            case MELEE -> categories.add(SpellCategory.MELEE);
                            case CUSTOM -> {
                                if (spell.type != Spell.Type.PASSIVE) {
                                    categories.add(SpellCategory.PROJECTILE);
                                }
                            }
                            case DIRECT -> {
                                if (spell.target != null && spell.target.type == Spell.Target.Type.FROM_TRIGGER) {
                                    categories.add(SpellCategory.SUPPORT);
                                }
                            }
                            default -> {}
                        }
                    }

                    if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
                        categories.add(SpellCategory.PROJECTILE);
                    }
                }
                default -> {}
            }
        }

        if (hasHealingImpact(spell)) {
            categories.add(SpellCategory.HEALING);
        }

        if (hasSupportImpact(spell)) {
            categories.add(SpellCategory.SUPPORT);
        }

        if (hasSummonImpact(spell)) {
            categories.add(SpellCategory.SUPPORT);
        }

        if (appliesStatusEffectToCaster(spell)) {
            categories.add(SpellCategory.SUPPORT);
        }

        if (spell.type == Spell.Type.PASSIVE && spell.passive != null) {
            for (Spell.Trigger trigger : spell.passive.triggers) {
                switch (trigger.type) {
                    case MELEE_IMPACT -> {
                        categories.add(SpellCategory.PASSIVE_DAMAGE);
                        categories.add(SpellCategory.MELEE);
                    }
                    case DAMAGE_TAKEN, SHIELD_BLOCK -> categories.add(SpellCategory.PASSIVE_DEFENSE);
                    case ARROW_SHOT, ARROW_IMPACT -> categories.add(SpellCategory.RANGED_BOW);
                    case SPELL_CAST -> categories.add(SpellCategory.PROJECTILE);
                    case SPELL_IMPACT_ANY, SPELL_IMPACT_SPECIFIC -> {
                        categories.add(SpellCategory.PASSIVE_DAMAGE);
                        if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
                            categories.add(SpellCategory.AREA);
                        }
                    }
                    case SPELL_AREA_IMPACT -> categories.add(SpellCategory.PASSIVE_DAMAGE);
                    case EVASION, ROLL, EFFECT_TICK -> categories.add(SpellCategory.PASSIVE_DEFENSE);
                }
            }
        }

        if (spell.secondary_archetype != null) {
            switch (spell.secondary_archetype) {
                case ARCHERY -> {
                    categories.add(SpellCategory.RANGED_BOW);
                    if (spell.deliver != null && (spell.deliver.type == Spell.Delivery.Type.AFFECT_ARROW
                            || spell.deliver.type == Spell.Delivery.Type.STASH_EFFECT)) {
                        categories.add(SpellCategory.SUPPORT);
                    }
                }
                case MELEE -> categories.add(SpellCategory.MELEE);
                case MAGIC -> {
                    if (spell.deliver != null) {
                        switch (spell.deliver.type) {
                            case PROJECTILE, SHOOT_ARROW -> categories.add(SpellCategory.PROJECTILE);
                            case METEOR, CLOUD -> categories.add(SpellCategory.AREA);
                            default -> {}
                        }
                    }
                }
                case ANY -> {

                    if (categories.isEmpty()) {
                        categories.add(SpellCategory.SUPPORT);
                    }
                }
            }
        }

        if (isMeleeArchetype(spell)) {
            categories.add(SpellCategory.MELEE);
        }
        if (isArcheryArchetype(spell)) {
            categories.add(SpellCategory.RANGED_BOW);
        }
        if (isMagicArchetype(spell) && spell.deliver != null && spell.deliver.type != null) {
            switch (spell.deliver.type) {
                case PROJECTILE, SHOOT_ARROW -> categories.add(SpellCategory.PROJECTILE);
                case METEOR, CLOUD -> categories.add(SpellCategory.AREA);
                case MELEE -> categories.add(SpellCategory.MELEE);
                case DIRECT, CUSTOM, STASH_EFFECT -> categories.add(SpellCategory.SUPPORT);
                default -> {}
            }
        }

        if (categories.isEmpty() && spell.type == Spell.Type.ACTIVE && isMagicArchetype(spell)) {
            categories.add(SpellCategory.SUPPORT);
        }

        return categories;
    }

    private static boolean appliesStatusEffectToCaster(Spell spell) {
        if (spell.impacts == null) return false;

        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null) continue;

            if (impact.action.type == Spell.Impact.Action.Type.STATUS_EFFECT) {
                if (impact.action.apply_to_caster) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasHealingImpact(Spell spell) {
        if (spell.impacts == null) return false;

        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.HEAL) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSupportImpact(Spell spell) {
        if (spell.impacts == null) return false;

        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null) continue;

            if (impact.action.type == Spell.Impact.Action.Type.STATUS_EFFECT) {
                if (impact.action.status_effect != null) {
                    String effectId = impact.action.status_effect.effect_id;
                    if (effectId != null && (effectId.contains("barrier") || effectId.contains("shield") || effectId.contains("pact"))) {
                        return true;
                    }
                }
            }

            if (impact.action.type == Spell.Impact.Action.Type.SPAWN) {
                if (impact.action.spawns != null) {
                    for (Spell.Impact.Action.Spawn spawn : impact.action.spawns) {
                        if (spawn.entity_type_id != null && spawn.entity_type_id.contains("barrier")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasSummonImpact(Spell spell) {
        if (spell.impacts == null) {
            return false;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.SPAWN) {
                return true;
            }
        }
        return false;
    }

    public void debugPrintSpells() {
        GuardDebugManager.broadcast(guard, "=== Spell Manager Debug ===", Formatting.AQUA);

        ItemStack mainhand = guard.getMainHandStack();
        ItemStack spellSlot = guard.getSpellSlotStack();

        GuardDebugManager.broadcast(guard, "Mainhand: " + (mainhand.isEmpty() ? "Empty" : mainhand.getItem().getName().getString()), Formatting.WHITE);
        GuardDebugManager.broadcast(guard, "Spell Slot: " + (spellSlot.isEmpty() ? "Empty" : spellSlot.getItem().getName().getString()), Formatting.WHITE);

        GuardDebugManager.broadcast(guard, "--- Active Spells (" + activeSpells.size() + ") ---", Formatting.YELLOW);
        for (CategorizedSpell spell : activeSpells) {
            String archetype = spell.entry().value().school != null
                    ? spell.entry().value().school.archetype.name()
                    : "UNKNOWN";
            GuardDebugManager.broadcast(guard,
                    spell.spellId() + " | Category: " + spell.category() + " | Archetype: " + archetype + " | Source: " + spell.source() + " | Tier: " + spell.entry().value().tier,
                    Formatting.WHITE
            );
        }

        GuardDebugManager.broadcast(guard, "--- Passive Spells (" + passiveSpells.size() + ") ---", Formatting.LIGHT_PURPLE);
        for (CategorizedSpell spell : passiveSpells) {
            String archetype = spell.entry().value().school != null
                    ? spell.entry().value().school.archetype.name()
                    : "UNKNOWN";
            GuardDebugManager.broadcast(guard,
                    spell.spellId() + " | Category: " + spell.category() + " | Archetype: " + archetype + " | Source: " + spell.source() + " | Tier: " + spell.entry().value().tier,
                    Formatting.WHITE
            );
        }

        GuardDebugManager.broadcast(guard, "--- Modifier Spells (" + modifierSpells.size() + ") ---", Formatting.GOLD);
        for (RegistryEntry<Spell> modEntry : modifierSpells) {
            GuardDebugManager.broadcast(guard,
                    modEntry.getKey().get().getValue() + " | Modifiers: " + modEntry.value().modifiers.size(),
                    Formatting.WHITE
            );
        }

        GuardDebugManager.broadcast(guard, "=====================================", Formatting.AQUA);
    }

    public boolean hasProjectileSpells() {
        return hasSpells(SpellCategory.PROJECTILE) || hasSpells(SpellCategory.AREA);
    }

    public boolean hasArcherySpells() {
        return hasSpells(SpellCategory.RANGED_BOW);
    }

    public boolean hasMeleeSpells() {
        return hasSpells(SpellCategory.MELEE) || hasPassiveSpells(SpellCategory.PASSIVE_DAMAGE);
    }

    public boolean hasHealingSpells() {
        return hasSpells(SpellCategory.HEALING);
    }

    public boolean hasSupportSpells() {
        return hasSpells(SpellCategory.SUPPORT);
    }

    
    public boolean hasSupportBuffSpells() {
        return getBestCastableAllyBuffSong().isPresent()
                || activeSpells.stream().anyMatch(s ->
                s.category() == SpellCategory.SUPPORT && isSupportBuffSpell(s.entry().value()));
    }

    public Optional<CategorizedSpell> getBestCastableSupportBuffSpell() {
        Optional<CategorizedSpell> buff = getBestCastableAllyBuffSong();
        if (buff.isPresent()) {
            return buff;
        }
        return getBestSpell(SpellCategory.SUPPORT, s ->
                isSupportBuffSpell(s.entry().value()) && !guard.isSpellOnCooldown(s.spellId()));
    }

    private boolean isSupportBuffSpell(Spell spell) {
        if (!isAllySupportOnlySpell(spell)) {
            return false;
        }
        if (hasHealingImpact(spell) && !hasSupportImpact(spell) && !hasStatusEffectImpact(spell)
                && !appliesStatusEffectToCaster(spell)) {
            return false;
        }
        return hasSupportImpact(spell)
                || hasStatusEffectImpact(spell)
                || appliesStatusEffectToCaster(spell)
                || hasSummonImpact(spell)
                || (spell.target != null && spell.target.type == Spell.Target.Type.CASTER)
                || (spell.target != null && spell.target.type == Spell.Target.Type.AREA && !hasHealingImpact(spell));
    }

    

    public static boolean isAllySupportOnlySpell(Spell spell) {
        if (hasDamageImpact(spell) || isPureDamageSpell(spell)) {
            return false;
        }
        return hasHealingImpact(spell)
                || hasSupportImpact(spell)
                || hasStatusEffectImpact(spell)
                || appliesStatusEffectToCaster(spell)
                || hasSummonImpact(spell);
    }

    public boolean isHoldingStaff() {
        ItemStack mainhand = guard.getMainHandStack();
        if (mainhand.isEmpty()) return false;
        return mainhand.getItem() instanceof net.spell_engine.api.item.weapon.StaffItem;
    }

    public boolean shouldUseProjectileCasting() {
        if (!hasMagicCastingSpells()) {
            return false;
        }
        return mainHandSupportsMagicCasting();
    }

    public boolean mainHandSupportsMagicCasting() {
        return GuardWeaponArchetype.usesMagicCasting(guard.getMainHandStack());
    }

    
    public boolean isMagicCastingWeapon() {
        ItemStack main = guard.getMainHandStack();
        if (main.isEmpty()) {
            return false;
        }
        SpellContainer container = SpellContainerHelper.containerFromItemStack(main);
        if (container != null && container.access() == SpellContainer.ContentType.MAGIC) {
            return true;
        }
        return GuardItemTags.isMagicCastingWeapon(main);
    }

    

    public boolean shouldUseMeleeWeaponCasting() {
        if (!GuardWeaponArchetype.usesMeleeCasting(guard.getMainHandStack())) {
            return false;
        }
        return hasActiveMeleeCastSpells();
    }

    
    public boolean hasCastablePhysicalMeleeSpell() {
        if (!shouldUseMeleeWeaponCasting()) {
            return false;
        }
        return getBestSpell(SpellCategory.MELEE, s ->
                !guard.isSpellOnCooldown(s.spellId())
                        && isCastableMeleeSpell(s.entry().value())
                        && !isOnHitMeleeSpell(s.entry().value())).isPresent();
    }

    private static boolean isCastableMeleeSpell(Spell spell) {
        return isMeleeArchetype(spell) || isMeleeDeliverSpell(spell);
    }

    private boolean hasActiveMeleeCastSpells() {
        return getBestCastableMeleeSelfBuff().isPresent()
                || activeSpells.stream().anyMatch(s ->
                        s.category() == SpellCategory.MELEE
                                && isCastableMeleeSpell(s.entry().value())
                                && !isOnHitMeleeSpell(s.entry().value()));
    }

    public boolean hasMagicCastingSpells() {
        return activeSpells.stream().anyMatch(s ->
                isMagicCastingCategory(s.category()) && isMagicCastingSpell(s.entry().value()));
    }

    public static boolean isMagicArchetype(Spell spell) {
        if (spell.school == null || spell.school.archetype == null) {
            return true;
        }
        return spell.school.archetype == SpellSchool.Archetype.MAGIC;
    }

    
    public static boolean isMagicCastingSpell(Spell spell) {
        if (isMeleeArchetype(spell)) {
            return false;
        }
        if (isArcheryArchetype(spell)) {
            return false;
        }
        if (spell.secondary_archetype != null && spell.secondary_archetype == net.spell_engine.api.spell.Spell.ExtendedArchetype.ANY) {
            return true;
        }
        if (spell.school != null && spell.school.id != null) {
            String path = spell.school.id.getPath();
            if (path.contains("arcane") || path.contains("healing") || path.contains("soul")
                    || path.contains("fire") || path.contains("frost") || path.contains("lightning")
                    || path.contains("holy") || path.contains("nature") || path.contains("shadow")) {
                return true;
            }
        }
        return isMagicArchetype(spell);
    }

    public static boolean hasDamageImpact(Spell spell) {
        if (spell.impacts == null) {
            return false;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.DAMAGE) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasHostileSpellEffect(Spell spell) {
        if (hasDamageImpact(spell)) {
            return true;
        }
        if (spell.impacts == null) {
            return false;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null) {
                continue;
            }
            if (impact.action.type == Spell.Impact.Action.Type.STATUS_EFFECT && !impact.action.apply_to_caster) {
                return true;
            }
            if (impact.action.type == Spell.Impact.Action.Type.CUSTOM
                    && impact.action.custom != null
                    && impact.action.custom.intent == net.spell_engine.internals.target.SpellTarget.Intent.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMeleeDeliverSpell(Spell spell) {
        return spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.MELEE;
    }

    private static boolean hasStatusEffectImpact(Spell spell) {
        if (spell.impacts == null) {
            return false;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.STATUS_EFFECT) {
                return true;
            }
        }
        return false;
    }

    public float effectiveCastRange(RegistryEntry<Spell> entry) {
        float range = getAugmentedRange(entry);
        if (range > 0) {
            return range;
        }
        Spell spell = entry.value();
        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.MELEE
                && spell.deliver.melee != null && spell.deliver.melee.attacks != null
                && !spell.deliver.melee.attacks.isEmpty()) {
            float hitbox = spell.deliver.melee.attacks.getFirst().hitbox != null
                    ? spell.deliver.melee.attacks.getFirst().hitbox.length
                    : 0;
            if (hitbox > 0) {
                return hitbox;
            }
        }
        if (spell.target != null && spell.target.type == Spell.Target.Type.CASTER) {
            return 0.0F;
        }
        return 16.0F;
    }

    public boolean inCastRange(LivingEntity target, Spell spell) {
        if (target == null) {
            return true;
        }
        float range = spell.range;
        if (range <= 0 && (spell.target == null || spell.target.type != Spell.Target.Type.AIM)) {
            return true;
        }
        if (range <= 0) {
            range = 16.0F;
        }
        return guard.distanceTo(target) <= range + 1.5F;
    }

    private boolean inCastRange(LivingEntity target, RegistryEntry<Spell> entry) {
        if (target == null) {
            return true;
        }
        Spell spell = entry.value();
        float range = effectiveCastRange(entry);
        if (range <= 0 && (spell.target == null || spell.target.type != Spell.Target.Type.AIM)) {
            return true;
        }
        return guard.distanceTo(target) <= range + 1.5F;
    }

    public boolean hasAnyCastableCombatMagic(LivingEntity enemy) {
        return getBestCastableCombatSong(enemy).isPresent();
    }

    public Optional<CategorizedSpell> getBestCastableOffensiveSpell(SpellCategory category, LivingEntity enemy) {
        return getBestSpell(category, s -> {
            Spell spell = s.entry().value();
            return !guard.isSpellOnCooldown(s.spellId())
                    && isMagicCastingSpell(spell)
                    && hasHostileSpellEffect(spell)
                    && inCastRange(enemy, s.entry());
        });
    }

    public Optional<CategorizedSpell> getBestCastableCombatSong(LivingEntity enemy) {
        return getBestCastableOffensiveSpell(SpellCategory.PROJECTILE, enemy)
                .or(() -> getBestCastableOffensiveSpell(SpellCategory.AREA, enemy))
                .or(() -> getBestSpell(SpellCategory.SUPPORT, s -> {
                    Spell spell = s.entry().value();
                    return !guard.isSpellOnCooldown(s.spellId())
                            && isMagicCastingSpell(spell)
                            && hasHostileSpellEffect(spell)
                            && inCastRange(enemy, s.entry());
                }));
    }

    public Optional<CategorizedSpell> getBestCastableSelfSupportSpell() {
        return getBestSpell(SpellCategory.SUPPORT, s -> {
            Spell spell = s.entry().value();
            return !guard.isSpellOnCooldown(s.spellId())
                    && isMagicCastingSpell(spell)
                    && isSupportBuffSpell(spell);
        });
    }

    public Optional<CategorizedSpell> getBestCastableSpellbladeMagicSpell(LivingEntity enemy) {
        if (enemy == null || !enemy.isAlive()) {
            return Optional.empty();
        }
        return getBestCastableOffensiveSpell(SpellCategory.PROJECTILE, enemy)
                .or(() -> getBestCastableOffensiveSpell(SpellCategory.AREA, enemy))
                .or(() -> getBestSpell(SpellCategory.SUPPORT, s -> {
                    Spell spell = s.entry().value();
                    return !guard.isSpellOnCooldown(s.spellId())
                            && isMagicCastingSpell(spell)
                            && hasHostileSpellEffect(spell)
                            && inCastRange(enemy, s.entry());
                }));
    }

    public Optional<CategorizedSpell> getBestCastableSpellbladeMeleeSpell() {
        java.util.function.Predicate<CategorizedSpell> meleeReady = s ->
                !guard.isSpellOnCooldown(s.spellId())
                        && isCastableMeleeSpell(s.entry().value())
                        && !isOnHitMeleeSpell(s.entry().value());
        return getBestSpell(SpellCategory.MELEE, meleeReady)
                .or(() -> getBestSpell(SpellCategory.PROJECTILE, s ->
                        meleeReady.test(s) && isMeleeDeliverSpell(s.entry().value())))
                .or(() -> getBestSpell(SpellCategory.AREA, s ->
                        meleeReady.test(s) && isMeleeDeliverSpell(s.entry().value())));
    }

    public Optional<CategorizedSpell> getBestCastableMeleeSelfBuff() {
        return getBestSpell(SpellCategory.SUPPORT, s -> {
            if (guard.isSpellOnCooldown(s.spellId())) return false;
            Spell spell = s.entry().value();
            boolean isMeleeSelfBuff = isMeleeArchetype(spell)
                    || (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.STASH_EFFECT);
            if (!isMeleeSelfBuff) return false;
            if (hasHostileSpellEffect(spell) && !appliesStatusEffectToCaster(spell)) return false;
            return true;
        });
    }

    public Optional<CategorizedSpell> getBestCastableHybridAreaSong() {
        return getBestSpell(SpellCategory.AREA, s -> {
            Spell spell = s.entry().value();
            return !guard.isSpellOnCooldown(s.spellId())
                    && isMagicCastingSpell(spell)
                    && hasHealingImpact(spell)
                    && hasDamageImpact(spell);
        });
    }

    public Optional<CategorizedSpell> getBestCastableAllyBuffSong() {
        return getBestSpell(SpellCategory.SUPPORT, s -> {
            Spell spell = s.entry().value();
            return !guard.isSpellOnCooldown(s.spellId())
                    && isMagicCastingSpell(spell)
                    && !isChanneledSpell(spell)
                    && isAllySupportOnlySpell(spell)
                    && (hasStatusEffectImpact(spell) || appliesStatusEffectToCaster(spell) || hasSummonImpact(spell))
                    && !isPureDamageSpell(spell);
        }).or(() -> getBestSpell(SpellCategory.AREA, s -> {
            Spell spell = s.entry().value();
            return !guard.isSpellOnCooldown(s.spellId())
                    && isMagicCastingSpell(spell)
                    && !isChanneledSpell(spell)
                    && hasStatusEffectImpact(spell)
                    && !hasDamageImpact(spell);
        }));
    }

    private static boolean isChanneledSpell(Spell spell) {
        return spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0;
    }

    private static boolean isPureDamageSpell(Spell spell) {
        if (!hasDamageImpact(spell) || hasStatusEffectImpact(spell)) {
            return false;
        }
        if (spell.impacts == null) {
            return true;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action != null && impact.action.type == Spell.Impact.Action.Type.HEAL) {
                return false;
            }
        }
        return true;
    }

    public static boolean isMeleeArchetype(Spell spell) {
        if (spell.school == null) {
            return false;
        }
        if (spell.school.archetype == SpellSchool.Archetype.MELEE) {
            return true;
        }
        return spell.school.id != null && "physical_melee".equals(spell.school.id.getPath());
    }

    public static boolean isArcheryArchetype(Spell spell) {
        if (spell.school == null) {
            return false;
        }
        if (spell.school.archetype == SpellSchool.Archetype.ARCHERY) {
            return true;
        }
        if (spell.school.id != null) {
            String path = spell.school.id.getPath();
            if ("physical_ranged".equals(path) || "fire_ranged".equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMagicCastingCategory(SpellCategory category) {
        return category == SpellCategory.PROJECTILE
                || category == SpellCategory.AREA
                || category == SpellCategory.SUPPORT
                || category == SpellCategory.HEALING;
    }

    public List<CategorizedSpell> getMeleeOnHitSpells() {
        return cachedMeleeOnHitSpells;
    }

    public static boolean isOnHitMeleeSpell(Spell spell) {
        if (spell.active != null && spell.active.cast != null && spell.active.cast.duration > 0) {
            return false;
        }
        
        if (spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0) {
            return false;
        }
        
        if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
            return false;
        }
        
        if (spell.deliver != null) {
            switch (spell.deliver.type) {
                case PROJECTILE, SHOOT_ARROW, AFFECT_ARROW, METEOR, CLOUD:
                    return false;
                case STASH_EFFECT:
                    return false;
                case MELEE:
                    if (spell.deliver.melee != null && spell.deliver.melee.attacks != null
                            && !spell.deliver.melee.attacks.isEmpty()) {
                        return false;
                    }
                    break;
                default:
                    break;
            }
        }

        return true;
    }
    
    private Set<SpellCategory> filterCategoriesBySource(Spell spell, Set<SpellCategory> categories, ItemSource source) {
        if (source == ItemSource.MAINHAND) {
            categories = filterMainhandCategories(spell, categories);
        }

        if (spell.school == null) {
            return categories;
        }

        if (spell.school.archetype == SpellSchool.Archetype.ARCHERY) {
            if (source == ItemSource.MAINHAND && guard.getMainHandStack().getItem() instanceof net.spell_engine.api.item.weapon.StaffItem) {
                Set<SpellCategory> filtered = EnumSet.copyOf(categories);
                filtered.remove(SpellCategory.PROJECTILE);
                filtered.remove(SpellCategory.AREA);
                return filtered;
            }

            if (source == ItemSource.SPELL_SLOT) {
                Set<SpellCategory> filtered = EnumSet.copyOf(categories);
                filtered.remove(SpellCategory.PROJECTILE);
                filtered.remove(SpellCategory.AREA);
                return filtered;
            }
        }

        return categories;
    }

    private Set<SpellCategory> filterMainhandCategories(Spell spell, Set<SpellCategory> categories) {
        if (guard.getSpellSlotStack().isEmpty()) {
            return categories;
        }

        GuardWeaponArchetype.Role role = GuardWeaponArchetype.resolve(guard.getMainHandStack());
        if (role == GuardWeaponArchetype.Role.MAGE) {
            return categories;
        }

        Set<SpellCategory> filtered = EnumSet.copyOf(categories);
        filtered.removeIf(cat -> cat != SpellCategory.MELEE
                && cat != SpellCategory.PASSIVE_DAMAGE
                && cat != SpellCategory.PASSIVE_DEFENSE);
        return filtered;
    }

    public boolean hasMeleeSpellEffects() {
        return !getMeleeOnHitSpells().isEmpty();
    }

    public List<CategorizedSpell> getSpells(SpellCategory category) {
        return activeByCategory.getOrDefault(category, List.of());
    }

    public List<CategorizedSpell> getPassiveSpells(SpellCategory category) {
        return passiveByCategory.getOrDefault(category, List.of());
    }

    public Optional<CategorizedSpell> getBestSpell(
            SpellCategory category,
            java.util.function.Predicate<CategorizedSpell> filter
    ) {
        List<CategorizedSpell> matches = getSpells(category).stream()
                .filter(filter)
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        Map<ItemSource, List<CategorizedSpell>> bySource = new java.util.LinkedHashMap<>();
        for (CategorizedSpell s : matches) {
            bySource.computeIfAbsent(s.source(), k -> new ArrayList<>()).add(s);
        }

        List<CategorizedSpell> candidates = new ArrayList<>();
        for (List<CategorizedSpell> group : bySource.values()) {
            int maxTier = group.stream().mapToInt(s -> s.entry().value().tier).max().orElse(0);
            group.stream()
                    .filter(s -> s.entry().value().tier == maxTier)
                    .findFirst()
                    .ifPresent(candidates::add);
        }

        return Optional.of(candidates.get(guard.getRandom().nextInt(candidates.size())));
    }

    public boolean hasSpells(SpellCategory category) {
        List<CategorizedSpell> list = activeByCategory.get(category);
        return list != null && !list.isEmpty();
    }

    public boolean hasPassiveSpells(SpellCategory category) {
        List<CategorizedSpell> list = passiveByCategory.get(category);
        return list != null && !list.isEmpty();
    }

    public List<CategorizedSpell> getAllActiveSpells() {
        return new ArrayList<>(activeSpells);
    }

    public CategorizedSpell findActiveSpellByPath(String pathPart) {
        for (CategorizedSpell s : activeSpells) {
            if (s.spellId().getPath().contains(pathPart)) {
                return s;
            }
        }
        return null;
    }

    public List<CategorizedSpell> getAllPassiveSpells() {
        return new ArrayList<>(passiveSpells);
    }

    public List<RegistryEntry<Spell>> activeSpellEntries() {
        return activeSpells.stream().map(CategorizedSpell::entry).toList();
    }

    public List<RegistryEntry<Spell>> passiveSpellEntries() {
        return passiveSpells.stream().map(CategorizedSpell::entry).toList();
    }

    public void applySharedSpellCooldown(Identifier spellId, int ticks) {
        SpellRegistry.from(guard.getWorld())
                .getEntry(spellId)
                .ifPresent(entry -> guard.getCooldownManager().set(entry, ticks, true));
    }

    @org.jetbrains.annotations.Nullable
    private Spell findSpell(Identifier spellId) {
        for (CategorizedSpell categorized : activeSpells) {
            if (categorized.spellId().equals(spellId)) {
                return categorized.entry().value();
            }
        }
        for (CategorizedSpell categorized : passiveSpells) {
            if (categorized.spellId().equals(spellId)) {
                return categorized.entry().value();
            }
        }
        return null;
    }

    public List<RegistryEntry<Spell>> getAllModifierSpells() {
        return new ArrayList<>(modifierSpells);
    }

    public List<Spell.Modifier> getModifiersFor(RegistryEntry<Spell> spellEntry) {
        List<Spell.Modifier> result = new ArrayList<>();
        for (RegistryEntry<Spell> modEntry : modifierSpells) {
            for (Spell.Modifier modifier : modEntry.value().modifiers) {
                if (modifierPatternMatches(spellEntry, modifier.spell_pattern)) {
                    result.add(modifier);
                }
            }
        }
        return result;
    }

    private boolean modifierPatternMatches(RegistryEntry<Spell> spellEntry, String pattern) {
        if (pattern == null || pattern.isEmpty()) return true;
        var id = spellEntry.getKey().get().getValue().toString();
        if (pattern.startsWith("~")) {
            return id.matches(pattern.substring(1));
        }
        if (pattern.startsWith("#")) {
            var tagId = Identifier.tryParse(pattern.substring(1));
            if (tagId == null) return false;
            var tagKey = TagKey.of(SpellRegistry.KEY, tagId);
            return spellEntry.isIn(tagKey);
        }
        return id.equals(pattern);
    }

    public SpellPower.Result getAugmentedPower(RegistryEntry<Spell> spellEntry) {
        Spell spell = spellEntry.value();
        SpellPower.Result base = SpellPower.getSpellPower(spell.school, guard);

        List<Spell.Modifier> modifiers = getModifiersFor(spellEntry);
        if (modifiers.isEmpty()) return base;

        float bonusPower = 1.0f;
        float bonusCritChance = 0.0f;
        float bonusCritDamage = 0.0f;
        for (Spell.Modifier modifier : modifiers) {
            if (modifier.power_modifier != null) {
                bonusPower += modifier.power_modifier.power_multiplier;
                bonusCritChance += modifier.power_modifier.critical_chance_bonus;
                bonusCritDamage += modifier.power_modifier.critical_damage_bonus;
            }
        }

        return new SpellPower.Result(
                base.school(),
                base.baseValue() * bonusPower,
                base.criticalChance() + bonusCritChance,
                base.criticalDamage() + bonusCritDamage
        );
    }

    public List<Spell.Impact> getAugmentedImpacts(RegistryEntry<Spell> spellEntry) {
        Spell spell = spellEntry.value();
        List<Spell.Modifier> modifiers = getModifiersFor(spellEntry);
        if (modifiers.isEmpty()) return spell.impacts;

        List<Spell.Impact> mutableImpacts = new ArrayList<>(spell.impacts);
        for (Spell.Modifier modifier : modifiers) {
            if (modifier.mutate_impacts != null) {
                switch (modifier.mutate_impacts) {
                    case PREPEND -> mutableImpacts.addAll(0, modifier.impacts);
                    case APPEND -> mutableImpacts.addAll(modifier.impacts);
                }
            }
        }
        return mutableImpacts;
    }

    public int getAugmentedCooldownTicks(RegistryEntry<Spell> spellEntry, int baseTicks) {
        List<Spell.Modifier> modifiers = getModifiersFor(spellEntry);
        if (modifiers.isEmpty()) return baseTicks;

        float deductSeconds = 0.0f;
        for (Spell.Modifier modifier : modifiers) {
            deductSeconds += modifier.cooldown_duration_deduct;
        }
        int deductTicks = (int)(deductSeconds * 20.0f);
        return Math.max(1, baseTicks - deductTicks);
    }

    public float getAugmentedRange(RegistryEntry<Spell> spellEntry) {
        Spell spell = spellEntry.value();
        float range = spell.range;
        List<Spell.Modifier> modifiers = getModifiersFor(spellEntry);
        for (Spell.Modifier modifier : modifiers) {
            range += modifier.range_add;
        }
        return range;
    }
}