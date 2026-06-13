package dev.sterner.guardvillagers.common.entity;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

public final class GuardItemTags {

    
    public static final TagKey<Item> BARDS_LUTES = tag("bards_rpg", "lutes");
    public static final TagKey<Item> BARDS_LYRES = tag("bards_rpg", "lyres");
    public static final TagKey<Item> BARDS_HARP_CROSSBOWS = tag("bards_rpg", "harp_crossbows");

    
    public static final TagKey<Item> RPG_MAGIC_DAMAGE_WEAPON = tag("rpg_series", "archetype/magic_damage_weapon");
    public static final TagKey<Item> RPG_HEALING_WEAPON = tag("rpg_series", "archetype/healing_weapon");
    public static final TagKey<Item> RPG_MELEE_DAMAGE_WEAPON = tag("rpg_series", "archetype/melee_damage_weapon");
    public static final TagKey<Item> RPG_RANGED_DAMAGE_WEAPON = tag("rpg_series", "archetype/ranged_damage_weapon");
    public static final TagKey<Item> RPG_DEFENSE_WEAPON = tag("rpg_series", "archetype/defense_weapon");

    
    public static final TagKey<Item> RPG_DAMAGE_STAFF = tag("rpg_series", "weapon_type/damage_staff");
    public static final TagKey<Item> RPG_DAMAGE_WAND = tag("rpg_series", "weapon_type/damage_wand");
    public static final TagKey<Item> RPG_HEALING_STAFF = tag("rpg_series", "weapon_type/healing_staff");
    public static final TagKey<Item> RPG_HEALING_WAND = tag("rpg_series", "weapon_type/healing_wand");
    public static final TagKey<Item> RPG_SPELL_BLADE = tag("rpg_series", "weapon_type/spell_blade");
    public static final TagKey<Item> RPG_SPELL_SCYTHE = tag("rpg_series", "weapon_type/spell_scythe");
    public static final TagKey<Item> RPG_LONG_BOW = tag("rpg_series", "weapon_type/long_bow");
    public static final TagKey<Item> RPG_SHORT_BOW = tag("rpg_series", "weapon_type/short_bow");
    public static final TagKey<Item> RPG_HEAVY_CROSSBOW = tag("rpg_series", "weapon_type/heavy_crossbow");
    public static final TagKey<Item> RPG_RAPID_CROSSBOW = tag("rpg_series", "weapon_type/rapid_crossbow");

    
    public static final TagKey<Item> WIZARDS_WANDS = tag("wizards", "wands");
    public static final TagKey<Item> WIZARDS_STAVES = tag("wizards", "staves");
    public static final TagKey<Item> ELEMENTAL_WANDS = tag("elemental_wizards_rpg", "elemental_wands");
    public static final TagKey<Item> ELEMENTAL_STAVES = tag("elemental_wizards_rpg", "elemental_staves");
    public static final TagKey<Item> PALADINS_STAVES = tag("paladins", "staves");
    public static final TagKey<Item> FORCEMASTER_FIST_WEAPONS = tag("forcemaster_rpg", "fist_weapons");

    private GuardItemTags() {}

    private static TagKey<Item> tag(String namespace, String path) {
        return TagKey.of(RegistryKeys.ITEM, Identifier.of(namespace, path));
    }

    public static boolean isBardInstrument(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.isIn(BARDS_LUTES)
                || stack.isIn(BARDS_LYRES)
                || stack.isIn(BARDS_HARP_CROSSBOWS)
                || nameContains(stack, "lute", "lyre", "harp", "flute", "fiddle", "drum");
    }

