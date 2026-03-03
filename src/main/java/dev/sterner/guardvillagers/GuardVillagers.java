package dev.sterner.guardvillagers;

import dev.sterner.guardvillagers.common.command.*;
import dev.sterner.guardvillagers.common.entity.*;
import dev.sterner.guardvillagers.common.network.*;
import dev.sterner.guardvillagers.common.screenhandler.*;
import eu.midnightdust.lib.config.*;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.command.v2.*;
import net.fabricmc.fabric.api.entity.event.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.itemgroup.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.object.builder.v1.entity.*;
import net.fabricmc.fabric.api.screenhandler.v1.*;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.brain.*;
import net.minecraft.entity.damage.*;
import net.minecraft.entity.effect.*;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.*;
import net.minecraft.item.*;
import net.minecraft.particle.*;
import net.minecraft.registry.*;
import net.minecraft.screen.*;
import net.minecraft.sound.*;
import net.minecraft.util.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.village.*;
import net.minecraft.world.*;
import org.jetbrains.annotations.*;
import org.slf4j.*;

import java.util.*;
import java.util.function.*;

public class GuardVillagers implements ModInitializer {
    public static final String MODID = "guardvillagers";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    public static final ScreenHandlerType<GuardVillagerScreenHandler> GUARD_SCREEN_HANDLER =
            new ExtendedScreenHandlerType<>((syncId, inventory, data) -> new GuardVillagerScreenHandler(syncId, inventory, data), GuardData.PACKET_CODEC);

    public static final EntityType<GuardEntity> GUARD_VILLAGER = Registry.register(Registries.ENTITY_TYPE, id("guard"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, GuardEntity::new).dimensions(EntityDimensions.fixed(0.6f, 1.8f)).build());

    public static final Item GUARD_SPAWN_EGG = new SpawnEggItem(GUARD_VILLAGER, 5651507, 8412749, new Item.Settings());

    private static final List<Runnable> PENDING_GUARD_SPAWNS = new ArrayList<>();

    public static Hand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
        return itemPredicate.test(livingEntity.getMainHandStack().getItem()) ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    public static SoundEvent GUARD_AMBIENT = SoundEvent.of(id( "entity.guard.ambient"));
    public static SoundEvent GUARD_HURT = SoundEvent.of(id( "entity.guard.hurt"));
    public static SoundEvent GUARD_DEATH = SoundEvent.of(id("entity.guard.death"));

    public static Identifier id(String name){
        return Identifier.of(MODID, name);
    }

