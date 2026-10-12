package dev.sterner.guardvillagers.common.special;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

public final class GuardHiring {

    private static final String TAG_PREFIX = "#";
    private static final String TAG_TRANSLATION_PREFIX = "tag.item.";
    private static final String ENTRY_SEPARATOR = " / ";

    private GuardHiring() {}

    public static boolean canBeHired(GuardEntity guard) {
        return GuardEffectiveConfig.allowHiring(guard) && !guard.isHired();
    }

    public static List<String> entriesFor(GuardEntity guard) {
        List<String> override = guard.getHiringItemsOverride();
        return override.isEmpty() ? List.of(GuardVillagersConfig.hiringItem) : override;
    }

    public static int costFor(GuardEntity guard) {
        Integer override = guard.getHiringCostOverride();
        return override != null ? override : GuardEffectiveConfig.hiringItemCount(guard);
    }

    public static int countCarried(PlayerEntity player, List<String> entries) {
        PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (matches(stack, entries)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean canAfford(PlayerEntity player, GuardEntity guard) {
        return canAffordItems(player, entriesFor(guard), costFor(guard));
    }

    public static boolean payFromInventory(PlayerEntity player, GuardEntity guard) {
        return payItems(player, entriesFor(guard), costFor(guard));
    }

    public static boolean canAffordItems(PlayerEntity player, List<String> entries, int cost) {
        return cost <= 0 || player.getAbilities().creativeMode || countCarried(player, entries) >= cost;
    }

    public static boolean payItems(PlayerEntity player, List<String> entries, int cost) {
        if (!canAffordItems(player, entries, cost)) {
            return false;
        }
        if (cost <= 0 || player.getAbilities().creativeMode) {
            return true;
        }
        PlayerInventory inventory = player.getInventory();
        int remaining = cost;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (matches(stack, entries)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.decrement(taken);
                remaining -= taken;
            }
        }
        inventory.markDirty();
        return true;
    }

    public static boolean matches(ItemStack stack, List<String> entries) {
        if (stack.isEmpty()) {
            return false;
        }
        for (String entry : entries) {
            if (entry.startsWith(TAG_PREFIX)) {
                Identifier tagId = Identifier.tryParse(entry.substring(TAG_PREFIX.length()));
                if (tagId != null && stack.isIn(TagKey.of(RegistryKeys.ITEM, tagId))) {
                    return true;
                }
            } else {
                Identifier itemId = Identifier.tryParse(entry);
                if (itemId != null && Registries.ITEM.getId(stack.getItem()).equals(itemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Text describe(List<String> entries) {
        MutableText text = Text.empty();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                text.append(ENTRY_SEPARATOR);
            }
            text.append(describeEntry(entries.get(i)));
        }
        return text;
    }

    private static Text describeEntry(String entry) {
        if (entry.startsWith(TAG_PREFIX)) {
            Identifier tagId = Identifier.tryParse(entry.substring(TAG_PREFIX.length()));
            if (tagId == null) {
                return Text.literal(entry);
            }
            String key = TAG_TRANSLATION_PREFIX + tagId.getNamespace() + "." + tagId.getPath().replace('/', '.');
            return Text.translatableWithFallback(key, entry);
        }
        Identifier itemId = Identifier.tryParse(entry);
        if (itemId == null || !Registries.ITEM.containsId(itemId)) {
            return Text.literal(entry);
        }
        return Text.translatable(Registries.ITEM.get(itemId).getTranslationKey());
    }
}
