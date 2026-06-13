package dev.sterner.guardvillagers.common.special;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class SpecialGuardItemRoll {

    private SpecialGuardItemRoll() {}

    @Nullable
    public static JsonElement resolveItemJson(@Nullable JsonElement config, Random random) {
        if (config == null || config.isJsonNull()) {
            return null;
        }
        if (config.isJsonArray()) {
            return pickFromChoices(config.getAsJsonArray(), random);
        }
        if (!config.isJsonObject()) {
            return config;
        }

        JsonObject obj = config.getAsJsonObject();
        if (obj.has("choices") && obj.get("choices").isJsonArray()) {
            return pickFromChoices(obj.getAsJsonArray("choices"), random);
        }
        if (obj.has("chance")) {
            double chance = obj.get("chance").getAsDouble();
            if (random.nextDouble() >= chance) {
                return null;
            }
        }
        if (obj.has("empty") && obj.get("empty").getAsBoolean()) {
            return null;
        }

        if (obj.has("stack")) {
            return obj.get("stack");
        }
        if (obj.has("item")) {
            return obj.get("item");
        }
        if (obj.has("id")) {
            return obj;
        }
        return null;
    }

    @Nullable
    private static JsonElement pickFromChoices(JsonArray choices, Random random) {
        List<WeightedChoice> weighted = new ArrayList<>();
        int totalWeight = 0;
        for (JsonElement element : choices) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int weight = entry.has("weight") ? Math.max(0, entry.get("weight").getAsInt()) : 1;
            if (weight <= 0) {
                continue;
            }
            weighted.add(new WeightedChoice(weight, entry));
            totalWeight += weight;
        }
        if (totalWeight <= 0 || weighted.isEmpty()) {
            return null;
        }

        int roll = random.nextInt(totalWeight);
        for (WeightedChoice choice : weighted) {
            roll -= choice.weight();
            if (roll < 0) {
                return resolveItemJson(choice.entry(), random);
            }
        }
        return null;
    }

    private record WeightedChoice(int weight, JsonObject entry) {}
}
