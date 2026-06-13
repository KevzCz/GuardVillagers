package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.ai.GuardWeaponArchetype;
import dev.sterner.guardvillagers.common.special.GuardEffectiveConfig;
import dev.sterner.guardvillagers.common.ai.GuardWeaponArchetype.Role;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.spell_engine.api.item.weapon.StaffItem;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.container.SpellContainer;
import net.spell_engine.api.spell.container.SpellContainerHelper;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.item.ScrollItem;
import net.spell_power.api.SpellSchool;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class GuardSpellScrollRoller {

    private static final String SCROLL_POOL_PREFIX = "spell_scroll/";

    private static final List<Identifier> SPELLBLADE_SCROLL_POOLS = List.of(
            pool("spellbladenext", "fire_battlemage"),
            pool("spellbladenext", "frost_battlemage"),
            pool("spellbladenext", "arcane_battlemage"),
            pool("spellbladenext", "lightning_battlemage"),
            pool("spellbladenext", "phoenix"),
            pool("spellbladenext", "deathchill"),
            pool("spellbladenext", "runic_echoes")
    );

    private static final Map<String, List<Identifier>> SCHOOL_SCROLL_POOLS = Map.ofEntries(
            Map.entry("fire", List.of(
                    pool("wizards", "fire"),
                    pool("spellbladenext", "fire_battlemage"),
                    pool("spellbladenext", "phoenix")
            )),
            Map.entry("frost", List.of(
                    pool("wizards", "frost"),
                    pool("spellbladenext", "frost_battlemage"),
                    pool("spellbladenext", "deathchill")
            )),
            Map.entry("arcane", List.of(
                    pool("wizards", "arcane"),
                    pool("spellbladenext", "arcane_battlemage"),
                    pool("spellbladenext", "runic_echoes"),
                    pool("brimstone-battlemages", "arcana")
            )),
            Map.entry("lightning", List.of(pool("spellbladenext", "lightning_battlemage"))),
            Map.entry("healing", List.of(pool("paladins", "priest"), pool("paladins", "paladin"))),
            Map.entry("holy", List.of(pool("paladins", "priest"), pool("paladins", "paladin"))),
            Map.entry("soul", List.of(pool("soulwarden", "shepherd"))),
            Map.entry("nature", List.of(pool("druids", "nature"))),
            Map.entry("aqua", List.of(pool("elemental_wizards_rpg", "aqua"))),
            Map.entry("terra", List.of(pool("elemental_wizards_rpg", "terra"))),
            Map.entry("wind", List.of(pool("elemental_wizards_rpg", "wind"))),
            Map.entry("archery", List.of(
                    pool("archers", "archer"),
                    pool("archers_expansion", "deadeye"),
                    pool("archers_expansion", "tundra_hunter"),
                    pool("archers_expansion", "war_archer")
            ))
    );

    private GuardSpellScrollRoller() {}

    @Nullable
    public static ItemStack tryRoll(GuardEntity guard, ServerWorld world) {
        if (guard.getRandom().nextFloat() >= GuardEffectiveConfig.spellSlotScrollChance(guard)) {
            return null;
        }

        ItemStack mainHand = guard.getMainHandStack();
        if (mainHand.isEmpty()) {
            return null;
        }

        WeaponSpellProfile profile = resolveWeaponProfile(guard, mainHand, world);
        if (profile.schools.isEmpty() && profile.archetype == null && profile.scrollPools.isEmpty()) {
            return null;
        }

        Set<Identifier> knownSpells = collectKnownSpellIds(guard);
        List<ScrollCandidate> candidates = collectScrollCandidates(world, profile, knownSpells);
        if (candidates.isEmpty()) {
            return null;
        }

        ScrollCandidate chosen = pickWeighted(guard, candidates, profile.preferredScrollPools, guard.getRandom());
        Item scrollItem = Registries.ITEM.get(ScrollItem.ID);
        if (scrollItem == null) {
            return null;
        }

        ItemStack stack = new ItemStack(scrollItem);
        ScrollItem.applySpell(stack, chosen.entry(), chosen.pool());
        return stack;
    }

    private static Set<Identifier> collectKnownSpellIds(GuardEntity guard) {
        guard.getSpellManager().refresh();
        Set<Identifier> known = new HashSet<>();
        for (GuardSpellManager.CategorizedSpell spell : guard.getSpellManager().getAllActiveSpells()) {
            known.add(spell.spellId());
        }
        for (GuardSpellManager.CategorizedSpell spell : guard.getSpellManager().getAllPassiveSpells()) {
            known.add(spell.spellId());
        }
        return known;
    }

    private static List<ScrollCandidate> collectScrollCandidates(
            ServerWorld world,
            WeaponSpellProfile profile,
            Set<Identifier> knownSpells
    ) {
        List<ScrollCandidate> candidates = new ArrayList<>();
        Set<Identifier> seenSpells = new HashSet<>();

        for (Identifier poolId : profile.scrollPools) {
            collectFromPool(world, poolId, profile, knownSpells, candidates, seenSpells, false);
        }

        collectFromScrollTags(world, profile, knownSpells, candidates, seenSpells, true);

        return candidates;
    }

    private static void collectFromPool(
            ServerWorld world,
            Identifier poolId,
            WeaponSpellProfile profile,
            Set<Identifier> knownSpells,
            List<ScrollCandidate> candidates,
            Set<Identifier> seenSpells,
            boolean requireSchoolMatch
    ) {
        for (Identifier resolved : resolvePoolIds(poolId)) {
            TagKey<Spell> poolTag = TagKey.of(SpellRegistry.KEY, resolved);
            for (RegistryEntry<Spell> entry : SpellRegistry.entries(world, resolved)) {
                if (!trackSpell(entry, seenSpells) || isAlreadyKnown(entry, knownSpells)) {
                    continue;
                }
                if (!acceptsSpell(profile.guard, entry.value(), profile, requireSchoolMatch)) {
                    continue;
                }
                if (ScrollItem.resolveSpellPool(world, entry) == null) {
                    continue;
                }
                candidates.add(new ScrollCandidate(entry, poolTag));
            }
        }
    }

    private static void collectFromScrollTags(
            ServerWorld world,
            WeaponSpellProfile profile,
            Set<Identifier> knownSpells,
            List<ScrollCandidate> candidates,
            Set<Identifier> seenSpells,
            boolean requireSchoolMatch
    ) {
        Registry<Spell> registry = SpellRegistry.from(world);
        for (TagKey<Spell> poolTag : registry.streamTags().toList()) {
            if (!isScrollPoolTag(poolTag.id())) {
                continue;
            }
            RegistryEntryList<Spell> poolEntries = registry.getEntryList(poolTag).orElse(null);
            if (poolEntries == null) {
                continue;
            }
            for (RegistryEntry<Spell> entry : poolEntries) {
                if (!trackSpell(entry, seenSpells) || isAlreadyKnown(entry, knownSpells)) {
                    continue;
                }
                if (!acceptsSpell(profile.guard, entry.value(), profile, requireSchoolMatch)) {
                    continue;
                }
                if (ScrollItem.resolveSpellPool(world, entry) == null) {
                    continue;
                }
                candidates.add(new ScrollCandidate(entry, poolTag));
            }
        }
    }

    private static boolean isScrollPoolTag(Identifier poolId) {
        return poolId.getPath().startsWith(SCROLL_POOL_PREFIX);
    }

    private static List<Identifier> resolvePoolIds(Identifier poolId) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        ids.add(poolId);
        if (!poolId.getPath().contains("/")) {
            ids.add(Identifier.of(poolId.getNamespace(), SCROLL_POOL_PREFIX + poolId.getPath()));
        } else if (poolId.getPath().startsWith(SCROLL_POOL_PREFIX)) {
            ids.add(Identifier.of(poolId.getNamespace(), poolId.getPath().substring(SCROLL_POOL_PREFIX.length())));
        }
        return List.copyOf(ids);
    }

    private static boolean isAlreadyKnown(RegistryEntry<Spell> entry, Set<Identifier> knownSpells) {
        return entry.getKey().map(key -> knownSpells.contains(key.getValue())).orElse(false);
    }

    private static boolean trackSpell(RegistryEntry<Spell> entry, Set<Identifier> seenSpells) {
        return entry.getKey().map(key -> seenSpells.add(key.getValue())).orElse(false);
    }

    private static boolean acceptsSpell(@Nullable GuardEntity guard, Spell spell, WeaponSpellProfile profile, boolean requireSchoolMatch) {
        if (spell.type != Spell.Type.ACTIVE) {
            return false;
        }
        if (GuardEffectiveConfig.spellSlotScrollMaxTier(guard) > 0
                && spell.tier > GuardEffectiveConfig.spellSlotScrollMaxTier(guard)) {
            return false;
        }
        if (!requireSchoolMatch) {
            return true;
        }
        return matchesProfile(spell.school, profile);
    }

    private static ScrollCandidate pickWeighted(
            GuardEntity guard,
            List<ScrollCandidate> candidates,
            Set<Identifier> preferredPools,
            net.minecraft.util.math.random.Random random
    ) {
        int maxTier = GuardEffectiveConfig.spellSlotScrollMaxTier(guard);
        int tierCap = maxTier > 0 ? maxTier : 5;

        int totalWeight = 0;
        int[] weights = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ScrollCandidate candidate = candidates.get(i);
            int tier = Math.max(1, candidate.entry().value().tier);
            int weight = Math.max(1, tierCap - tier + 2);
            if (preferredPools.contains(candidate.pool().id())) {
                weight *= 3;
            }
            weights[i] = weight;
            totalWeight += weight;
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static boolean matchesProfile(@Nullable SpellSchool school, WeaponSpellProfile profile) {
        if (school == null) {
            return false;
        }

        if (!profile.schools.isEmpty()) {
            for (SpellSchool weaponSchool : profile.schools) {
                if (sameSchool(school, weaponSchool)) {
                    return true;
                }
            }
            return false;
        }

        return switch (profile.role) {
            case SPELLBLADE -> school.archetype == SpellSchool.Archetype.MAGIC
                    || school.archetype == SpellSchool.Archetype.MELEE;
            case MAGE -> school.archetype == SpellSchool.Archetype.MAGIC;
            case MELEE -> school.archetype == SpellSchool.Archetype.MELEE;
            case RANGED -> school.archetype == SpellSchool.Archetype.ARCHERY;
            case UNSPECIFIED -> profile.archetype == null || school.archetype == profile.archetype;
        };
    }

    private static boolean sameSchool(SpellSchool a, SpellSchool b) {
        if (a.id != null && b.id != null) {
            return a.id.equals(b.id);
        }
        return a == b;
    }

    private static WeaponSpellProfile resolveWeaponProfile(GuardEntity guard, ItemStack mainHand, ServerWorld world) {
        Set<SpellSchool> schools = new LinkedHashSet<>();
        Set<Identifier> scrollPools = new LinkedHashSet<>();
        Set<Identifier> preferredScrollPools = new LinkedHashSet<>();
        SpellSchool.Archetype archetype = null;
        Role role = GuardWeaponArchetype.resolve(mainHand);

        Identifier itemId = Registries.ITEM.getId(mainHand.getItem());
        SpellContainer container = SpellContainerHelper.containerFromItemStack(mainHand);
        if (container != null) {
            collectSchoolsFromSpellIds(container.spell_ids(), world, schools);
            collectScrollPoolsFromBoundSpells(container.spell_ids(), world, scrollPools);

            if (!container.pool().isEmpty()) {
                Identifier poolId = Identifier.tryParse(container.pool());
                if (poolId != null) {
                    scrollPools.add(poolId);
                    collectSchoolsFromPool(poolId, world, schools);
                }
            }

            archetype = archetypeFromContainer(container);
        }

        inferScrollPoolsFromItem(mainHand, schools, scrollPools);
        preferredScrollPools.addAll(scrollPools);
        expandScrollPools(mainHand, role, schools, scrollPools);

        if (schools.isEmpty() && archetype == null) {
            archetype = archetypeFromStack(mainHand);
        }

        return new WeaponSpellProfile(guard, schools, archetype, role, scrollPools, preferredScrollPools);
    }

    private static void expandScrollPools(
            ItemStack mainHand,
            Role role,
            Set<SpellSchool> schools,
            Set<Identifier> scrollPools
    ) {
        for (SpellSchool school : schools) {
            if (school.id == null) {
                continue;
            }
            addSchoolScrollPools(scrollPools, school.id.getPath());
        }

        if (role == Role.SPELLBLADE) {
            scrollPools.addAll(SPELLBLADE_SCROLL_POOLS);
            scrollPools.add(pool("rogues", "warrior"));
            scrollPools.add(pool("witcher_rpg", "fencing"));
        } else if (role == Role.MELEE) {
            scrollPools.add(pool("rogues", "rogue"));
            scrollPools.add(pool("rogues", "warrior"));
            scrollPools.add(pool("paladins", "paladin"));
            scrollPools.add(pool("berserker_rpg", "berserker"));
            scrollPools.add(pool("forcemaster_rpg", "forcemaster"));
            scrollPools.add(pool("witcher_rpg", "fencing"));
        } else if (role == Role.RANGED) {
            scrollPools.add(pool("archers", "archer"));
            scrollPools.add(pool("archers_expansion", "deadeye"));
            scrollPools.add(pool("archers_expansion", "tundra_hunter"));
            scrollPools.add(pool("archers_expansion", "war_archer"));
        } else if (role == Role.MAGE) {
            scrollPools.add(pool("bards_rpg", "bard"));
            scrollPools.add(pool("druids", "nature"));
            scrollPools.add(pool("brimstone-battlemages", "arcana"));
            scrollPools.add(pool("soulwarden", "shepherd"));
            scrollPools.add(pool("witcher_rpg", "signs"));
        }
    }

    private static void addSchoolScrollPools(Set<Identifier> scrollPools, String schoolPath) {
        List<Identifier> aliases = SCHOOL_SCROLL_POOLS.get(schoolPath);
        if (aliases != null) {
            scrollPools.addAll(aliases);
        }
    }

    private static void collectScrollPoolsFromBoundSpells(
            List<String> spellIds,
            ServerWorld world,
            Set<Identifier> scrollPools
    ) {
        var registry = SpellRegistry.from(world);
        for (String spellIdString : spellIds) {
            Identifier spellId = Identifier.tryParse(spellIdString);
            if (spellId == null) {
                continue;
            }
            registry.getEntry(spellId).ifPresent(entry -> {
                TagKey<Spell> pool = ScrollItem.resolveSpellPool(world, entry);
                if (pool != null && isScrollPoolTag(pool.id())) {
                    scrollPools.add(pool.id());
                }
            });
        }
    }

    private static void inferScrollPoolsFromItem(
            ItemStack mainHand,
            Set<SpellSchool> schools,
            Set<Identifier> scrollPools
    ) {
        Identifier itemId = Registries.ITEM.getId(mainHand.getItem());
        String namespace = itemId.getNamespace();

        addScrollPool(scrollPools, namespace, GuardItemTags.scrollPoolSuffix(mainHand));

        for (SpellSchool school : schools) {
            if (school.id == null) {
                continue;
            }
            addSchoolScrollPools(scrollPools, school.id.getPath());
            addScrollPool(scrollPools, namespace, school.id.getPath());
        }
    }

    private static void addScrollPool(Set<Identifier> scrollPools, String namespace, @Nullable String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return;
        }
        scrollPools.add(pool(namespace, suffix));
    }

    private static Identifier pool(String namespace, String suffix) {
        return Identifier.of(namespace, SCROLL_POOL_PREFIX + suffix);
    }

    private static void collectSchoolsFromSpellIds(List<String> spellIds, ServerWorld world, Set<SpellSchool> schools) {
        var registry = SpellRegistry.from(world);
        for (String spellIdString : spellIds) {
            Identifier spellId = Identifier.tryParse(spellIdString);
            if (spellId == null) {
                continue;
            }
            registry.getEntry(spellId).ifPresent(entry -> {
                if (entry.value().school != null) {
                    schools.add(entry.value().school);
                }
            });
        }
    }

    private static void collectSchoolsFromPool(Identifier poolId, ServerWorld world, Set<SpellSchool> schools) {
        for (Identifier resolved : resolvePoolIds(poolId)) {
            for (RegistryEntry<Spell> entry : SpellRegistry.entries(world, resolved)) {
                if (entry.value().school != null) {
                    schools.add(entry.value().school);
                }
            }
        }
    }

    @Nullable
    private static SpellSchool.Archetype archetypeFromContainer(SpellContainer container) {
        return switch (container.access()) {
            case ARCHERY -> SpellSchool.Archetype.ARCHERY;
            case MAGIC -> SpellSchool.Archetype.MAGIC;
            default -> null;
        };
    }

    @Nullable
    private static SpellSchool.Archetype archetypeFromStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof StaffItem || GuardItemTags.isMagicCastingWeapon(stack)) {
            return SpellSchool.Archetype.MAGIC;
        }
        if (GuardItemTags.isRangedDamageWeapon(stack)) {
            return SpellSchool.Archetype.ARCHERY;
        }
        if (GuardItemTags.isMeleeDamageWeapon(stack)) {
            return SpellSchool.Archetype.MELEE;
        }
        if (item instanceof BowItem || item instanceof CrossbowItem || item instanceof RangedWeaponItem) {
            return SpellSchool.Archetype.ARCHERY;
        }
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem) {
            return SpellSchool.Archetype.MELEE;
        }
        return null;
    }

    private record ScrollCandidate(RegistryEntry<Spell> entry, TagKey<Spell> pool) {}

    private record WeaponSpellProfile(
            GuardEntity guard,
            Set<SpellSchool> schools,
            @Nullable SpellSchool.Archetype archetype,
            Role role,
            Set<Identifier> scrollPools,
            Set<Identifier> preferredScrollPools
    ) {}
}
