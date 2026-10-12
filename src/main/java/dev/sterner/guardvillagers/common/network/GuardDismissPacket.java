package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record GuardDismissPacket(int guardId) implements CustomPayload {
    public static final CustomPayload.Id<GuardDismissPacket> ID = new CustomPayload.Id<>(GuardVillagers.id("guard_dismiss"));

    public static final PacketCodec<RegistryByteBuf, GuardDismissPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            GuardDismissPacket::guardId,
            GuardDismissPacket::new
    );

    public void handle(ServerPlayNetworking.Context context) {
        Entity entity = context.player().getWorld().getEntityById(guardId);
        if (entity instanceof GuardEntity guardEntity && guardEntity.dismissBy(context.player())) {
            context.player().closeHandledScreen();
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
