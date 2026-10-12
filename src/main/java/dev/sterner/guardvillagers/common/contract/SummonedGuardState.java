package dev.sterner.guardvillagers.common.contract;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SummonedGuardState extends PersistentState {

    private static final String SAVE_KEY = GuardVillagers.MODID + "_summoned_guards";
    private static final String NBT_SUMMONS = "Summons";
    private static final String NBT_GUARD = "Guard";
    private static final String NBT_OWNER = "Owner";
    private static final String NBT_CONTRACT = "Contract";
    private static final String NBT_EXPIRES = "ExpiresAt";

    private static final PersistentState.Type<SummonedGuardState> TYPE =
            new PersistentState.Type<>(SummonedGuardState::new, SummonedGuardState::fromNbt, null);

    public record Summon(UUID guard, UUID owner, UUID contract, long expiresAt) {}

    private final List<Summon> summons = new ArrayList<>();

    public static SummonedGuardState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, SAVE_KEY);
    }

    public int activeFor(UUID owner, long now) {
        int count = 0;
        for (Summon summon : summons) {
            if (summon.owner().equals(owner) && summon.expiresAt() > now) {
                count++;
            }
        }
        return count;
    }

    public List<UUID> guardsFor(UUID contract, long now) {
        List<UUID> guards = new ArrayList<>();
        for (Summon summon : summons) {
            if (summon.contract().equals(contract) && summon.expiresAt() > now) {
                guards.add(summon.guard());
            }
        }
        return guards;
    }

    public void add(Summon summon) {
        summons.add(summon);
        markDirty();
    }

    public void remove(UUID guard) {
        if (summons.removeIf(summon -> summon.guard().equals(guard))) {
            markDirty();
        }
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        NbtList list = new NbtList();
        for (Summon summon : summons) {
            NbtCompound entry = new NbtCompound();
            entry.putUuid(NBT_GUARD, summon.guard());
            entry.putUuid(NBT_OWNER, summon.owner());
            entry.putUuid(NBT_CONTRACT, summon.contract());
            entry.putLong(NBT_EXPIRES, summon.expiresAt());
            list.add(entry);
        }
        nbt.put(NBT_SUMMONS, list);
        return nbt;
    }

    private static SummonedGuardState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        SummonedGuardState state = new SummonedGuardState();
        for (NbtElement element : nbt.getList(NBT_SUMMONS, NbtElement.COMPOUND_TYPE)) {
            NbtCompound entry = (NbtCompound) element;
            state.summons.add(new Summon(entry.getUuid(NBT_GUARD), entry.getUuid(NBT_OWNER),
                    entry.getUuid(NBT_CONTRACT), entry.getLong(NBT_EXPIRES)));
        }
        return state;
    }
}
