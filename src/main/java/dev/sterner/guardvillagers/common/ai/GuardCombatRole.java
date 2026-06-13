package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

public final class GuardCombatRole {

    public enum SupportRole {
        PRIEST,
        BARD,
        NONE
    }

    private GuardCombatRole() {}

    public static SupportRole resolve(GuardEntity guard) {
        if (guard.guardInventory == null) {
            return SupportRole.NONE;
        }
        ItemStack main = guard.getMainHandStack();
        if (isBardWeapon(main)) {
            return SupportRole.BARD;
        }
        if (isPriestWeapon(main) || wearsPriestArmor(guard)) {
            return SupportRole.PRIEST;
        }
        if (wearsBardArmor(guard) && guard.getSpellManager().hasSupportSpells()) {
            return SupportRole.BARD;
        }

        GuardSpellManager manager = guard.getSpellManager();
        if (manager.hasCastablePhysicalMeleeSpell()) {
            return SupportRole.NONE;
        }
        if (!manager.shouldUseProjectileCasting() && !manager.isMagicCastingWeapon()) {
            return SupportRole.NONE;
        }

        if (manager.getBestCastableAllyBuffSong().isPresent()
                || (manager.hasSupportBuffSpells() && GuardItemTags.isBardInstrument(main))) {
            return SupportRole.BARD;
        }
        if ((manager.hasHealingSpells() || manager.hasSupportSpells())
                && GuardItemTags.isHealingWeapon(main)) {
            return SupportRole.PRIEST;
        }
        return SupportRole.NONE;
    }

    public static boolean isDedicatedSupport(GuardEntity guard) {
        return resolve(guard) != SupportRole.NONE;
    }

    
    public static boolean isRetreatHealer(GuardEntity guard) {
        SupportRole role = resolve(guard);
        if (role == SupportRole.PRIEST) {
            return guard.getSpellManager().hasHealingSpells() || guard.getSpellManager().hasSupportSpells();
        }
        if (role == SupportRole.BARD) {
            return guard.getSpellManager().hasHealingSpells()
                    || guard.getSpellManager().getBestCastableAllyBuffSong().isPresent();
        }
        return false;
    }

    private static boolean isBardWeapon(ItemStack stack) {
        return GuardItemTags.isBardInstrument(stack);
    }

    private static boolean isPriestWeapon(ItemStack stack) {
        return GuardItemTags.isHealingWeapon(stack);
    }

    private static boolean wearsPriestArmor(GuardEntity guard) {
        return armorPathContains(guard, "priest");
    }

    private static boolean wearsBardArmor(GuardEntity guard) {
        return armorPathContains(guard, "bard");
    }

    private static boolean armorPathContains(GuardEntity guard, String token) {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = guard.getEquippedStack(slot);
            if (!armor.isEmpty() && Registries.ITEM.getId(armor.getItem()).getPath().contains(token)) {
                return true;
            }
        }
        return false;
    }
}
