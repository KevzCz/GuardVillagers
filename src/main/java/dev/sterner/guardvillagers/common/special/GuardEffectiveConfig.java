package dev.sterner.guardvillagers.common.special;

import com.google.gson.JsonElement;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class GuardEffectiveConfig {

    private GuardEffectiveConfig() {}

    public static float spellSlotScrollChance(@Nullable GuardEntity guard) {
        return readFloat(guard, "spellSlotScrollChance", GuardVillagersConfig.spellSlotScrollChance);
    }

    public static int spellSlotScrollMaxTier(@Nullable GuardEntity guard) {
        return readInt(guard, "spellSlotScrollMaxTier", GuardVillagersConfig.spellSlotScrollMaxTier);
    }

    public static double healthModifier(@Nullable GuardEntity guard) {
        return readDouble(guard, "healthModifier", GuardVillagersConfig.healthModifier);
    }

    public static double speedModifier(@Nullable GuardEntity guard) {
        return readDouble(guard, "speedModifier", GuardVillagersConfig.speedModifier);
    }

    public static double followRangeModifier(@Nullable GuardEntity guard) {
        return readDouble(guard, "followRangeModifier", GuardVillagersConfig.followRangeModifier);
    }

    public static float amountOfHealthRegenerated(@Nullable GuardEntity guard) {
        return readFloat(guard, "amountOfHealthRegenerated", GuardVillagersConfig.amountOfHealthRegenerated);
    }

    public static boolean allowHiring(@Nullable GuardEntity guard) {
        if (guard != null && guard.getHireableOverride() != null) {
            return guard.getHireableOverride();
        }
        return readBoolean(guard, "allowHiring", GuardVillagersConfig.allowHiring);
    }

    public static boolean followHero(@Nullable GuardEntity guard) {
        if (guard != null && guard.getFollowHeroOverride() != null) {
            return guard.getFollowHeroOverride();
        }
        return readBoolean(guard, "followHero", GuardVillagersConfig.followHero);
    }

    public static int reputationRequirement(@Nullable GuardEntity guard) {
        return readInt(guard, "reputationRequirement", GuardVillagersConfig.reputationRequirement);
    }

    public static int reputationRequirementToBeAttacked(@Nullable GuardEntity guard) {
        return readInt(guard, "reputationRequirementToBeAttacked", GuardVillagersConfig.reputationRequirementToBeAttacked);
    }

    public static int reputationLostOnAttack(@Nullable GuardEntity guard) {
        return readInt(guard, "reputationLostOnAttack", GuardVillagersConfig.reputationLostOnAttack);
    }

    public static int hiringItemCount(@Nullable GuardEntity guard) {
        return readInt(guard, "hiringItemCount", GuardVillagersConfig.hiringItemCount);
    }

    public static float chanceToDropEquipment(@Nullable GuardEntity guard) {
        return readFloat(guard, "chanceToDropEquipment", GuardVillagersConfig.chanceToDropEquipment);
    }

    public static boolean giveGuardStuffHotv(@Nullable GuardEntity guard) {
        return readBoolean(guard, "giveGuardStuffHotv", GuardVillagersConfig.giveGuardStuffHotv);
    }

    public static boolean setGuardPatrolHotv(@Nullable GuardEntity guard) {
        return readBoolean(guard, "setGuardPatrolHotv", GuardVillagersConfig.setGuardPatrolHotv);
    }

    public static GuardVillagersConfig.SupportBuffPriority supportBuffPriority(@Nullable GuardEntity guard) {
        if (guard != null) return guard.getBuffPriority();
        return GuardVillagersConfig.supportBuffPriority;
    }

    public static float hiredOwnerHealThreshold(@Nullable GuardEntity guard) {
        return readFloat(guard, "hiredOwnerHealThreshold", GuardVillagersConfig.hiredOwnerHealThreshold);
    }

    private static Map<String, JsonElement> overrides(@Nullable GuardEntity guard) {
        return guard != null ? guard.getConfigOverrides() : Map.of();
    }

    private static float readFloat(@Nullable GuardEntity guard, String key, float fallback) {
        JsonElement value = overrides(guard).get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsFloat();
        }
        return fallback;
    }

    private static int readInt(@Nullable GuardEntity guard, String key, int fallback) {
        JsonElement value = overrides(guard).get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsInt();
        }
        return fallback;
    }

    private static double readDouble(@Nullable GuardEntity guard, String key, double fallback) {
        JsonElement value = overrides(guard).get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsDouble();
        }
        return fallback;
    }

    private static boolean readBoolean(@Nullable GuardEntity guard, String key, boolean fallback) {
        JsonElement value = overrides(guard).get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return fallback;
    }
}