    public static boolean isHealingWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.isIn(RPG_HEALING_WEAPON)
                || stack.isIn(RPG_HEALING_STAFF)
                || stack.isIn(RPG_HEALING_WAND)
                || stack.isIn(PALADINS_STAVES)
                || nameContains(stack, "holy_wand", "holy_staff", "priest", "healing_staff", "healing_wand");
    }

    public static boolean isSpellbladeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.isIn(RPG_SPELL_BLADE) || stack.isIn(RPG_SPELL_SCYTHE) || stack.isIn(FORCEMASTER_FIST_WEAPONS)) {
            return true;
        }
        return nameContains(stack, "spell_blade", "spellblade", "frost_blade", "fire_blade", "arcane_blade",
                "frost_claymore", "fire_claymore", "arcane_claymore", "runeblade", "runesword", "cudgel",
                "knuckle", "knuckles");
    }

    public static boolean isMageArchetypeWeapon(ItemStack stack) {
        if (stack.isEmpty() || isSpellbladeWeapon(stack) || isMeleeDamageWeapon(stack) || isRangedDamageWeapon(stack)) {
            return false;
        }
        if (stack.getItem() instanceof net.spell_engine.api.item.weapon.StaffItem) {
            return true;
        }
        if (isBardInstrument(stack) || isHealingWeapon(stack)) {
            return true;
        }
        if (stack.isIn(RPG_MAGIC_DAMAGE_WEAPON)
                || stack.isIn(RPG_DAMAGE_STAFF)
                || stack.isIn(RPG_DAMAGE_WAND)
                || stack.isIn(WIZARDS_WANDS)
                || stack.isIn(WIZARDS_STAVES)
                || stack.isIn(ELEMENTAL_WANDS)
                || stack.isIn(ELEMENTAL_STAVES)) {
            return true;
        }
        return nameContains(stack, "wand", "staff", "focus", "scepter", "tome", "orb");
    }

    public static boolean isMagicCastingWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isSpellbladeWeapon(stack) || isMageArchetypeWeapon(stack)) {
            return true;
        }
        if (stack.isIn(RPG_SPELL_BLADE) || stack.isIn(RPG_SPELL_SCYTHE)) {
            return true;
        }
        return nameContains(stack, "instrument");
    }

    public static boolean isMeleeDamageWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.isIn(RPG_MELEE_DAMAGE_WEAPON)
                || nameContains(stack, "claymore", "glaive", "mace", "hammer", "dagger", "sickle", "spear",
                "double_axe", "sword", "longsword", "rapier", "katana", "scimitar", "cutlass");
    }

    public static boolean isCrossbowLikeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof net.minecraft.item.CrossbowItem) {
            return true;
        }
        return stack.isIn(BARDS_HARP_CROSSBOWS)
                || stack.isIn(RPG_HEAVY_CROSSBOW)
                || stack.isIn(RPG_RAPID_CROSSBOW)
                || (stack.isIn(RPG_RANGED_DAMAGE_WEAPON) && nameContains(stack, "crossbow"));
    }

    public static boolean isBowLikeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isCrossbowLikeWeapon(stack)) {
            return false;
        }
        return stack.getItem() instanceof net.minecraft.item.BowItem
                || stack.isIn(RPG_LONG_BOW)
                || stack.isIn(RPG_SHORT_BOW)
                || stack.isIn(RPG_RANGED_DAMAGE_WEAPON);
    }

    
    public static boolean isRangedDamageWeapon(ItemStack stack) {
        return isBowLikeWeapon(stack) || isCrossbowLikeWeapon(stack);
    }

    public static boolean isDefenseWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.isIn(RPG_DEFENSE_WEAPON);
    }

    
    @org.jetbrains.annotations.Nullable
    public static String bardInstrumentType(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.isIn(BARDS_LYRES)) {
            return "lyre";
        }
        if (stack.isIn(BARDS_LUTES)) {
            return "lute";
        }
        if (stack.isIn(BARDS_HARP_CROSSBOWS)) {
            return "harp";
        }
        return nameContains(stack, "lyre") ? "lyre"
                : nameContains(stack, "lute") ? "lute"
                : nameContains(stack, "harp") ? "harp"
                : null;
    }

    

    @org.jetbrains.annotations.Nullable
    public static String scrollPoolSuffix(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (isSpellbladeWeapon(stack)) {
            return spellbladeScrollPoolSuffix(Registries.ITEM.getId(stack.getItem()).getPath());
        }
        if (isHealingWeapon(stack)) {
            return "priest";
        }
        if (isBardInstrument(stack)) {
            return "bard";
        }
        if (isRangedDamageWeapon(stack)) {
            String path = Registries.ITEM.getId(stack.getItem()).getPath();
            if (path.contains("deadeye")) {
                return "deadeye";
            }
            if (path.contains("tundra")) {
                return "tundra_hunter";
            }
            if (path.contains("war_archer")) {
                return "war_archer";
            }
            return "archer";
        }
        if (isMeleeDamageWeapon(stack)) {
            if (nameContains(stack, "dagger", "sickle")) {
                return "rogue";
            }
            if (nameContains(stack, "glaive", "double_axe")) {
                return "warrior";
            }
            if (nameContains(stack, "great_hammer", "paladin")) {
                return "paladin";
            }
            return "warrior";
        }
        if (stack.isIn(WIZARDS_WANDS) || stack.isIn(WIZARDS_STAVES)
                || stack.isIn(ELEMENTAL_WANDS) || stack.isIn(ELEMENTAL_STAVES)
                || isMagicCastingWeapon(stack)) {
            return scrollPoolSuffixFromPath(Registries.ITEM.getId(stack.getItem()).getPath());
        }
        return scrollPoolSuffixFromPath(Registries.ITEM.getId(stack.getItem()).getPath());
    }

    @org.jetbrains.annotations.Nullable
    private static String scrollPoolSuffixFromPath(String path) {
        if (path.contains("holy_wand") || path.contains("priest")) {
            return "priest";
        }
        if (path.contains("paladin") || path.contains("great_hammer")) {
            return "paladin";
        }
        if (path.contains("lute") || path.contains("lyre") || path.contains("harp") || path.contains("bard")) {
            return "bard";
        }
        if (path.contains("rogue") || path.contains("dagger") || path.contains("sickle")) {
            return "rogue";
        }
        if (path.contains("warrior") || path.contains("glaive") || path.contains("double_axe")) {
            return "warrior";
        }
        if (path.contains("wand_aqua") || path.contains("aqua")) {
            return "aqua";
        }
        if (path.contains("wand_terra") || path.contains("terra")) {
            return "terra";
        }
        if (path.contains("wand_air") || path.contains("wind")) {
            return "wind";
        }
        if (path.contains("wand_fire") || path.contains("fire_staff") || path.contains("staff_fire")) {
            return "fire";
        }
        if (path.contains("wand_frost") || path.contains("frost_staff") || path.contains("staff_frost")) {
            return "frost";
        }
        if (path.contains("wand_arcane") || path.contains("arcane_staff") || path.contains("staff_arcane")) {
            return "arcane";
        }
        if (path.contains("berserker")) {
            return "berserker";
        }
        if (path.contains("druid") || path.contains("nature")) {
            return "nature";
        }
        if (path.contains("shepherd") || path.contains("soulwarden")) {
            return "shepherd";
        }
        if (path.contains("archer") || (path.contains("bow") && !path.contains("crossbow"))) {
            return "archer";
        }
        if (path.contains("deadeye")) {
            return "deadeye";
        }
        if (path.contains("tundra")) {
            return "tundra_hunter";
        }
        if (path.contains("war_archer")) {
            return "war_archer";
        }
        if (path.contains("forcemaster")) {
            return "forcemaster";
        }
        if (path.contains("witcher") || path.contains("sign")) {
            return "signs";
        }
        if (path.contains("fencing") || path.contains("rapier")) {
            return "fencing";
        }
        if (path.contains("brimstone") || path.contains("battlemage")) {
            return "arcana";
        }
        return null;
    }

    @org.jetbrains.annotations.Nullable
    private static String spellbladeScrollPoolSuffix(String path) {
        if (path.contains("frost") || path.contains("ice")) {
            return "frost_battlemage";
        }
        if (path.contains("fire") || path.contains("flame")) {
            return "fire_battlemage";
        }
        if (path.contains("arcane") || path.contains("rune")) {
            return "arcane_battlemage";
        }
        if (path.contains("lightning") || path.contains("thunder")) {
            return "lightning_battlemage";
        }
        if (path.contains("phoenix")) {
            return "phoenix";
        }
        if (path.contains("death") || path.contains("chill")) {
            return "deathchill";
        }
        if (path.contains("echo")) {
            return "runic_echoes";
        }
        return null;
    }

    private static boolean nameContains(ItemStack stack, String... tokens) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        for (String token : tokens) {
            if (path.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
