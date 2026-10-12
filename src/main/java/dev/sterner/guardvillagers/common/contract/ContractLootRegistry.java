package dev.sterner.guardvillagers.common.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.special.SpecialGuardRegistry;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ContractLootRegistry implements SimpleSynchronousResourceReloadListener {

    public static final ContractLootRegistry INSTANCE = new ContractLootRegistry();
    private static final String FOLDER = "guardvillagers/contract_loot";
    private static final String JSON_SUFFIX = ".json";
    private static final String WILDCARD = "*";
    private static final String KEY_LOOT_TABLES = "loot_tables";
    private static final String KEY_CHANCE = "chance";
    private static final String KEY_CONTRACTS = "contracts";
    public static final String KEY_WEIGHT = "weight";
    private static final float DEFAULT_CHANCE = 1.0F;
    public static final int DEFAULT_WEIGHT = 1;

    public record WeightedContract(int weight, ContractTerms terms) {}

    public record Rule(Identifier id, List<String> lootTables, float chance, List<WeightedContract> contracts) {

        boolean appliesTo(Identifier table) {
            String id = table.toString();
            for (String pattern : lootTables) {
                if (pattern.endsWith(WILDCARD)
                        ? id.startsWith(pattern.substring(0, pattern.length() - WILDCARD.length()))
                        : id.equals(pattern)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    private ContractLootRegistry() {}

    @Override
    public Identifier getFabricId() {
        return GuardVillagers.id("contract_loot");
    }

    @Override
    public void reload(ResourceManager manager) {
        rules.clear();
        manager.findResources(FOLDER, path -> path.getPath().endsWith(JSON_SUFFIX)).forEach((id, resource) -> {
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (SpecialGuardRegistry.isDisabled(root)) {
                    return;
                }
                Rule rule = parse(root, id, ruleId(id));
                if (!rule.contracts().isEmpty()) {
                    rules.add(rule);
                }
            } catch (Exception error) {
                GuardVillagers.LOGGER.error("Failed to load contract loot {}", id, error);
            }
        });
        GuardVillagers.LOGGER.info("Loaded {} contract loot rules", rules.size());
    }

    public List<Rule> all() {
        return List.copyOf(rules);
    }

    public Optional<Rule> get(Identifier id) {
        for (Rule rule : rules) {
            if (rule.id().equals(id)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public static Optional<ContractTerms> pick(List<WeightedContract> contracts, Random random) {
        int total = 0;
        for (WeightedContract contract : contracts) {
            total += contract.weight();
        }
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = random.nextInt(total);
        for (WeightedContract contract : contracts) {
            roll -= contract.weight();
            if (roll < 0) {
                return Optional.of(contract.terms());
            }
        }
        return Optional.empty();
    }

    private static Identifier ruleId(Identifier file) {
        String path = file.getPath();
        return Identifier.of(file.getNamespace(), path.substring(FOLDER.length() + 1, path.length() - JSON_SUFFIX.length()));
    }

    public List<Rule> forTable(Identifier table) {
        List<Rule> matching = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.appliesTo(table)) {
                matching.add(rule);
            }
        }
        return matching;
    }

    private static Rule parse(JsonObject root, Identifier source, Identifier id) {
        List<String> tables = new ArrayList<>();
        if (root.has(KEY_LOOT_TABLES)) {
            for (JsonElement element : root.getAsJsonArray(KEY_LOOT_TABLES)) {
                tables.add(element.getAsString().trim());
            }
        }
        float chance = root.has(KEY_CHANCE) ? root.get(KEY_CHANCE).getAsFloat() : DEFAULT_CHANCE;
        List<WeightedContract> contracts = new ArrayList<>();
        if (root.has(KEY_CONTRACTS)) {
            for (JsonElement element : root.getAsJsonArray(KEY_CONTRACTS)) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                int weight = entry.has(KEY_WEIGHT) ? Math.max(0, entry.get(KEY_WEIGHT).getAsInt()) : DEFAULT_WEIGHT;
                ContractTerms.CODEC.parse(JsonOps.INSTANCE, entry)
                        .resultOrPartial(error -> GuardVillagers.LOGGER.error("Invalid contract in {}: {}", source, error))
                        .filter(terms -> weight > 0)
                        .ifPresent(terms -> contracts.add(new WeightedContract(weight, terms)));
            }
        }
        return new Rule(id, List.copyOf(tables), chance, List.copyOf(contracts));
    }
}