    @Override
    public void onInitialize() {
        MidnightConfig.init(MODID, GuardVillagersConfig.class);
        FabricDefaultAttributeRegistry.register(GUARD_VILLAGER, GuardEntity.createAttributes());
        
        CommandRegistrationCallback.EVENT.register(GuardDebugCommand::register);
        Registry.register(Registries.ITEM, id("guard_spawn_egg"), GUARD_SPAWN_EGG);
        Registry.register(Registries.SCREEN_HANDLER, id("guard_screen"), GUARD_SCREEN_HANDLER);
        Registry.register(Registries.SOUND_EVENT, id("entity.guard.ambient"), GUARD_AMBIENT);
        Registry.register(Registries.SOUND_EVENT, id( "entity.guard.hurt"), GUARD_HURT);
        Registry.register(Registries.SOUND_EVENT, id( "entity.guard.death"), GUARD_DEATH);

        PayloadTypeRegistry.playC2S().register(GuardFollowPacket.ID, GuardFollowPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(GuardPatrolPacket.ID, GuardPatrolPacket.PACKET_CODEC);

        PayloadTypeRegistry.playS2C().register(GuardFollowPacket.ID, GuardFollowPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(GuardPatrolPacket.ID, GuardPatrolPacket.PACKET_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GuardFollowPacket.ID, GuardFollowPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(GuardPatrolPacket.ID, GuardPatrolPacket::handle);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(GUARD_SPAWN_EGG));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onDamage);
        UseEntityCallback.EVENT.register(this::villagerConvert);

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof VillagerEntity villagerEntity && villagerEntity.isNatural()) {
                var spawnChance = MathHelper.clamp(GuardVillagersConfig.spawnChancePerVillager, 0f, 1f);
                if (world.random.nextFloat() < spawnChance) {
                    synchronized (PENDING_GUARD_SPAWNS) {
                        PENDING_GUARD_SPAWNS.add(() -> {
                            if (! villagerEntity.isRemoved() && villagerEntity.isAlive()) {
                                GuardEntity guardEntity = GUARD_VILLAGER.create(world);
                                if (guardEntity != null) {
                                    guardEntity.spawnWithArmor = true;
                                    guardEntity.initialize(world, world.getLocalDifficulty(villagerEntity.getBlockPos()), SpawnReason.NATURAL, null);
                                    guardEntity.refreshPositionAndAngles(villagerEntity.getBlockPos(), 0.0f, 0.0f);

                                    int i = GuardEntity.getRandomTypeForBiome(guardEntity.getWorld(), guardEntity.getBlockPos());
                                    guardEntity.setGuardVariant(i);
                                    guardEntity.setPersistent();
                                    guardEntity.setCustomName(villagerEntity.getCustomName());
                                    guardEntity.setCustomNameVisible(villagerEntity.isCustomNameVisible());
                                    guardEntity.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
                                    guardEntity.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
                                    guardEntity.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
                                    guardEntity.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
                                    guardEntity.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
                                    guardEntity.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);

                                    world.spawnEntityAndPassengers(guardEntity);
                                }
                            }
                        });
                    }
                }
            }
        });

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            synchronized (PENDING_GUARD_SPAWNS) {
                if (!PENDING_GUARD_SPAWNS.isEmpty()) {
                    PENDING_GUARD_SPAWNS.forEach(Runnable::run);
                    PENDING_GUARD_SPAWNS.clear();
                }
            }
        });
    }


    private boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
        Entity attacker = source.getAttacker();
        if (entity == null || attacker == null)
            return true;
        boolean shouldDamage = true;
        boolean isVillager = entity.getType() == EntityType.VILLAGER || entity.getType() == GuardVillagers.GUARD_VILLAGER;
        boolean isGolem = isVillager || entity.getType() == EntityType.IRON_GOLEM;
        if (isGolem && attacker.getType() == GuardVillagers.GUARD_VILLAGER && ! GuardVillagersConfig.guardArrowsHurtVillagers) {
            shouldDamage = false;
        }
        if (isVillager && attacker instanceof MobEntity) {
            List<MobEntity> list = attacker.getWorld().getNonSpectatingEntities(MobEntity.class, attacker.getBoundingBox().expand(GuardVillagersConfig.guardVillagerHelpRange, 5.0D, GuardVillagersConfig.guardVillagerHelpRange));
            for (MobEntity mob : list) {
                boolean type = mob.getType() == GUARD_VILLAGER || mob.getType() == EntityType.IRON_GOLEM;
                boolean trueSourceGolem = attacker.getType() == GUARD_VILLAGER || attacker.getType() == EntityType.IRON_GOLEM;
                if (! trueSourceGolem && type && mob.getTarget() == null)
                    mob.setTarget((MobEntity) attacker);
            }
        }
        return shouldDamage;
    }

    private ActionResult villagerConvert(PlayerEntity player, World world, Hand hand, Entity entity, @Nullable EntityHitResult entityHitResult) {
        ItemStack itemStack = player.getStackInHand(hand);
        if ((itemStack.getItem() instanceof SwordItem || itemStack.getItem() instanceof CrossbowItem) && player.isSneaking()) {
            if (entityHitResult != null) {
                Entity target = entityHitResult.getEntity();
                if (target instanceof VillagerEntity villagerEntity) {
                    if (! villagerEntity.isBaby()) {
                        if (villagerEntity.getVillagerData().getProfession() == VillagerProfession.NONE || villagerEntity.getVillagerData().getProfession() == VillagerProfession.NITWIT) {
                            if (! GuardVillagersConfig.convertVillagerIfHaveHotv || player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.convertVillagerIfHaveHotv) {
                                convertVillager(villagerEntity, player, world);
                                if (! player.getAbilities().creativeMode)
                                    itemStack.decrement(1);
                                return ActionResult.SUCCESS;
                            }
                        }
                    }
                }
            }

        }

        return ActionResult.PASS;
    }

    private void convertVillager(VillagerEntity villagerEntity, PlayerEntity player, World world) {
        player.swingHand(Hand.MAIN_HAND);
        ItemStack itemstack = player.getEquippedStack(EquipmentSlot.MAINHAND);
        GuardEntity guard = GUARD_VILLAGER.create(world);
        if (guard == null)
            return;
        if (player.getWorld().isClient()) {
            ParticleEffect particleEffect = ParticleTypes.HAPPY_VILLAGER;
            for (int i = 0; i < 10; ++i) {
                double d0 = villagerEntity.getRandom().nextGaussian() * 0.02D;
                double d1 = villagerEntity.getRandom().nextGaussian() * 0.02D;
                double d2 = villagerEntity.getRandom().nextGaussian() * 0.02D;
                villagerEntity.getWorld().addParticle(particleEffect, villagerEntity.getX() + (double) (villagerEntity.getRandom().nextFloat() * villagerEntity.getWidth() * 2.0F) - (double) villagerEntity.getWidth(), villagerEntity.getY() + 0.5D + (double) (villagerEntity.getRandom().nextFloat() * villagerEntity.getWidth()),
                        villagerEntity.getZ() + (double) (villagerEntity.getRandom().nextFloat() * villagerEntity.getWidth() * 2.0F) - (double) villagerEntity.getWidth(), d0, d1, d2);
            }
        }
        guard.copyPositionAndRotation(villagerEntity);
        guard.headYaw = villagerEntity.headYaw;
        guard.refreshPositionAndAngles(villagerEntity.getX(), villagerEntity.getY(), villagerEntity.getZ(), villagerEntity.getYaw(), villagerEntity.getPitch());
        guard.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1.0F, 1.0F);
        guard.equipStack(EquipmentSlot.MAINHAND, itemstack.copy());
        guard.guardInventory.setStack(5, itemstack.copy());

        int i = GuardEntity.getRandomTypeForBiome(guard.getWorld(), guard.getBlockPos());
        guard.setGuardVariant(i);
        guard.setPersistent();
        guard.setCustomName(villagerEntity.getCustomName());
        guard.setCustomNameVisible(villagerEntity.isCustomNameVisible());
        guard.setEquipmentDropChance(EquipmentSlot.HEAD, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.CHEST, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.FEET, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.LEGS, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.MAINHAND, 100.0F);
        guard.setEquipmentDropChance(EquipmentSlot.OFFHAND, 100.0F);
        world.spawnEntity(guard);
        villagerEntity.releaseTicketFor(MemoryModuleType.HOME);
        villagerEntity.releaseTicketFor(MemoryModuleType.JOB_SITE);
        villagerEntity.releaseTicketFor(MemoryModuleType.MEETING_POINT);
        villagerEntity.discard();
    }

    public static boolean hotvChecker(PlayerEntity player, GuardEntity guard) {
        return player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && GuardVillagersConfig.giveGuardStuffHotv
                || ! GuardVillagersConfig.giveGuardStuffHotv || guard.getPlayerEntityReputation(player) > GuardVillagersConfig.reputationRequirement && !player.getWorld().isClient();
    }
}