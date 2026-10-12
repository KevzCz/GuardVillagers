package dev.sterner.guardvillagers.common.entity;

import com.mojang.serialization.Codec;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.StringIdentifiable;

public enum GuardOrder implements StringIdentifiable {
    FOLLOW("follow"),
    HOLD("hold"),
    RELEASE("release");

    private static final float ORDER_SOUND_VOLUME = 1.0F;
    private static final float ORDER_SOUND_PITCH = 1.0F;

    public static final Codec<GuardOrder> CODEC = StringIdentifiable.createCodec(GuardOrder::values);

    private final String name;

    GuardOrder(String name) {
        this.name = name;
    }

    @Override
    public String asString() {
        return name;
    }

    public boolean isActive(GuardEntity guard) {
        return switch (this) {
            case FOLLOW -> guard.isFollowing();
            case HOLD -> !guard.isFollowing() && guard.isPatrolling();
            case RELEASE -> !guard.isFollowing() && !guard.isPatrolling();
        };
    }

    public boolean isAvailable(GuardEntity guard) {
        return this != FOLLOW || guard.isFollowable();
    }

    public void give(GuardEntity guard, PlayerEntity player) {
        if (!this.isAvailable(guard)) {
            return;
        }
        switch (this) {
            case FOLLOW -> {
                guard.setPatrolling(false);
                guard.setOwnerId(player.getUuid());
                guard.setFollowing(true);
            }
            case HOLD -> {
                guard.setFollowing(false);
                guard.setPatrolPos(guard.getBlockPos());
                guard.setPatrolling(true);
            }
            case RELEASE -> {
                guard.setFollowing(false);
                guard.setPatrolling(false);
            }
        }
        guard.playSound(SoundEvents.ENTITY_VILLAGER_YES, ORDER_SOUND_VOLUME, ORDER_SOUND_PITCH);
    }
}
