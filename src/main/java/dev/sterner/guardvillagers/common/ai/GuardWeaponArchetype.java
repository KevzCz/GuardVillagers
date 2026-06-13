package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager.SpellCategory;
import net.minecraft.item.ItemStack;

import java.util.EnumSet;
import java.util.Set;

public final class GuardWeaponArchetype {

    public enum Role {
        MELEE,
        MAGE,
        SPELLBLADE,
        RANGED,
        UNSPECIFIED
    }

    private static final Set<SpellCategory> MAGE_CAST_CATEGORIES = EnumSet.of(
            SpellCategory.PROJECTILE,
            SpellCategory.AREA,
            SpellCategory.SUPPORT,
            SpellCategory.HEALING,
            SpellCategory.RANGED_BOW
    );

    private static final Set<SpellCategory> MELEE_CAST_CATEGORIES = EnumSet.of(
            SpellCategory.MELEE
    );

    private GuardWeaponArchetype() {}

    public static Role resolve(ItemStack mainHand) {
        if (mainHand.isEmpty()) {
            return Role.UNSPECIFIED;
        }
        if (GuardItemTags.isSpellbladeWeapon(mainHand)) {
            return Role.SPELLBLADE;
        }
        if (GuardItemTags.isRangedDamageWeapon(mainHand)) {
            return Role.RANGED;
        }
        if (GuardItemTags.isMeleeDamageWeapon(mainHand)) {
            return Role.MELEE;
        }
        if (GuardItemTags.isMageArchetypeWeapon(mainHand)) {
            return Role.MAGE;
        }
        return Role.UNSPECIFIED;
    }

    public static boolean usesMagicCasting(ItemStack mainHand) {
        return switch (resolve(mainHand)) {
            case MAGE, SPELLBLADE, UNSPECIFIED -> true;
            case MELEE, RANGED -> false;
        };
    }

    public static boolean usesMeleeCasting(ItemStack mainHand) {
        return switch (resolve(mainHand)) {
            case MELEE, SPELLBLADE, UNSPECIFIED -> !mainHand.isEmpty();
            case MAGE, RANGED -> false;
        };
    }

    public static Set<SpellCategory> filterActiveCategories(Role role, Set<SpellCategory> categories) {
        if (role == Role.SPELLBLADE || role == Role.UNSPECIFIED || categories.isEmpty()) {
            return categories;
        }

        Set<SpellCategory> filtered = EnumSet.copyOf(categories);
        switch (role) {
            case MELEE, RANGED -> filtered.removeAll(MAGE_CAST_CATEGORIES);
            case MAGE -> filtered.removeAll(MELEE_CAST_CATEGORIES);
            default -> {}
        }
        return filtered;
    }
}
