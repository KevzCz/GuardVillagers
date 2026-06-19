package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

public record GuardBuffPriorityPacket(int guardId) implements CustomPayload {
    public static final CustomPayload.Id<GuardBuffPriorityPacket> ID = new CustomPayload.Id<>(Identifier.of(GuardVillagers.MODID, "guard_buff_priority"));

    public static final PacketCodec<RegistryByteBuf, GuardBuffPriorityPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            GuardBuffPriorityPacket::guardId,
            GuardBuffPriorityPacket::new
    );

    public void handle(ServerPlayNetworking.Context context) {
        Entity entity = context.player().getWorld().getEntityById(guardId);
        if (entity instanceof GuardEntity guardEntity) {
            if (!guardEntity.isHired()) return;
            if (guardEntity.getOwnerId() == null || !guardEntity.getOwnerId().equals(context.player().getUuid())) return;
            guardEntity.cycleBuffPriority();
            guardEntity.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1, 1);
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
