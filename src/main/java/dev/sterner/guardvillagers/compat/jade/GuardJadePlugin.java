package dev.sterner.guardvillagers.compat.jade;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public class GuardJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerEntityDataProvider(GuardHireCostProvider.INSTANCE, GuardEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(GuardHireCostProvider.INSTANCE, GuardEntity.class);
    }
}
