package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

public record GuardFormationPacket(int guardId) implements CustomPayload {
    public static final CustomPayload.Id<GuardFormationPacket> ID = new CustomPayload.Id<>(Identifier.of(GuardVillagers.MODID, "guard_formation"));

    public static final PacketCodec<RegistryByteBuf, GuardFormationPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            GuardFormationPacket::guardId,
            GuardFormationPacket::new
    );

    public void handle(ServerPlayNetworking.Context context) {
        Entity entity = context.player().getWorld().getEntityById(guardId);
        if (entity instanceof GuardEntity guardEntity) {
            if (!guardEntity.isHired()) return;
            if (guardEntity.getOwnerId() == null || !guardEntity.getOwnerId().equals(context.player().getUuid())) return;
            guardEntity.cycleFollowFormation();
            guardEntity.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1, 1);
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
