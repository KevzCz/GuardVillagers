package dev.sterner.guardvillagers.common.network;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record GuardPatrolPacket(int guardId, boolean pressed) implements CustomPayload {
    public static final CustomPayload.Id<GuardPatrolPacket> ID = new CustomPayload.Id<>(Identifier.of(GuardVillagers.MODID, "guard_patrol"));
    public static final PacketCodec<RegistryByteBuf, GuardPatrolPacket> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, GuardPatrolPacket::guardId,
            PacketCodecs.BOOL, GuardPatrolPacket::pressed,
            GuardPatrolPacket::new
    );

    public void handle(ServerPlayNetworking.Context context) {

        Entity entity = context.player().getWorld().getEntityById(guardId);
        if (entity instanceof GuardEntity guardEntity) {
            if (!guardEntity.canPlayerUseHeroControls(context.player())) {
                return;
            }
            BlockPos pos = guardEntity.getBlockPos();
            if (guardEntity.getBlockPos() != null) {
                guardEntity.setPatrolPos(pos);
            }
            guardEntity.setPatrolling(pressed);
        }
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}