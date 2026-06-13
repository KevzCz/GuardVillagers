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

public record GuardFollowPacket(int guardId) implements CustomPayload {
    public static final CustomPayload.Id<GuardFollowPacket> ID = new CustomPayload.Id<>(Identifier.of(GuardVillagers.MODID, "guard_follow"));

    public static final PacketCodec<RegistryByteBuf, GuardFollowPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER,
            GuardFollowPacket::guardId,
            GuardFollowPacket::new
    );

    public void handle(ServerPlayNetworking.Context context) {
        Entity entity = context.player().getWorld().getEntityById(guardId);
        if (entity instanceof GuardEntity guardEntity) {
            if (!guardEntity.canPlayerUseHeroControls(context.player())) {
                return;
            }
            guardEntity.setFollowing(!guardEntity.isFollowing());
            guardEntity.setOwnerId(context.player().getUuid());
            guardEntity.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1, 1);
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
