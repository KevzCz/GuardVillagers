package dev.sterner.guardvillagers.common.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.special.GuardTextUtils;
import dev.sterner.guardvillagers.common.special.SpecialGuardApplicator;
import dev.sterner.guardvillagers.common.special.SpecialGuardDefinition;
import dev.sterner.guardvillagers.common.special.SpecialGuardRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.Codecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record ContractTerms(
        Either<Identifier, JsonElement> guard,
        Optional<String> name,
        Optional<Integer> termDays,
        ContractCost cost,
        int uses,
        int maxActive,
        int cooldownDays,
        int ribbon,
        List<Identifier> dimensions
) {

    public static final Identifier INLINE_GUARD_ID = GuardVillagers.id("contract_guard");
    public static final int UNLIMITED_USES = 0;
    public static final int DEFAULT_USES = 1;
    public static final int DEFAULT_MAX_ACTIVE = 1;
    public static final int DEFAULT_COOLDOWN_DAYS = 0;
    public static final int DEFAULT_RIBBON = 0xE02018;
    private static final String DISPLAY_KEY = "display";
    private static final String NAME_KEY = "name";
    private static final String NAME_SEPARATOR = ", ";
    private static final String UNKNOWN_GUARD_KEY = "item.guardvillagers.contract.unknown_guard";

    public static final Codec<ContractTerms> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.either(Identifier.CODEC, Codecs.JSON_ELEMENT).fieldOf("guard").forGetter(ContractTerms::guard),
            Codec.STRING.optionalFieldOf("name").forGetter(ContractTerms::name),
            Codec.INT.optionalFieldOf("term_days").forGetter(ContractTerms::termDays),
            ContractCost.CODEC.optionalFieldOf("cost", ContractCost.FREE).forGetter(ContractTerms::cost),
            Codec.INT.optionalFieldOf("uses", DEFAULT_USES).forGetter(ContractTerms::uses),
            Codec.INT.optionalFieldOf("max_active", DEFAULT_MAX_ACTIVE).forGetter(ContractTerms::maxActive),
            Codec.INT.optionalFieldOf("cooldown_days", DEFAULT_COOLDOWN_DAYS).forGetter(ContractTerms::cooldownDays),
            Codec.INT.optionalFieldOf("ribbon", DEFAULT_RIBBON).forGetter(ContractTerms::ribbon),
            Identifier.CODEC.listOf().optionalFieldOf("dimensions", List.of()).forGetter(ContractTerms::dimensions)
    ).apply(instance, ContractTerms::new));

    public static final PacketCodec<RegistryByteBuf, ContractTerms> PACKET_CODEC = PacketCodecs.registryCodec(CODEC);

    public static ContractTerms of(Identifier guard) {
        return new ContractTerms(Either.left(guard), Optional.empty(), Optional.empty(), ContractCost.FREE,
                DEFAULT_USES, DEFAULT_MAX_ACTIVE, DEFAULT_COOLDOWN_DAYS, DEFAULT_RIBBON, List.of());
    }

    public ContractTerms withName(Optional<String> name) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withTermDays(int days) {
        return new ContractTerms(guard, name, Optional.of(days), cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withCost(ContractCost cost) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withUses(int uses) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withMaxActive(int maxActive) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withCooldownDays(int cooldownDays) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withRibbon(int ribbon) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, dimensions);
    }

    public ContractTerms withDimensions(List<Identifier> dimensions) {
        return new ContractTerms(guard, name, termDays, cost, uses, maxActive, cooldownDays, ribbon, List.copyOf(dimensions));
    }

    public int effectiveTermDays() {
        return termDays.orElse(GuardVillagersConfig.defaultContractTermDays);
    }

    public boolean hasUnlimitedUses() {
        return uses <= UNLIMITED_USES;
    }

    public Optional<SpecialGuardDefinition> resolve() {
        return guard.map(SpecialGuardRegistry.INSTANCE::get, json -> {
            if (!json.isJsonObject()) {
                return Optional.empty();
            }
            try {
                return Optional.of(SpecialGuardDefinition.parse(INLINE_GUARD_ID, json.getAsJsonObject()));
            } catch (RuntimeException error) {
                GuardVillagers.LOGGER.error("Invalid inline guard on a contract", error);
                return Optional.empty();
            }
        });
    }

    public Text displayName() {
        if (name.isPresent()) {
            return GuardTextUtils.translatableOrColored(name.get());
        }
        return guard.map(id -> Text.literal(readable(id.getPath())), json -> inlineName(json)
                .map(GuardTextUtils::translatableOrColored)
                .orElse(Text.translatable(UNKNOWN_GUARD_KEY)));
    }

    private static Optional<String> inlineName(JsonElement json) {
        if (!json.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject root = json.getAsJsonObject();
        if (!root.has(DISPLAY_KEY) || !root.get(DISPLAY_KEY).isJsonObject()) {
            return Optional.empty();
        }
        JsonObject display = root.getAsJsonObject(DISPLAY_KEY);
        if (!display.has(NAME_KEY) || !display.get(NAME_KEY).isJsonPrimitive()) {
            return Optional.empty();
        }
        String raw = display.get(NAME_KEY).getAsString()
                .replace(SpecialGuardApplicator.NAME_PLACEHOLDER + NAME_SEPARATOR, "")
                .replace(SpecialGuardApplicator.NAME_PLACEHOLDER, "")
                .trim();
        return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
    }

    private static String readable(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        List<String> words = new ArrayList<>();
        for (String word : last.split("_")) {
            if (!word.isEmpty()) {
                words.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
            }
        }
        return String.join(" ", words);
    }
}
