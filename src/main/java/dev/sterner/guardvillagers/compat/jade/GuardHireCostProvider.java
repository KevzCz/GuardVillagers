package dev.sterner.guardvillagers.compat.jade;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.special.GuardHiring;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.util.ArrayList;
import java.util.List;

public enum GuardHireCostProvider implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
    INSTANCE;

    private static final Identifier UID = GuardVillagers.id("hire_cost");
    private static final String NBT_COST = "GuardHireCost";
    private static final String NBT_ITEMS = "GuardHireItems";
    private static final String TOOLTIP_KEY = "guardvillagers.jade.hire_cost";

    @Override
    public void appendServerData(NbtCompound data, EntityAccessor accessor) {
        if (!(accessor.getEntity() instanceof GuardEntity guard) || !GuardHiring.canBeHired(guard)) {
            return;
        }
        NbtList items = new NbtList();
        for (String entry : GuardHiring.entriesFor(guard)) {
            items.add(NbtString.of(entry));
        }
        data.put(NBT_ITEMS, items);
        data.putInt(NBT_COST, GuardHiring.costFor(guard));
    }

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        NbtCompound data = accessor.getServerData();
        if (!data.contains(NBT_ITEMS)) {
            return;
        }
        List<String> entries = new ArrayList<>();
        for (NbtElement element : data.getList(NBT_ITEMS, NbtElement.STRING_TYPE)) {
            entries.add(element.asString());
        }
        if (entries.isEmpty()) {
            return;
        }
        tooltip.add(Text.translatable(TOOLTIP_KEY, data.getInt(NBT_COST), GuardHiring.describe(entries)).formatted(Formatting.GOLD));
    }

    @Override
    public Identifier getUid() {
        return UID;
    }
}
