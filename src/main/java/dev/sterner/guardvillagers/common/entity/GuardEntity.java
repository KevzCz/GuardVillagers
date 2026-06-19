package dev.sterner.guardvillagers.common.entity;

import com.google.common.collect.*;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sterner.guardvillagers.*;
import dev.sterner.guardvillagers.common.debug.*;
import dev.sterner.guardvillagers.common.special.GuardEffectiveConfig;
import dev.sterner.guardvillagers.common.special.SpecialGuardApplicator;
import dev.sterner.guardvillagers.common.special.SpecialGuardRegistry;
import dev.sterner.guardvillagers.common.entity.goal.*;
import dev.sterner.guardvillagers.common.entity.goal.spell.*;
import dev.sterner.guardvillagers.common.network.*;
import dev.sterner.guardvillagers.common.screenhandler.*;
import net.fabricmc.fabric.api.screenhandler.v1.*;
import net.minecraft.component.*;
import net.minecraft.component.type.*;
import net.minecraft.enchantment.*;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.ai.pathing.*;
import net.minecraft.entity.attribute.*;
import net.minecraft.entity.damage.*;
import net.minecraft.entity.data.*;
import net.minecraft.entity.effect.*;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.*;
import net.minecraft.entity.projectile.*;
import net.minecraft.entity.raid.*;
import net.minecraft.inventory.*;
import net.minecraft.item.*;
import net.minecraft.loot.*;
import net.minecraft.loot.context.*;
import net.minecraft.nbt.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.*;
import net.minecraft.screen.*;
import net.minecraft.server.network.*;
import net.minecraft.server.world.*;
import net.minecraft.sound.*;
import net.minecraft.text.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.util.math.intprovider.*;
import net.minecraft.village.VillageGossipType;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.*;
import net.minecraft.world.*;
import net.spell_engine.api.spell.*;
import net.spell_engine.internals.*;
import net.spell_engine.internals.arrow.*;
import net.spell_engine.internals.casting.*;
import net.spell_engine.internals.melee.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class GuardEntity extends TameableEntity implements CrossbowUser, RangedAttackMob, Angerable, InventoryChangedListener, InteractionObserver, SpellCasterEntity {
    private static final EntityAttributeModifier USE_ITEM_SPEED_PENALTY = new EntityAttributeModifier(GuardVillagers.id("speed_penalty"), -0.25D, EntityAttributeModifier.Operation.ADD_VALUE);
    private static final TrackedData<Optional<BlockPos>> GUARD_POS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.OPTIONAL_BLOCK_POS);
    private static final TrackedData<Boolean> PATROLLING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> GUARD_VARIANT = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> RUNNING_TO_EAT = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> DATA_CHARGING_STATE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> KICKING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> FOLLOWING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> CASTING_SPELL = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> CASTING_MELEE_SPELL = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN); 
    private static final TrackedData<Integer> CAST_PROGRESS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER); 
    private static final TrackedData<Integer> SPELL_SWING_TICKS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER); 
    private static final TrackedData<String> CAST_ANIMATION_ID = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.STRING); 
    
    private static final TrackedData<String> CAST_HOLD_ANIMATION_ID = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> RELEASE_ANIMATION_ID = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.STRING); 
    private static final TrackedData<String> SWING_ANIMATION_ID = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.STRING); 
    private static final TrackedData<Float> SWING_ANIMATION_SPEED = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> ANIMATION_SEQUENCE = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    
    private static final TrackedData<Float> CAST_ANIMATION_SPIN = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Long> CAST_STARTED_AT = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.LONG);
    private static final TrackedData<Integer> CAST_LENGTH_TICKS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> CAST_CHANNEL_TICKS = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> CAST_ANIMATION_SPEED = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> CAST_ANIMATION_PITCH = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    
    private static final TrackedData<String> SYNCED_CAST_SPELL_ID = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final Map<EntityPose, EntityDimensions> SIZE_BY_POSE = ImmutableMap.<EntityPose, EntityDimensions>builder().put(EntityPose.STANDING, EntityDimensions.changing(0.6F, 1.95F)).put(EntityPose.SLEEPING, SLEEPING_DIMENSIONS).put(EntityPose.FALL_FLYING, EntityDimensions.changing(0.6F, 0.6F)).put(EntityPose.SWIMMING, EntityDimensions.changing(0.6F, 0.6F)).put(EntityPose.SPIN_ATTACK, EntityDimensions.changing(0.6F, 0.6F)).put(EntityPose.CROUCHING, EntityDimensions.changing(0.6F, 1.75F)).put(EntityPose.DYING, EntityDimensions.fixed(0.2F, 0.2F)).build();
    private static final UniformIntProvider angerTime = TimeHelper.betweenSeconds(20, 39);
    public static final Map<EquipmentSlot, RegistryKey<LootTable>> EQUIPMENT_SLOT_ITEMS = Util.make(Maps.newHashMap(), (slotItems) -> {
        slotItems.put(EquipmentSlot.MAINHAND, GuardEntityLootTables.GUARD_MAIN_HAND);
        slotItems.put(EquipmentSlot.OFFHAND, GuardEntityLootTables.GUARD_OFF_HAND);
        slotItems.put(EquipmentSlot.HEAD, GuardEntityLootTables.GUARD_HELMET);
        slotItems.put(EquipmentSlot.CHEST, GuardEntityLootTables.GUARD_CHEST);
        slotItems.put(EquipmentSlot.LEGS, GuardEntityLootTables.GUARD_LEGGINGS);
        slotItems.put(EquipmentSlot.FEET, GuardEntityLootTables.GUARD_FEET);
    });

    
    private SpellCooldownManager cooldownManager;
    private int channelTickIndex = 0;
    private SpellCast.Process spellCastProcess = null;
    private ArrowShootContext arrowShootContext = null;
    @Nullable
    private Melee.ActiveAttack meleeSkillAttack = null;
    @Nullable
    private RegistryEntry<Spell> activeMeleeSkill = null;

    private final GuardSpellManager spellManager = new GuardSpellManager(this);
    private final MeleeSpellHandler meleeSpellHandler = new MeleeSpellHandler(this);
    private final DefensiveSpellHandler defensiveSpellHandler = new DefensiveSpellHandler(this);
    private LivingEntity lastShieldBlockAttacker = null; 
    private final VillagerGossips gossips = new VillagerGossips();
    public long lastGossipTime;
    public long lastGossipDecayTime;
    public SimpleInventory guardInventory = new SimpleInventory(7);
    public static final int SPELL_SLOT_INDEX = 6;
    public int kickTicks;
    public int shieldCoolDown;
    public int kickCoolDown;
    public boolean interacting;
    @Nullable private UUID hotvFollowerId;
    public boolean isBeingViewedInGui;
    public boolean spawnWithArmor;
    
    public int spellCastGraceTicks;
    private int remainingPersistentAngerTime;
    private UUID persistentAngerTarget;
    public Queue<Pair<Integer, Runnable>> delayedTasks = new LinkedList<>();
    
    public int animationTaskGeneration = 0;
    private final Set<UUID> spellSummons = new HashSet<>();
    @Nullable private Identifier specialGuardType;
    private Map<String, JsonElement> configOverrides = Map.of();
    private GuardVillagersConfig.SupportBuffPriority buffPriority = GuardVillagersConfig.SupportBuffPriority.OWNER;
    private GuardVillagersConfig.FollowFormation followFormation = GuardVillagersConfig.FollowFormation.FREE;
    private NbtCompound specialEntityData = new NbtCompound();
    private boolean treatAsHeroOfTheVillage;
    @Nullable private Boolean hireableOverride;
    @Nullable private Boolean followHeroOverride;
    private boolean skipLootTables;
    private boolean applyEquipmentOverridesAfterLoot;
    private boolean blockGui;
    private boolean lockEquipment;
    private boolean immutableEquipment;
    private boolean attackPlayers;
    private List<Identifier> attackMobs = List.of();
    @Nullable private Identifier hiringItemOverride;
    @Nullable private Integer hiringCostOverride;
    @Nullable private Float equipmentDropChanceOverride;
    @Nullable private Identifier deathLootTable;
    private List<ItemStack> deathDropItems = new ArrayList<>();

    public int nextAnimationTaskGeneration() {
        return ++animationTaskGeneration;
    }

    @Override
    public boolean isCastingSpell() {
        return this.dataTracker.get(CASTING_SPELL) || SpellCasterEntity.super.isCastingSpell();
    }

    
    public boolean isSpellCastBusy() {
        return isCastingSpell()
                || getSpellCastProcess() != null
                || !getCastAnimationId().isEmpty()
                || !getCastHoldAnimationId().isEmpty()
                || !getSwingAnimationId().isEmpty()
                || !getReleaseAnimationId().isEmpty();
    }

    public void setCastingSpell(boolean casting) {
        this.dataTracker.set(CASTING_SPELL, casting);
        if (!casting) {
            this.dataTracker.set(CAST_PROGRESS, 0);
            this.dataTracker.set(CASTING_MELEE_SPELL, false);
            this.dataTracker.set(SYNCED_CAST_SPELL_ID, "");
            
            
            this.setSpellCastProcess(null);
        }
    }

    public void setSyncedCastSpellId(@Nullable Identifier spellId) {
        this.dataTracker.set(SYNCED_CAST_SPELL_ID, spellId != null ? spellId.toString() : "");
    }

    @Nullable
    public Identifier getSyncedCastSpellId() {
        String raw = this.dataTracker.get(SYNCED_CAST_SPELL_ID);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return Identifier.tryParse(raw);
    }

    public int getAnimationSequence() {
        return this.dataTracker.get(ANIMATION_SEQUENCE);
    }

    public void bumpAnimationSequence() {
        this.dataTracker.set(ANIMATION_SEQUENCE, getAnimationSequence() + 1);
    }

    
    public boolean shouldSuppressVanillaSpellSwing() {
        if (isCastingSpell()) {
            return true;
        }
        return !getCastAnimationId().isEmpty()
                || !getCastHoldAnimationId().isEmpty()
                || !getSwingAnimationId().isEmpty();
    }

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }

    @Override
    public void swingHand(Hand hand) {
        if (shouldSuppressVanillaSpellSwing()) {
            return;
        }
        super.swingHand(hand);
    }

    @Override
    public void swingHand(Hand hand, boolean fromServer) {
        if (shouldSuppressVanillaSpellSwing()) {
            return;
        }
        super.swingHand(hand, fromServer);
    }

    
    public void interruptSpellCast() {
        nextAnimationTaskGeneration();
        bumpAnimationSequence();
        setCastingSpell(false);
        setReleaseAnimationId(null);
        setSwingAnimationId(null);
        setCastAnimationId(null);
        setCastHoldAnimationId(null);
        bumpAnimationSequence();
        stopUsingItem();
        clearChannelCastVisuals();
        setMeleeSkillAttack(null);
        setSpellCastProcess(null);
        setActiveBeam(null);
    }

    

    public boolean isCastingMeleeSpell() {
        return this.dataTracker.get(CASTING_MELEE_SPELL);
    }

    

    public void setCastingMeleeSpell(boolean melee) {
        this.dataTracker.set(CASTING_MELEE_SPELL, melee);
    }

    

    public String getCastAnimationId() {
        return this.dataTracker.get(CAST_ANIMATION_ID);
    }

    public void setCastAnimationId(String animationId) {
        this.dataTracker.set(CAST_ANIMATION_ID, animationId != null ? animationId : "");
    }

    public String getCastHoldAnimationId() {
        return this.dataTracker.get(CAST_HOLD_ANIMATION_ID);
    }

    public void setCastHoldAnimationId(@Nullable String animationId) {
        this.dataTracker.set(CAST_HOLD_ANIMATION_ID, animationId != null ? animationId : "");
    }

    

    public String getReleaseAnimationId() {
        return this.dataTracker.get(RELEASE_ANIMATION_ID);
    }

    public void setReleaseAnimationId(String animationId) {
        this.dataTracker.set(RELEASE_ANIMATION_ID, animationId != null ? animationId : "");
    }

    public String getSwingAnimationId() {
        return this.dataTracker.get(SWING_ANIMATION_ID);
    }

    public void setSwingAnimationId(String animationId) {
        this.dataTracker.set(SWING_ANIMATION_ID, animationId != null ? animationId : "");
    }

    public float getSwingAnimationSpeed() {
        return this.dataTracker.get(SWING_ANIMATION_SPEED);
    }

    public void setSwingAnimationSpeed(float speed) {
        this.dataTracker.set(SWING_ANIMATION_SPEED, speed > 0 ? speed : 1f);
    }

    public float getCastAnimationSpin() {
        return this.dataTracker.get(CAST_ANIMATION_SPIN);
    }

    public long getCastStartedAt() {
        return this.dataTracker.get(CAST_STARTED_AT);
    }

    public float getCastChannelInterval() {
        int lengthTicks = this.dataTracker.get(CAST_LENGTH_TICKS);
        int channelTicks = this.dataTracker.get(CAST_CHANNEL_TICKS);
        return channelTicks > 0 ? lengthTicks / (float) channelTicks : lengthTicks;
    }

    public int getCastChannelTickCount() {
        return this.dataTracker.get(CAST_CHANNEL_TICKS);
    }

    public int getCastLengthTicks() {
        return this.dataTracker.get(CAST_LENGTH_TICKS);
    }

    public float getCastAnimationSpeed() {
        return this.dataTracker.get(CAST_ANIMATION_SPEED);
    }

    public void setCastAnimationSpeed(float speed) {
        this.dataTracker.set(CAST_ANIMATION_SPEED, speed > 0 ? speed : 1f);
    }

    public boolean getCastAnimationPitch() {
        return this.dataTracker.get(CAST_ANIMATION_PITCH);
    }

    

    public void setChannelCastVisuals(float animationSpin, long worldTime, int lengthTicks,
                                      int channelTickCount, float animationSpeed, boolean animationPitch) {
        this.dataTracker.set(CAST_ANIMATION_SPIN, animationSpin);
        this.dataTracker.set(CAST_STARTED_AT, worldTime);
        this.dataTracker.set(CAST_LENGTH_TICKS, lengthTicks);
        this.dataTracker.set(CAST_CHANNEL_TICKS, channelTickCount);
        this.dataTracker.set(CAST_ANIMATION_SPEED, animationSpeed > 0 ? animationSpeed : 1f);
        this.dataTracker.set(CAST_ANIMATION_PITCH, animationPitch);
    }

    public void clearChannelCastVisuals() {
        this.dataTracker.set(CAST_ANIMATION_SPIN, 0f);
        this.dataTracker.set(CAST_STARTED_AT, 0L);
        this.dataTracker.set(CAST_LENGTH_TICKS, 0);
        this.dataTracker.set(CAST_CHANNEL_TICKS, 0);
        this.dataTracker.set(CAST_ANIMATION_SPEED, 1f);
        this.dataTracker.set(CAST_ANIMATION_PITCH, true);
    }

    

    public int getCastProgress() {
        return this.dataTracker.get(CAST_PROGRESS);
    }

    

    public void setCastProgress(int progress) {
        this.dataTracker.set(CAST_PROGRESS, Math.max(0, Math.min(100, progress)));
    }

    

    public int getSpellSwingTicks() {
        return this.dataTracker.get(SPELL_SWING_TICKS);
    }

    

    public void triggerSpellSwing(int duration) {
        this.dataTracker.set(SPELL_SWING_TICKS, duration);
    }

    

    public void tickSpellSwing() {
        int current = this.dataTracker.get(SPELL_SWING_TICKS);
        if (current > 0) {
            this.dataTracker.set(SPELL_SWING_TICKS, current - 1);
        }
    }

    public GuardEntity(EntityType<? extends GuardEntity> type, World world) {
        super(type, world);
        this.guardInventory.addListener(this);
        this.setPersistent();
        this.cooldownManager = new SpellCooldownManager(null);
        if (GuardVillagersConfig.guardEntitysOpenDoors)
            ((MobNavigation) this.getNavigation()).setCanPathThroughDoors(true);
    }

    
    @Override
    public SpellCooldownManager getCooldownManager() {
        if (this.cooldownManager == null) {
            this.cooldownManager = new SpellCooldownManager(null);
        }
        return this.cooldownManager;
    }

    @Override
    public void setChannelTickIndex(int index) {
        this.channelTickIndex = index;
    }

    @Override
    public int getChannelTickIndex() {
        return this.channelTickIndex;
    }

    @Override
    public void setSpellCastProcess(@Nullable SpellCast.Process process) {
        this.spellCastProcess = process;
    }

    @Nullable
    @Override
    public SpellCast.Process getSpellCastProcess() {
        return this.spellCastProcess;
    }

    @Override
    public Spell getCurrentSpell() {
        if (this.spellCastProcess != null && this.spellCastProcess.spell() != null) {
            return this.spellCastProcess.spell().value();
        }
        return null;
    }

    @Override
    public float getCurrentCastingSpeed() {
        if (this.spellCastProcess != null) {
            return this.spellCastProcess.speed();
        }
        return 1.0F;
    }

    @Override
    public void setArrowShootContext(ArrowShootContext context) {
        this.arrowShootContext = context;
    }

    @Override
    public ArrowShootContext getArrowShootContext() {
        return this.arrowShootContext;
    }
    private static final TrackedData<Boolean> IS_BEAMING = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<NbtCompound> BEAM_DATA = DataTracker.registerData(GuardEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
    @Nullable
    private Spell.Target.Beam cachedBeam = null;

    public void setActiveBeam(@Nullable Spell.Target.Beam beam) {
        this.cachedBeam = beam;

        if (!getWorld().isClient()) {
            this.dataTracker.set(IS_BEAMING, beam != null);

            if (beam != null) {
                NbtCompound beamNbt = new NbtCompound();
                beamNbt.putString("texture_id", beam.texture_id);
                beamNbt.putLong("color_rgba", beam.color_rgba);
                beamNbt.putLong("inner_color_rgba", beam.inner_color_rgba);
                beamNbt.putFloat("width", beam.width);
                beamNbt.putFloat("flow", beam.flow);
                beamNbt.putString("luminance", beam.luminance.name());
                this.dataTracker.set(BEAM_DATA, beamNbt);
            } else {
                this.dataTracker.set(BEAM_DATA, new NbtCompound());
            }
        }
    }

    @Override
    public boolean isBeaming() {
        return this.dataTracker.get(IS_BEAMING) && this.isCastingSpell();
    }

    @Nullable
    @Override
    public Spell.Target.Beam getBeam() {
        if (!this.dataTracker.get(IS_BEAMING)) {
            return null;
        }

        
        if (getWorld().isClient() && cachedBeam == null) {
            NbtCompound beamNbt = this.dataTracker.get(BEAM_DATA);
            if (!beamNbt.isEmpty()) {
                Spell.Target.Beam beam = new Spell.Target.Beam();
                beam.texture_id = beamNbt.getString("texture_id");
                beam.color_rgba = beamNbt.getLong("color_rgba");
                beam.inner_color_rgba = beamNbt.getLong("inner_color_rgba");
                beam.width = beamNbt.getFloat("width");
                beam.flow = beamNbt.getFloat("flow");
                beam.luminance = Spell.Target.Beam.Luminance.valueOf(beamNbt.getString("luminance"));
                cachedBeam = beam;
            }
        }

        return cachedBeam;
    }

    @Override
    public void setMeleeSkillAttack(Melee.ActiveAttack attack) {
        this.meleeSkillAttack = attack;
    }

    @Override
    public void setActiveMeleeSkill(@Nullable RegistryEntry<Spell> spell) {
        this.activeMeleeSkill = spell;
    }

    @Override
    @Nullable
    public RegistryEntry<Spell> getActiveMeleeSkill() {
        return this.activeMeleeSkill;
    }

    @Override
    public float getExtraSlipperiness() {
        if (this.meleeSkillAttack != null) {
            return this.meleeSkillAttack.attack.movement_slip();
        }
        return 0.0f;
    }

    public GuardSpellManager getSpellManager() {
        return spellManager;
    }

    public boolean hasSpellSources() {
        return !spellManager.getAllActiveSpells().isEmpty() || !spellManager.getAllPassiveSpells().isEmpty();
    }

    public static int slotToInventoryIndex(EquipmentSlot slot) {
        return switch (slot) {
            case CHEST -> 1;
            case FEET -> 3;
            case LEGS -> 2;
            default -> 0;
        };
    }

    public ItemStack getSpellSlotStack() {
        return this.guardInventory.getStack(SPELL_SLOT_INDEX);
    }

    public void setSpellSlotStack(ItemStack stack) {
        this.guardInventory.setStack(SPELL_SLOT_INDEX, stack);
    }

    public static int getRandomTypeForBiome(WorldAccess world, BlockPos pos) {
        VillagerType type = VillagerType.forBiome(world.getBiome(pos));
        if (type == VillagerType.SNOW) return 6;
        else if (type == VillagerType.TAIGA) return 5;
        else if (type == VillagerType.JUNGLE) return 4;
        else if (type == VillagerType.SWAMP) return 3;
        else if (type == VillagerType.SAVANNA) return 2;
        else if (type == VillagerType.DESERT) return 1;
        else return 0;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        
        double health = GuardVillagersConfig.healthModifier > 0 ? GuardVillagersConfig.healthModifier : 20.0D;
        double speed = GuardVillagersConfig.speedModifier > 0 ? GuardVillagersConfig.speedModifier : 0.5D;
        double followRange = GuardVillagersConfig.followRangeModifier > 0 ? GuardVillagersConfig.followRangeModifier : 20.0D;
        
        var builder = MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, health)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, speed)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, followRange);

        try {
            for (var school : net.spell_power.api.SpellSchools.all()) {
                if (school.attributeEntry != null) {
                    builder.add(school.attributeEntry, school.attributeBaseValue());
                }
            }

            var critChanceId = Identifier.of("critical_strike", "chance");
            var critDamageId = Identifier.of("critical_strike", "damage");

            Registries.ATTRIBUTE.getEntry(critChanceId).ifPresent(attr -> builder.add(attr, 105));
            Registries.ATTRIBUTE.getEntry(critDamageId).ifPresent(attr -> builder.add(attr, 150));

        } catch (Exception e) {
            GuardVillagers.LOGGER.error("Error adding spell power attributes to GuardEntity", e);
        }

        return builder;
    }

    @Nullable
    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, @Nullable EntityData entityData) {
        this.setPersistent();
        int type = GuardEntity.getRandomTypeForBiome(world, this.getBlockPos());
        if (entityData instanceof GuardEntity.GuardEntityData) {
            type = ((GuardEntity.GuardEntityData) entityData).variantData;
            entityData = new GuardEntity.GuardEntityData(type);
        }

        this.setGuardEntityVariant(type);
        Random random = world.getRandom();
        this.initEquipment(random, difficulty);
        return super.initialize(world, difficulty, spawnReason, entityData);
    }

    @Override
    protected void pushAway(Entity entity) {
        if (entity instanceof PathAwareEntity living) {
            boolean attackTargets = living.getTarget() instanceof VillagerEntity || living.getTarget() instanceof IronGolemEntity || living.getTarget() instanceof GuardEntity;
            if (attackTargets) this.setTarget(living);
        }
        super.pushAway(entity);
    }

    @Nullable
    public BlockPos getPatrolPos() {
        return this.dataTracker.get(GUARD_POS).orElse(null);
    }

    @Nullable
    public void setPatrolPos(BlockPos position) {
        this.dataTracker.set(GUARD_POS, Optional.ofNullable(position));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return GuardVillagers.GUARD_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
        if (this.isBlocking()) {
            return SoundEvents.ITEM_SHIELD_BLOCK;
        } else {
            return GuardVillagers.GUARD_HURT;
        }
    }

    @Override
    protected SoundEvent getDeathSound() {
        return GuardVillagers.GUARD_DEATH;
    }

    @Override
    protected void dropEquipment(ServerWorld world, DamageSource source, boolean causedByPlayer) {
        if (this.isHired()) {
            this.dropAllHiredGear();
            return;
        }
        for (int i = 0; i < this.guardInventory.size(); ++i) {
            ItemStack itemstack = this.guardInventory.getStack(i);
            Random random = getWorld().getRandom();
            float dropChance = this.equipmentDropChanceOverride != null
                    ? this.equipmentDropChanceOverride
                    : GuardEffectiveConfig.chanceToDropEquipment(this);
            if (!itemstack.isEmpty() && !EnchantmentHelper.hasAnyEnchantmentsWith(itemstack, EnchantmentEffectComponentTypes.PREVENT_EQUIPMENT_DROP) && random.nextFloat() < dropChance) {
                this.dropStack(itemstack);
            }
        }
    }

    private void dropAllHiredGear() {
        for (int i = 0; i < this.guardInventory.size(); ++i) {
            ItemStack stack = this.guardInventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            this.dropStack(stack.copy());
            this.guardInventory.setStack(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.equipStack(slot, ItemStack.EMPTY);
        }
    }

    private void protectHiredEquipment() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setEquipmentDropChance(slot, 0.0F);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Hired") && !nbt.getBoolean("Hired") && isTamed()) {
            hotvFollowerId = getOwnerUuid();
            setOwnerUuid(null);
            setTamed(false, false);
        }
        this.setGuardEntityVariant(nbt.getInt("Type"));
        this.kickTicks = nbt.getInt("KickTicks");
        this.setFollowing(nbt.getBoolean("Following"));
        this.interacting = nbt.getBoolean("Interacting");
        this.setPatrolling(nbt.getBoolean("Patrolling"));
        this.shieldCoolDown = nbt.getInt("ShieldCooldown");
        this.kickCoolDown = nbt.getInt("KickCooldown");
        this.lastGossipDecayTime = nbt.getLong("LastGossipDecay");
        this.lastGossipTime = nbt.getLong("LastGossipTime");
        this.spawnWithArmor = nbt.getBoolean("SpawnWithArmor");
        if (nbt.contains("SpecialGuardType")) {
            this.specialGuardType = Identifier.tryParse(nbt.getString("SpecialGuardType"));
        }
        this.treatAsHeroOfTheVillage = nbt.getBoolean("TreatAsHeroOfTheVillage");
        this.hireableOverride = nbt.contains("HireableOverride") ? nbt.getBoolean("HireableOverride") : null;
        this.followHeroOverride = nbt.contains("FollowHeroOverride") ? nbt.getBoolean("FollowHeroOverride") : null;
        this.skipLootTables = nbt.getBoolean("SkipLootTables");
        this.applyEquipmentOverridesAfterLoot = nbt.getBoolean("ApplyEquipmentOverridesAfterLoot");
        this.blockGui = nbt.getBoolean("BlockGui");
        this.lockEquipment = nbt.getBoolean("LockEquipment");
        this.immutableEquipment = nbt.getBoolean("ImmutableEquipment");
        this.attackPlayers = nbt.getBoolean("AttackPlayers");
        if (nbt.contains("AttackMobs")) {
            List<Identifier> mobs = new ArrayList<>();
            for (NbtElement el : nbt.getList("AttackMobs", NbtElement.STRING_TYPE)) {
                Identifier id = Identifier.tryParse(el.asString());
                if (id != null) mobs.add(id);
            }
            this.attackMobs = mobs;
        } else {
            this.attackMobs = List.of();
        }
        this.hiringItemOverride = nbt.contains("HiringItemOverride")
                ? Identifier.tryParse(nbt.getString("HiringItemOverride")) : null;
        this.hiringCostOverride = nbt.contains("HiringCostOverride") ? nbt.getInt("HiringCostOverride") : null;
        this.equipmentDropChanceOverride = nbt.contains("EquipmentDropChanceOverride")
                ? nbt.getFloat("EquipmentDropChanceOverride")
                : null;
        this.deathLootTable = nbt.contains("DeathLootTable")
                ? Identifier.tryParse(nbt.getString("DeathLootTable"))
                : null;
        this.deathDropItems = new ArrayList<>();
        if (nbt.contains("DeathDropItems")) {
            NbtList deathDropsNbt = nbt.getList("DeathDropItems", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < deathDropsNbt.size(); i++) {
                ItemStack.fromNbt(this.getRegistryManager(), deathDropsNbt.getCompound(i))
                        .ifPresent(this.deathDropItems::add);
            }
        }
        this.specialEntityData = nbt.contains("SpecialEntityData") ? nbt.getCompound("SpecialEntityData") : new NbtCompound();
        this.configOverrides = readConfigOverridesFromNbt(nbt);
        if (nbt.contains("BuffPriority")) {
            try { this.buffPriority = GuardVillagersConfig.SupportBuffPriority.valueOf(nbt.getString("BuffPriority")); }
            catch (IllegalArgumentException ignored) { this.buffPriority = GuardVillagersConfig.SupportBuffPriority.OWNER; }
        }
        if (nbt.contains("FollowFormation")) {
            try { this.followFormation = GuardVillagersConfig.FollowFormation.valueOf(nbt.getString("FollowFormation")); }
            catch (IllegalArgumentException ignored) { this.followFormation = GuardVillagersConfig.FollowFormation.FREE; }
        }

        if (nbt.contains("PatrolPosX")) {
            int x = nbt.getInt("PatrolPosX");
            int y = nbt.getInt("PatrolPosY");
            int z = nbt.getInt("PatrolPosZ");
            this.dataTracker.set(GUARD_POS, Optional.ofNullable(new BlockPos(x, y, z)));
        }

        NbtList listtag = nbt.getList("Gossips", 10);
        this.gossips.deserialize(new Dynamic<>(NbtOps.INSTANCE, listtag));

        NbtList listnbt = nbt.getList("Inventory", 10);
        for (int i = 0; i < listnbt.size(); ++i) {
            NbtCompound nbtnbt = listnbt.getCompound(i);
            int slot = nbtnbt.getByte("Slot") & 255;

            if (slot >= 0 && slot < this.guardInventory.size()) {
                ItemStack stack = ItemStack.fromNbt(this.getRegistryManager(), nbtnbt).orElse(ItemStack.EMPTY);
                this.guardInventory.setStack(slot, stack);

                if (slot <= 3) {
                    EquipmentSlot equipSlot = switch(slot) {
                        case 0 -> EquipmentSlot.HEAD;
                        case 1 -> EquipmentSlot.CHEST;
                        case 2 -> EquipmentSlot.LEGS;
                        case 3 -> EquipmentSlot.FEET;
                        default -> null;
                    };
                    if (equipSlot != null) {
                        this.equipStack(equipSlot, stack);
                    }
                } else if (slot == 4) {
                    this.equipStack(EquipmentSlot.OFFHAND, stack);
                } else if (slot == 5) {
                    this.equipStack(EquipmentSlot.MAINHAND, stack);
                }
            }
        }

        if (! getWorld().isClient) {
            this.readAngerFromNbt(getWorld(), nbt);
        }
        if (this.isHired()) {
            this.protectHiredEquipment();
        }
        this.getCooldownManager().readCustomDataFromNbt(nbt);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Type", this.getGuardEntityVariant());
        nbt.putInt("KickTicks", this.kickTicks);
        nbt.putInt("ShieldCooldown", this.shieldCoolDown);
        nbt.putInt("KickCooldown", this.kickCoolDown);
        nbt.putBoolean("Following", this.isFollowing());
        nbt.putBoolean("Interacting", this.interacting);
        nbt.putBoolean("Patrolling", this.isPatrolling());
        nbt.putBoolean("SpawnWithArmor", this.spawnWithArmor);
        if (this.specialGuardType != null) {
            nbt.putString("SpecialGuardType", this.specialGuardType.toString());
        }
        nbt.putBoolean("TreatAsHeroOfTheVillage", this.treatAsHeroOfTheVillage);
        if (this.hireableOverride != null) {
            nbt.putBoolean("HireableOverride", this.hireableOverride);
        }
        if (this.followHeroOverride != null) {
            nbt.putBoolean("FollowHeroOverride", this.followHeroOverride);
        }
        nbt.putBoolean("SkipLootTables", this.skipLootTables);
        nbt.putBoolean("ApplyEquipmentOverridesAfterLoot", this.applyEquipmentOverridesAfterLoot);
        nbt.putBoolean("BlockGui", this.blockGui);
        nbt.putBoolean("LockEquipment", this.lockEquipment);
        nbt.putBoolean("ImmutableEquipment", this.immutableEquipment);
        nbt.putBoolean("AttackPlayers", this.attackPlayers);
        if (!this.attackMobs.isEmpty()) {
            NbtList attackMobsNbt = new NbtList();
            for (Identifier id : this.attackMobs) attackMobsNbt.add(NbtString.of(id.toString()));
            nbt.put("AttackMobs", attackMobsNbt);
        }
        if (this.hiringItemOverride != null) {
            nbt.putString("HiringItemOverride", this.hiringItemOverride.toString());
        }
        if (this.hiringCostOverride != null) {
            nbt.putInt("HiringCostOverride", this.hiringCostOverride);
        }
        if (this.equipmentDropChanceOverride != null) {
            nbt.putFloat("EquipmentDropChanceOverride", this.equipmentDropChanceOverride);
        }
        if (this.deathLootTable != null) {
            nbt.putString("DeathLootTable", this.deathLootTable.toString());
        }
        if (!this.deathDropItems.isEmpty()) {
            NbtList deathDropsNbt = new NbtList();
            for (ItemStack stack : this.deathDropItems) {
                NbtCompound stackNbt = new NbtCompound();
                deathDropsNbt.add(stack.encode(this.getRegistryManager(), stackNbt));
            }
            nbt.put("DeathDropItems", deathDropsNbt);
        }
        if (!this.specialEntityData.isEmpty()) {
            nbt.put("SpecialEntityData", this.specialEntityData.copy());
        }
        if (!this.configOverrides.isEmpty()) {
            nbt.putString("ConfigOverrides", writeConfigOverridesJson(this.configOverrides));
        }
        nbt.putString("BuffPriority", this.buffPriority.name());
        nbt.putString("FollowFormation", this.followFormation.name());
        nbt.putLong("LastGossipTime", this.lastGossipTime);
        nbt.putLong("LastGossipDecay", this.lastGossipDecayTime);

        NbtList listnbt = new NbtList();
        for (int i = 0; i < this.guardInventory.size(); ++i) {
            ItemStack itemstack = this.guardInventory.getStack(i);
            if (!itemstack.isEmpty()) {
                NbtCompound nbtnbt = new NbtCompound();
                nbtnbt.putByte("Slot", (byte) i);
                listnbt.add(itemstack.encode(this.getRegistryManager(), nbtnbt));
            }
        }
        nbt.put("Inventory", listnbt);

        if (this.getPatrolPos() != null) {
            nbt.putInt("PatrolPosX", this.getPatrolPos().getX());
            nbt.putInt("PatrolPosY", this.getPatrolPos().getY());
            nbt.putInt("PatrolPosZ", this.getPatrolPos().getZ());
        }

        nbt.put("Gossips", this.gossips.serialize(NbtOps.INSTANCE));
        this.writeAngerToNbt(nbt);
        this.getCooldownManager().writeCustomDataToNbt(nbt);
    }

    @Override
    protected void consumeItem() {
        if (this.isUsingItem()) {
            Hand hand = this.getActiveHand();
            if (!this.activeItemStack.equals(this.getStackInHand(hand))) {
                this.stopUsingItem();
            } else {
                if (!this.activeItemStack.isEmpty() && this.isUsingItem()) {
                    this.spawnConsumptionEffects(this.activeItemStack, 16);
                    ItemStack itemStack = this.activeItemStack.finishUsing(this.getWorld(), this);
                    if (itemStack != this.activeItemStack) {
                        this.setStackInHand(hand, itemStack);
                    }
                    if (!(this.activeItemStack.getUseAction() == UseAction.EAT)) this.activeItemStack.decrement(1);
                    this.stopUsingItem();
                }
            }
        }
    }

    private void maybeDecayGossip() {
        long i = getWorld().getTime();
        if (this.lastGossipDecayTime == 0L) {
            this.lastGossipDecayTime = i;
        } else if (i >= this.lastGossipDecayTime + 24000L) {
            this.gossips.decay();
            this.lastGossipDecayTime = i;
        }
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        if (this.guardInventory == null) {
            return ItemStack.EMPTY;
        }
        switch (slot) {
            case HEAD:
                return this.guardInventory.getStack(0);
            case CHEST:
                return this.guardInventory.getStack(1);
            case LEGS:
                return this.guardInventory.getStack(2);
            case FEET:
                return this.guardInventory.getStack(3);
            case OFFHAND:
                return this.guardInventory.getStack(4);
            case MAINHAND:
                return this.guardInventory.getStack(5);
            default:
                return ItemStack.EMPTY;
        }
    }

    public VillagerGossips getGossips() {
        return this.gossips;
    }

    public int getPlayerEntityReputation(PlayerEntity player) {
        return this.gossips.getReputationFor(player.getUuid(), (gossipType) -> true);
    }

    public boolean grantsHeroOfTheVillageInteractions() {
        return treatAsHeroOfTheVillage;
    }

    public boolean playerHasHeroInteractionAccess(PlayerEntity player) {
        return player.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) || grantsHeroOfTheVillageInteractions();
    }

    @Nullable
    public Identifier getSpecialGuardType() {
        return specialGuardType;
    }

    public void setSpecialGuardType(@Nullable Identifier specialGuardType) {
        this.specialGuardType = specialGuardType;
    }

    public Map<String, JsonElement> getConfigOverrides() {
        return configOverrides;
    }

    public void setConfigOverrides(Map<String, JsonElement> configOverrides) {
        this.configOverrides = configOverrides != null ? Map.copyOf(configOverrides) : Map.of();
    }

    public NbtCompound getSpecialEntityData() {
        return specialEntityData;
    }

    public void setTreatAsHeroOfTheVillage(boolean treatAsHeroOfTheVillage) {
        this.treatAsHeroOfTheVillage = treatAsHeroOfTheVillage;
    }

    @Nullable
    public Boolean getHireableOverride() {
        return hireableOverride;
    }

    public void setHireableOverride(@Nullable Boolean hireableOverride) {
        this.hireableOverride = hireableOverride;
    }

    @Nullable
    public Boolean getFollowHeroOverride() {
        return followHeroOverride;
    }

    public void setFollowHeroOverride(@Nullable Boolean followHeroOverride) {
        this.followHeroOverride = followHeroOverride;
    }

    public void setSkipLootTables(boolean skipLootTables) {
        this.skipLootTables = skipLootTables;
    }

    public void setApplyEquipmentOverridesAfterLoot(boolean applyEquipmentOverridesAfterLoot) {
        this.applyEquipmentOverridesAfterLoot = applyEquipmentOverridesAfterLoot;
    }

    public boolean blocksGuardGui() {
        return blockGui;
    }

    public void setBlockGui(boolean blockGui) {
        this.blockGui = blockGui;
    }

    public boolean isEquipmentLocked() {
        return lockEquipment;
    }

    public void setLockEquipment(boolean lockEquipment) {
        this.lockEquipment = lockEquipment;
    }

    public boolean isEquipmentImmutable() {
        return immutableEquipment;
    }

    public void setImmutableEquipment(boolean immutableEquipment) {
        this.immutableEquipment = immutableEquipment;
    }

    public boolean shouldAttackPlayers() {
        return attackPlayers;
    }

    public void setAttackPlayers(boolean attackPlayers) {
        this.attackPlayers = attackPlayers;
    }

    public List<Identifier> getAttackMobs() {
        return attackMobs;
    }

    public void setAttackMobs(List<Identifier> attackMobs) {
        this.attackMobs = attackMobs;
    }

    @Nullable
    public Identifier getHiringItemOverride() {
        return hiringItemOverride;
    }

    public void setHiringItemOverride(@Nullable Identifier hiringItemOverride) {
        this.hiringItemOverride = hiringItemOverride;
    }

    @Nullable
    public Integer getHiringCostOverride() {
        return hiringCostOverride;
    }

    public void setHiringCostOverride(@Nullable Integer hiringCostOverride) {
        this.hiringCostOverride = hiringCostOverride;
    }

    public void setEquipmentDropChanceOverride(@Nullable Float equipmentDropChanceOverride) {
        this.equipmentDropChanceOverride = equipmentDropChanceOverride;
    }

    public void setDeathLootTable(@Nullable Identifier deathLootTable) {
        this.deathLootTable = deathLootTable;
    }

    public void setDeathDropItems(List<ItemStack> items) {
        this.deathDropItems = new ArrayList<>(items);
    }

    private void dropSpecialDeathLoot(ServerWorld world, DamageSource damageSource) {
        if (this.isHired()) {
            return;
        }
        if (this.deathLootTable != null) {
            LootTable lootTable = world.getServer().getReloadableRegistries().getLootTable(
                    RegistryKey.of(RegistryKeys.LOOT_TABLE, this.deathLootTable));
            LootContextParameterSet.Builder builder = new LootContextParameterSet.Builder(world)
                    .add(LootContextParameters.THIS_ENTITY, this)
                    .add(LootContextParameters.ORIGIN, this.getPos())
                    .add(LootContextParameters.DAMAGE_SOURCE, damageSource);
            Entity attacker = damageSource.getAttacker();
            if (attacker != null) {
                builder.add(LootContextParameters.ATTACKING_ENTITY, attacker);
            }
            Entity directAttacker = damageSource.getSource();
            if (directAttacker != null) {
                builder.add(LootContextParameters.DIRECT_ATTACKING_ENTITY, directAttacker);
            }
            for (ItemStack stack : lootTable.generateLoot(builder.build(LootContextTypes.ENTITY))) {
                if (!stack.isEmpty()) {
                    this.dropStack(stack);
                }
            }
        }
        for (ItemStack stack : this.deathDropItems) {
            if (!stack.isEmpty()) {
                this.dropStack(stack.copy());
            }
        }
    }

    public boolean canPlayerOpenGui(PlayerEntity player) {
        if (!blockGui) {
            return true;
        }
        return isHired() && player.getUuid().equals(getOwnerUuid());
    }

    public boolean canPlayerUseHeroControls(PlayerEntity player) {
        if (blockGui) {
            return isHired() && player.getUuid().equals(getOwnerUuid());
        }
        return true;
    }

    private static Map<String, JsonElement> readConfigOverridesFromNbt(NbtCompound nbt) {
        if (!nbt.contains("ConfigOverrides")) {
            return Map.of();
        }
        JsonObject root = JsonParser.parseString(nbt.getString("ConfigOverrides")).getAsJsonObject();
        Map<String, JsonElement> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            overrides.put(entry.getKey(), entry.getValue());
        }
        return overrides;
    }

    private static String writeConfigOverridesJson(Map<String, JsonElement> overrides) {
        JsonObject root = new JsonObject();
        overrides.forEach(root::add);
        return root.toString();
    }

    @Nullable
    public LivingEntity getOwner() {
        try {
            UUID uuid = this.getOwnerId();
            if (uuid == null) return null;
            PlayerEntity player = getWorld().getPlayerByUuid(uuid);
            if (player == null) return null;
            if (this.isHired()) return player;
            if (blockGui) return null;
            if (GuardEffectiveConfig.followHero(this) && !playerHasHeroInteractionAccess(player)) return null;
            return player;
        } catch (IllegalArgumentException illegalargumentexception) {
            return null;
        }
    }

    public boolean isOwner(LivingEntity entityIn) {
        return entityIn == this.getOwner();
    }

    @Nullable
    public UUID getOwnerId() {
        return isTamed() ? getOwnerUuid() : hotvFollowerId;
    }

    public void setOwnerId(@Nullable UUID uuid) {
        if (isTamed()) setOwnerUuid(uuid);
        else hotvFollowerId = uuid;
    }

    public boolean trySpellMeleeWeaponHit(Entity target) {
        ((dev.sterner.guardvillagers.mixin.accessor.LivingEntityAccessor) this).guardvillagers$setLastAttackedTicks(0);
        return tryAttack(target);
    }

    public boolean tryAttack(Entity target) {
        if (this.isKicking()) {
            if (target instanceof LivingEntity livingTarget) {
                livingTarget.takeKnockback(
                        1.0F,
                        MathHelper.sin(this.getYaw() * ((float) Math.PI / 180F)),
                        -MathHelper.cos(this.getYaw() * ((float) Math.PI / 180F))
                );
            }
            this.kickTicks = 10;
            this.getWorld().sendEntityStatus(this, (byte) 4);
            this.lookAtEntity(target, 90.0F, 90.0F);
        }

        ItemStack hand = this.getMainHandStack();
        hand.damage(1, this, EquipmentSlot.MAINHAND);

        boolean success = super.tryAttack(target);

        if (success && target instanceof LivingEntity livingTarget) {
            meleeSpellHandler.triggerOnHitSpells(livingTarget);
        }

        return success;
    }

    public MeleeSpellHandler getMeleeSpellHandler() {
        return meleeSpellHandler;
    }

    @Override
    public void handleStatus(byte status) {
        if (status == 4) {
            this.kickTicks = 10;
        } else {
            super.handleStatus(status);
        }
    }

    @Override
    public boolean isImmobile() {
        return this.interacting || super.isImmobile();
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        if (getWorld() instanceof ServerWorld serverWorld) {
            dropSpecialDeathLoot(serverWorld, damageSource);
        }
        if ((getWorld().getDifficulty() == Difficulty.NORMAL || getWorld().getDifficulty() == Difficulty.HARD) && damageSource.getAttacker() instanceof ZombieEntity) {
            ZombieVillagerEntity zombieguard = this.convertTo(EntityType.ZOMBIE_VILLAGER, true);
            if (getWorld().getDifficulty() != Difficulty.HARD && this.random.nextBoolean() || zombieguard == null) {
                return;
            }
            zombieguard.initialize((ServerWorldAccess) getWorld(), getWorld().getLocalDifficulty(zombieguard.getBlockPos()), SpawnReason.CONVERSION, new ZombieEntity.ZombieData(false, true));
            if (!this.isSilent()) getWorld().syncWorldEvent(null, 1026, this.getBlockPos(), 0);
            this.discard();
        }
        super.onDeath(damageSource);
    }

    @Override
    public SoundEvent getEatSound(ItemStack stack) {
        return super.getEatSound(stack);
    }

    @Override
    public ItemStack eatFood(World world, ItemStack stack, FoodComponent food) {
        this.heal(food.nutrition());
        world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5F, world.random.nextFloat() * 0.1F + 0.9F);
        super.eatFood(world, stack, food);
        return stack;
    }

    @Override
    public void tickMovement() {
        if (this.kickTicks > 0)
            --this.kickTicks;
        if (this.kickCoolDown > 0)
            --this.kickCoolDown;
        if (this.shieldCoolDown > 0)
            --this.shieldCoolDown;
        if (this.getHealth() < this.getMaxHealth() && this.age % 200 == 0) {
            this.heal(GuardEffectiveConfig.amountOfHealthRegenerated(this));
        }
        if (spawnWithArmor && this.getWorld() instanceof ServerWorld serverWorld) {
            if (!skipLootTables) {
                for (EquipmentSlot equipmentslottype : EquipmentSlot.values()) {
                    for (ItemStack stack : this.getStacksFromLootTable(equipmentslottype, serverWorld)) {
                        this.equipStack(equipmentslottype, stack);
                    }
                }
                this.applyThemedArmorFromMainHand(serverWorld);
                this.rollSpellSlotLoot(serverWorld);
            }
            if (applyEquipmentOverridesAfterLoot && specialGuardType != null) {
                SpecialGuardRegistry.INSTANCE.get(specialGuardType).ifPresent(definition ->
                        SpecialGuardApplicator.applyEquipmentOverrides(this, definition, serverWorld));
                applyEquipmentOverridesAfterLoot = false;
            }
            this.spellCastGraceTicks = 40;
            this.spawnWithArmor = false;
        }
        if (!getWorld().isClient) this.tickAngerLogic((ServerWorld) getWorld(), true);
        this.tickHandSwing();
        super.tickMovement();
    }

    @Override
    public void tick() {
        this.maybeDecayGossip();
        super.tick();

        if (this.meleeSkillAttack != null && this.meleeSkillAttack.isFinished(this.age)) {
            this.meleeSkillAttack = null;
        }

        
        tickSpellSwing();

        if (!this.getWorld().isClient) {
            LivingEntity target = this.getTarget();
            if (target != null && !target.isAlive()) {
                this.setTarget(null);
            } else if (target != null && !this.isCastingSpell() && !this.isCastingMeleeSpell() && !this.canTarget(target)) {
                this.setTarget(null);
            }
            if (this.spellCastGraceTicks > 0) {
                this.spellCastGraceTicks--;
            }
            getSpellManager().refresh();
        }

        if (!this.getWorld().isClient) {
            tickSpellCooldowns();
            tickDelayedTasks();
            GuardDebugManager.tickGoalDebug(this);
            GuardDebugManager.tickAnimationDebug(this);
        }
    }

    private void tickDelayedTasks() {
        if (delayedTasks.isEmpty()) {
            return;
        }
        int pending = delayedTasks.size();
        for (int i = 0; i < pending; i++) {
            Pair<Integer, Runnable> task = delayedTasks.poll();
            if (task == null) {
                break;
            }
            int ticksLeft = task.getFirst() - 1;
            if (ticksLeft <= 0) {
                task.getSecond().run();
            } else {
                delayedTasks.add(new Pair<>(ticksLeft, task.getSecond()));
            }
        }
    }

    public void tickSpellCooldowns() {
        this.getCooldownManager().tickUpdate();
    }

    public boolean isSpellOnCooldown(Identifier spellId) {
        return net.spell_engine.api.spell.registry.SpellRegistry.from(this.getWorld())
                .getEntry(spellId)
                .map(entry -> this.getCooldownManager().isCoolingDown(entry))
                .orElseGet(() -> this.getCooldownManager().spellsOnCooldown().contains(spellId));
    }

    public int getSpellCooldownTicks(Identifier spellId) {
        return net.spell_engine.api.spell.registry.SpellRegistry.from(this.getWorld())
                .getEntry(spellId)
                .map(entry -> this.getCooldownManager().getCooldownDuration(entry))
                .orElse(0);
    }

    public void setSpellCooldown(Identifier spellId, int ticks) {
        if (ticks <= 0) {
            this.clearSpellCooldown(spellId);
            return;
        }
        net.spell_engine.api.spell.registry.SpellRegistry.from(this.getWorld())
                .getEntry(spellId)
                .ifPresent(entry -> this.getCooldownManager().set(entry, ticks, true));
    }

    public void clearSpellCooldown(Identifier spellId) {
        SpellCooldownManager manager = this.getCooldownManager();
        manager.remove(spellId);
        net.spell_engine.api.spell.registry.SpellRegistry.from(this.getWorld())
                .getEntry(spellId)
                .ifPresent(entry -> {
                    Spell spell = entry.value();
                    if (spell.cost != null && spell.cost.cooldown != null && spell.cost.cooldown.group != null) {
                        manager.remove(Identifier.of("group", spell.cost.cooldown.group));
                    }
                });
    }

    public void clearAllSpellCooldowns() {
        this.getCooldownManager().reset(null);
    }

    public Map<Identifier, Integer> getSpellCooldowns() {
        Map<Identifier, Integer> copy = new LinkedHashMap<>();
        for (GuardSpellManager.CategorizedSpell spell : this.spellManager.getAllActiveSpells()) {
            int ticks = this.getSpellCooldownTicks(spell.spellId());
            if (ticks > 0) {
                copy.put(spell.spellId(), ticks);
            }
        }
        for (GuardSpellManager.CategorizedSpell spell : this.spellManager.getAllPassiveSpells()) {
            int ticks = this.getSpellCooldownTicks(spell.spellId());
            if (ticks > 0) {
                copy.put(spell.spellId(), ticks);
            }
        }
        return copy;
    }

    @Override
    public void travel(Vec3d movementInput) {
        if (this.meleeSkillAttack != null && this.isOnGround()) {
            float slip = this.getExtraSlipperiness();
            if (slip > 0) {
                Vec3d forward = this.getRotationVector();
                movementInput = movementInput.add(forward.x * slip, 0.0, forward.z * slip);
            }
        }
        super.travel(movementInput);
    }

    private void applyThemedArmorFromMainHand(ServerWorld serverWorld) {
        ItemStack mainHand = getMainHandStack();
        if (mainHand.isEmpty()) {
            return;
        }
        GuardArmorThemeManager.ResolvedArmorTheme theme = GuardArmorThemeManager.INSTANCE.resolve(mainHand);
        if (theme != null) {
            equipThemedArmor(theme, serverWorld);
        }
    }

    private void equipThemedArmor(GuardArmorThemeManager.ResolvedArmorTheme theme, ServerWorld serverWorld) {
        Registry<Item> itemRegistry = serverWorld.getRegistryManager().get(RegistryKeys.ITEM);
        Map<EquipmentSlot, String> armorSlots = new java.util.LinkedHashMap<>();
        if (theme.head()  != null) armorSlots.put(EquipmentSlot.HEAD,  theme.head());
        if (theme.chest() != null) armorSlots.put(EquipmentSlot.CHEST, theme.chest());
        if (theme.legs()  != null) armorSlots.put(EquipmentSlot.LEGS,  theme.legs());
        if (theme.feet()  != null) armorSlots.put(EquipmentSlot.FEET,  theme.feet());

        for (Map.Entry<EquipmentSlot, String> entry : armorSlots.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            int invIndex = switch (slot) {
                case HEAD -> 0;
                case CHEST -> 1;
                case LEGS -> 2;
                case FEET -> 3;
                default -> -1;
            };

            if (invIndex == -1) continue;

            if (!guardInventory.getStack(invIndex).isEmpty()) {
                Identifier robeId = Identifier.tryParse(entry.getValue());
                if (robeId != null && itemRegistry.containsId(robeId)) {
                    ItemStack robe = new ItemStack(itemRegistry.get(robeId));

                    this.equipStack(slot, robe);
                    this.guardInventory.setStack(invIndex, robe);

                    if (!this.getWorld().isClient()) {
                        ((ServerWorld) this.getWorld()).getChunkManager().sendToNearbyPlayers(
                                this,
                                new EntityEquipmentUpdateS2CPacket(
                                        this.getId(),
                                        List.of(new Pair<>(slot, robe))
                                )
                        );
                    }
                }
            }
        }
    }

    public boolean isWindingUpSpell() {
        return this.isCastingSpell() && this.getActiveItem().isEmpty();
    }

    @Override
    protected EntityDimensions getBaseDimensions(EntityPose pose) {
        return SIZE_BY_POSE.getOrDefault(pose, EntityDimensions.changing(0.6F, 1.95F));
    }

    @Override
    protected void takeShieldHit(LivingEntity entityIn) {
        super.takeShieldHit(entityIn);
        if (entityIn.getMainHandStack().getItem() instanceof AxeItem) this.disableShield(true, entityIn.getMainHandStack().getItem());
    }

    @Override
    public void damageShield(float amount) {
        if (isShield(this.activeItemStack)) {
            
            defensiveSpellHandler.triggerShieldBlockSpells(amount, this.lastShieldBlockAttacker);
            
            if (amount >= 3.0F) {
                int i = 1 + MathHelper.floor(amount);
                Hand hand = this.getActiveHand();
                EquipmentSlot slot = hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                this.activeItemStack.damage(i, this, slot);
                if (this.activeItemStack.isEmpty()) {
                    if (hand == Hand.MAIN_HAND) {
                        this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    } else {
                        this.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                    }
                    this.activeItemStack = ItemStack.EMPTY;
                    this.playSound(SoundEvents.ITEM_SHIELD_BREAK, 0.8F, 0.8F + getWorld().random.nextFloat() * 0.4F);
                }
            }
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        
        if (this.isBlocking() && source.getAttacker() instanceof LivingEntity attacker) {
            this.lastShieldBlockAttacker = attacker;
        }
        
        boolean damaged = super.damage(source, amount);

        this.lastShieldBlockAttacker = null;

        if (damaged && amount > 0) {
            if (!this.isBlocking()) {
                defensiveSpellHandler.triggerDamageTakenSpells(source, amount);
            }
            if (source.getAttacker() instanceof PlayerEntity player) {
                boolean ownerAttack = this.isHired() && player.getUuid().equals(this.getOwnerUuid());
                if (!ownerAttack) {
                    int reputationLoss = GuardEffectiveConfig.reputationLostOnAttack(this);
                    if (reputationLoss > 0) {
                        this.gossips.startGossip(player.getUuid(), VillageGossipType.MAJOR_NEGATIVE, reputationLoss);
                    }
                }
            }
        }

        return damaged;
    }

    @Override
    public void setCurrentHand(Hand hand) {
        super.setCurrentHand(hand);
        ItemStack itemstack = this.getStackInHand(hand);
        if (isShield(itemstack)) {
            EntityAttributeInstance modifiableattributeinstance = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            modifiableattributeinstance.removeModifier(USE_ITEM_SPEED_PENALTY);
            modifiableattributeinstance.addTemporaryModifier(USE_ITEM_SPEED_PENALTY);
        }
    }

    @Override
    public void stopUsingItem() {
        super.stopUsingItem();
        if (this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).hasModifier(USE_ITEM_SPEED_PENALTY.id()))
            this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).removeModifier(USE_ITEM_SPEED_PENALTY);
    }

    public void disableShield(boolean increase, Item item) {
        float chance = 0.25F;
        if (increase) chance += 0.75;
        if (this.random.nextFloat() < chance) {
            this.shieldCoolDown = 100;
            this.stopUsingItem();
            getWorld().sendEntityStatus(this, (byte) 30);
        }
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(GUARD_VARIANT, 0);
        builder.add(DATA_CHARGING_STATE, false);
        builder.add(KICKING, false);
        builder.add(FOLLOWING, false);
        builder.add(GUARD_POS, Optional.empty());
        builder.add(PATROLLING, false);
        builder.add(RUNNING_TO_EAT, false);
        builder.add(CASTING_SPELL, false);
        builder.add(CASTING_MELEE_SPELL, false);
        builder.add(CAST_PROGRESS, 0);
        builder.add(SPELL_SWING_TICKS, 0);
        builder.add(CAST_ANIMATION_ID, "");
        builder.add(CAST_HOLD_ANIMATION_ID, "");
        builder.add(RELEASE_ANIMATION_ID, "");
        builder.add(SWING_ANIMATION_ID, "");
        builder.add(SWING_ANIMATION_SPEED, 1f);
        builder.add(ANIMATION_SEQUENCE, 0);
        builder.add(CAST_ANIMATION_SPIN, 0f);
        builder.add(CAST_STARTED_AT, 0L);
        builder.add(CAST_LENGTH_TICKS, 0);
        builder.add(CAST_CHANNEL_TICKS, 0);
        builder.add(CAST_ANIMATION_SPEED, 1f);
        builder.add(CAST_ANIMATION_PITCH, true);
        builder.add(SYNCED_CAST_SPELL_ID, "");
        builder.add(IS_BEAMING, false);
        builder.add(BEAM_DATA, new NbtCompound());
        super.initDataTracker(builder);
    }

    public boolean isCharging() {
        return this.dataTracker.get(DATA_CHARGING_STATE);
    }

    public void setChargingCrossbowGuard(boolean charging) {
        this.dataTracker.set(DATA_CHARGING_STATE, charging);
    }

    public boolean isKicking() {
        return this.dataTracker.get(KICKING);
    }

    public void setKicking(boolean kicking) {
        this.dataTracker.set(KICKING, kicking);
    }

    @Override
    protected void initEquipment(Random random, LocalDifficulty localDifficulty) {
        this.handDropChances[EquipmentSlot.MAINHAND.getEntitySlotId()] = 100.0F;
        this.handDropChances[EquipmentSlot.OFFHAND.getEntitySlotId()] = 100.0F;
        this.spawnWithArmor = true;
    }

    public List<ItemStack> getStacksFromLootTable(EquipmentSlot slot, ServerWorld serverWorld) {
        if (EQUIPMENT_SLOT_ITEMS.containsKey(slot)) {
            LootTable loot = serverWorld.getServer().getReloadableRegistries().getLootTable(EQUIPMENT_SLOT_ITEMS.get(slot));
            LootContextParameterSet.Builder lootcontext$builder = (new LootContextParameterSet.Builder((ServerWorld) getWorld()).add(LootContextParameters.THIS_ENTITY, this));
            return loot.generateLoot(lootcontext$builder.build(GuardEntityLootTables.SLOT));
        }
        return List.of();
    }

    public void rollSpellSlotLoot(ServerWorld serverWorld) {
        LootTable loot = serverWorld.getServer().getReloadableRegistries().getLootTable(GuardEntityLootTables.GUARD_SPELL_SLOT);
        LootContextParameterSet.Builder builder = new LootContextParameterSet.Builder(serverWorld)
                .add(LootContextParameters.THIS_ENTITY, this);
        List<ItemStack> stacks = loot.generateLoot(builder.build(GuardEntityLootTables.SLOT));
        if (!stacks.isEmpty()) {
            setSpellSlotStack(stacks.getFirst());
            return;
        }

        ItemStack scroll = GuardSpellScrollRoller.tryRoll(this, serverWorld);
        if (scroll != null) {
            setSpellSlotStack(scroll);
        }
    }

    public int getGuardEntityVariant() {
        return this.dataTracker.get(GUARD_VARIANT);
    }

    public void setGuardEntityVariant(int typeId) {
        this.dataTracker.set(GUARD_VARIANT, typeId);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(0, new KickGoal(this));
        this.goalSelector.add(0, new GuardEatFoodGoal(this));
        this.goalSelector.add(0, new RaiseShieldGoal(this));
        this.goalSelector.add(1, new GuardRunToEatGoal(this));
        this.goalSelector.add(1, new MeleeRetreatForHealingGoal(this, 1.1D));
        this.goalSelector.add(2, new RangedCrossbowAttackPassiveGoal<>(this, 1.0D, 8.0F));
        this.goalSelector.add(2, new GuardSpellbladeGoal(this));
        this.goalSelector.add(2, new GuardMeleeSpellCastGoal(this));
        this.goalSelector.add(2, new GuardSupportCasterGoal(this));
        this.goalSelector.add(2, new GuardCastSpellGoal(this));
        this.goalSelector.add(2, new StaffCasterDefensiveGoal(this));
        this.goalSelector.add(2, new RangedBowAttackPassiveGoal<GuardEntity>(this, 0.5D, 20, 15.0F) {
            @Override
            public boolean canStart() {
                return GuardEntity.this.getTarget() != null && this.isBowInMainhand() && !GuardEntity.this.isEating() && !GuardEntity.this.isBlocking();
            }

            private boolean isBowInMainhand() {
                return GuardItemTags.isBowLikeWeapon(GuardEntity.this.getMainHandStack());
            }

            @Override
            public void tick() {
                super.tick();
                if (GuardEntity.this.isPatrolling()) {
                    GuardEntity.this.getNavigation().stop();
                    GuardEntity.this.getMoveControl().strafeTo(0.0F, 0.0F);
                }
            }

            @Override
            public boolean shouldContinue() {
                return (this.canStart() || !GuardEntity.this.getNavigation().isIdle()) && this.isBowInMainhand();
            }
        });
        this.goalSelector.add(3, new GuardEntityMeleeGoal(this, 0.8D, true));
        this.goalSelector.add(3, new GuardEntity.FollowHeroGoal(this));
        this.goalSelector.add(3, new BehindOwnerFormationGoal(this));
        this.goalSelector.add(3, new HolyAreaAnchorGoal(this, 1.1D, HolyAreaAnchorGoal.rangedOrCaster()));

        if (GuardVillagersConfig.guardEntitysRunFromPolarBears)
            this.goalSelector.add(3, new FleeEntityGoal<>(this, PolarBearEntity.class, 12.0F, 1.0D, 1.2D));
        this.goalSelector.add(3, new WanderAroundPointOfInterestGoal(this, 0.5D, false));
        this.goalSelector.add(3, new IronGolemWanderAroundGoal(this, 0.5D));
        this.goalSelector.add(3, new MoveThroughVillageGoal(this, 0.5D, false, 4, () -> false));
        if (GuardVillagersConfig.guardEntitysOpenDoors) this.goalSelector.add(3, new GuardInteractDoorGoal(this, true));
        if (GuardVillagersConfig.guardEntityFormation) this.goalSelector.add(5, new FollowShieldGuards(this));
        if (GuardVillagersConfig.clericHealing) this.goalSelector.add(6, new RunToClericGoal(this));
        if (GuardVillagersConfig.armorerRepairGuardEntityArmor)
            this.goalSelector.add(6, new ArmorerRepairGuardArmorGoal(this));
        this.goalSelector.add(4, new WalkBackToCheckPointGoal(this, 0.5D));
        this.goalSelector.add(5, new WanderAroundFarGoal(this, 0.5D));
        this.goalSelector.add(8, new LookAtEntityGoal(this, MerchantEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new GuardLookAtAndStopMovingWhenBeingTheInteractionTarget(this));
        this.targetSelector.add(5, new GuardEntity.DefendVillageGuardEntityGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, RavagerEntity.class, true));
        this.targetSelector.add(2, (new RevengeGoal(this, GuardEntity.class, IronGolemEntity.class)).setGroupRevenge());
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, WitchEntity.class, true));
        this.targetSelector.add(3, new HeroHurtByTargetGoal(this));
        this.targetSelector.add(3, new HeroHurtTargetGoal(this));
        this.targetSelector.add(3, new ActiveTargetGoal<>(this, RaiderEntity.class, true));
        if (GuardVillagersConfig.attackAllMobs) {
            this.targetSelector.add(3, new ActiveTargetGoal<>(this, MobEntity.class, 5, true, true,
                    GuardTargeting::isProactiveHuntTarget));
        }
        this.targetSelector.add(3, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, true, false, this::shouldAngerAt));
        this.targetSelector.add(4, new ActiveTargetGoal<>(this, ZombieEntity.class, true));
        this.targetSelector.add(4, new UniversalAngerGoal<>(this, false));
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public void shootAt(LivingEntity target, float pullProgress) {
        this.shieldCoolDown = 8;

        if (this.getMainHandStack().getItem() instanceof CrossbowItem crossbowItem) {
            ItemStack crossbow = this.getMainHandStack();

            if (!CrossbowItem.isCharged(crossbow)) {
                ItemStack ammo = this.getProjectileType(crossbow);
                if (ammo.isEmpty()) {
                    ammo = new ItemStack(Items.ARROW);
                }

                crossbow.set(
                        DataComponentTypes.CHARGED_PROJECTILES,
                        ChargedProjectilesComponent.of(List.of(ammo.copyWithCount(1)))
                );
            }

            crossbowItem.shootAll(
                    this.getWorld(),
                    this,
                    Hand.MAIN_HAND,
                    crossbow,
                    3.15F,
                    1.0F,
                    target
            );
            return;
        }

        if (!(this.getMainHandStack().getItem() instanceof BowItem)) return;

        Hand hand = null;
        ItemStack weapon = ItemStack.EMPTY;

        for (Hand h : Hand.values()) {
            ItemStack stack = this.getStackInHand(h);
            if (stack.getItem() instanceof BowItem) {
                hand = h;
                weapon = stack;
                break;
            }
        }

        if (hand == null || !(weapon.getItem() instanceof BowItem)) return;

        ItemStack arrow = this.getProjectileType(weapon);
        if (arrow.isEmpty()) {
            arrow = new ItemStack(Items.ARROW);
        }

        GuardDebugManager.broadcast(this, "🏹 Shooting arrow - checking for spell effects", Formatting.AQUA);

        List<RegistryEntry<Spell>> arrowSpells = new ArrayList<>();
        List<Pair<RegistryEntry<net.minecraft.entity.effect.StatusEffect>, Integer>> effectsToModify = new ArrayList<>();

        List<RegistryEntry<Spell>> allSpells = net.spell_engine.api.spell.registry.SpellRegistry.stream(this.getWorld())
                .<RegistryEntry<Spell>>map(e -> e).toList();

        int effectCount = 0;
        for (var effectInstance : this.getStatusEffects()) {
            var effectEntry = effectInstance.getEffectType();
            String effectId = Registries.STATUS_EFFECT.getId(effectEntry.value()).toString();
            effectCount++;

            GuardDebugManager.broadcast(this,
                    "  📋 Checking effect #" + effectCount + ": " + effectId + " (amp: " + effectInstance.getAmplifier() + ", duration: " + effectInstance.getDuration() + ")",
                    Formatting.GRAY);

            boolean foundMatchingSpell = false;

            for (var spellEntry : allSpells) {
                Spell spell = spellEntry.value();
                if (spell.deliver != null &&
                        spell.deliver.type == Spell.Delivery.Type.STASH_EFFECT &&
                        spell.deliver.stash_effect != null &&
                        spell.deliver.stash_effect.id.equals(effectId)) {

                    GuardDebugManager.broadcast(this,
                            "    🔍 Found spell with matching stash_effect: " + spellEntry.getKey().get().getValue(),
                            Formatting.YELLOW);

                    GuardDebugManager.broadcast(this,
                            "      📝 Spell ID comparison: stash_effect.id='" + spell.deliver.stash_effect.id + "' vs effectId='" + effectId + "'",
                            Formatting.GRAY);

                    boolean hasArrowTrigger = false;
                    if (spell.deliver.stash_effect.triggers != null && !spell.deliver.stash_effect.triggers.isEmpty()) {
                        GuardDebugManager.broadcast(this,
                                "      🔧 Triggers found: " + spell.deliver.stash_effect.triggers.size(),
                                Formatting.GRAY);

                        for (var trigger : spell.deliver.stash_effect.triggers) {
                            GuardDebugManager.broadcast(this,
                                    "        • Trigger type: " + trigger.type,
                                    Formatting.GRAY);

                            if (trigger.type == Spell.Trigger.Type.ARROW_IMPACT ||
                                    trigger.type == Spell.Trigger.Type.ARROW_SHOT) {
                                hasArrowTrigger = true;
                                GuardDebugManager.broadcast(this,
                                        "      ✅ Has ARROW trigger type: " + trigger.type,
                                        Formatting.GREEN);
                                break;
                            }
                        }
                    } else {
                        GuardDebugManager.broadcast(this,
                                "      ❌ No triggers defined (null or empty)",
                                Formatting.RED);
                    }

                    if (hasArrowTrigger) {
                        arrowSpells.add(spellEntry);
                        foundMatchingSpell = true;

                        int consumeAmount = spell.deliver.stash_effect.consume;
                        GuardDebugManager.broadcast(this,
                                "      📊 Consume amount: " + consumeAmount,
                                Formatting.GOLD);

                        GuardDebugManager.broadcast(this,
                                "      🎯 Impact mode: " + (spell.deliver.stash_effect.impact_mode != null ? spell.deliver.stash_effect.impact_mode : "NULL"),
                                Formatting.GOLD);

                        GuardDebugManager.broadcast(this,
                                "      💥 Spell has " + (spell.impacts != null ? spell.impacts.size() : 0) + " impact(s) defined",
                                Formatting.GOLD);

                        if (consumeAmount > 0) {
                            effectsToModify.add(new Pair<>(effectEntry, consumeAmount));
                            GuardDebugManager.broadcast(this,
                                    "      🔄 Will consume " + consumeAmount + " stack(s)",
                                    Formatting.LIGHT_PURPLE);
                        } else {
                            GuardDebugManager.broadcast(this,
                                    "      ♾️ Will not consume (persistent effect)",
                                    Formatting.LIGHT_PURPLE);
                        }
                        break;
                    }
                }
            }

            if (!foundMatchingSpell) {
                GuardDebugManager.broadcast(this,
                        "    ⚠️ No matching spell found for this effect",
                        Formatting.DARK_GRAY);
            }
        }

        if (effectCount == 0) {
            GuardDebugManager.broadcast(this, "  ℹ️ No status effects on guard", Formatting.DARK_GRAY);
        }

        for (var entry : effectsToModify) {
            var effectEntry = entry.getFirst();
            int consumeAmount = entry.getSecond();

            var effectInstance = this.getStatusEffect(effectEntry);
            if (effectInstance != null) {
                int currentAmplifier = effectInstance.getAmplifier();
                int newAmplifier = currentAmplifier - consumeAmount;

                String effectId = Registries.STATUS_EFFECT.getId(effectEntry.value()).toString();
                GuardDebugManager.broadcast(this,
                        "  🔻 Consuming effect: " + effectId + " (" + currentAmplifier + " → " + newAmplifier + ")",
                        Formatting.RED);

                this.removeStatusEffect(effectEntry);

                if (newAmplifier >= 0) {
                    this.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                            effectEntry,
                            effectInstance.getDuration(),
                            newAmplifier,
                            effectInstance.isAmbient(),
                            effectInstance.shouldShowParticles(),
                            effectInstance.shouldShowIcon()
                    ));
                } else {
                    GuardDebugManager.broadcast(this,
                            "    ❌ Effect fully consumed",
                            Formatting.DARK_RED);
                }
            }
        }

        if (!arrowSpells.isEmpty()) {
            GuardDebugManager.broadcast(this,
                    "✨ Applying " + arrowSpells.size() + " spell(s) to arrow:",
                    Formatting.GREEN);

            for (var spellEntry : arrowSpells) {
                Spell spell = spellEntry.value();
                String spellPath = spellEntry.getKey().get().getValue().getPath();

                GuardDebugManager.broadcast(this,
                        "    • " + spellPath,
                        Formatting.AQUA);

                GuardDebugManager.broadcast(this,
                        "      School: " + spell.school + ", Range: " + spell.range + ", Tier: " + spell.tier,
                        Formatting.GRAY);

                if (spell.deliver != null && spell.deliver.stash_effect != null) {
                    var stash = spell.deliver.stash_effect;
                    GuardDebugManager.broadcast(this,
                            "      Stash: id=" + stash.id + ", consume=" + stash.consume + ", impact_mode=" + stash.impact_mode,
                            Formatting.GRAY);
                }
            }

            ArrowShootContext context = new ArrowShootContext();
            context.firedBySpell = true;
            context.activeSpells.addAll(arrowSpells);
            this.setArrowShootContext(context);

            GuardDebugManager.broadcast(this,
                    "📦 ArrowShootContext created: firedBySpell=" + context.firedBySpell + ", spells=" + context.activeSpells.size(),
                    Formatting.AQUA);
        } else {
            GuardDebugManager.broadcast(this,
                    "⚠️ No arrow spells to apply",
                    Formatting.YELLOW);
            this.setArrowShootContext(null);
        }

        ItemEnchantmentsComponent enchantments = EnchantmentHelper.getEnchantments(weapon);
        PersistentProjectileEntity projectile = ProjectileUtil.createArrowProjectile(this, arrow, pullProgress, weapon);

        GuardDebugManager.broadcast(this,
                "🎯 Created projectile: " + projectile.getClass().getSimpleName() + " (id=" + projectile.getId() + ")",
                Formatting.GRAY);

        if (projectile instanceof ArrowExtension arrowExt) {
            GuardDebugManager.broadcast(this,
                    "✅ Arrow IS an ArrowExtension",
                    Formatting.GREEN);
            GuardDebugManager.broadcast(this,
                    "🔍 Arrow carried spells BEFORE application: " + arrowExt.getCarriedSpells().size(),
                    Formatting.YELLOW);
            for (var spell : arrowExt.getCarriedSpells()) {
                GuardDebugManager.broadcast(this,
                        "    • Carried: " + spell.getKey().get().getValue(),
                        Formatting.GRAY);
            }
        } else {
            GuardDebugManager.broadcast(this,
                    "❌ Arrow is NOT an ArrowExtension - class: " + projectile.getClass().getName(),
                    Formatting.RED);
        }

        double rangedDamage = 0.0;
        Optional<RegistryEntry.Reference<EntityAttribute>> rangedAttrEntryOpt = Registries.ATTRIBUTE.getEntry(Identifier.of("ranged_weapon", "damage"));

        if (rangedAttrEntryOpt.isPresent()) {
            RegistryEntry<EntityAttribute> rangedAttrEntry = rangedAttrEntryOpt.get();
            if (this.getAttributes().hasAttribute(rangedAttrEntry)) {
                rangedDamage = this.getAttributeValue(rangedAttrEntry);
            }
        }

        RegistryWrapper.Impl<Enchantment> registry = this.getRegistryManager().getWrapperOrThrow(RegistryKeys.ENCHANTMENT);
        int powerLevel = enchantments.getLevel(registry.getOrThrow(Enchantments.POWER));
        if (powerLevel > 0) {
            projectile.setDamage(projectile.getDamage() + powerLevel * 0.5D + 0.5D);
        }

        projectile.setDamage(projectile.getDamage() + rangedDamage / 3);

        if (enchantments.getLevel(registry.getOrThrow(Enchantments.FLAME)) > 0) {
            projectile.setFireTicks(100);
        }

        ArrowShootContext shootContext = this.getArrowShootContext();

        GuardDebugManager.broadcast(this,
                "📥 Retrieved ArrowShootContext: " + (shootContext != null ? ("firedBySpell=" + shootContext.firedBySpell + ", spells=" + shootContext.activeSpells.size()) : "NULL"),
                Formatting.GRAY);

        if (shootContext != null && shootContext.firedBySpell && !shootContext.activeSpells.isEmpty()) {
            if (projectile instanceof net.spell_engine.internals.arrow.ArrowExtension arrowExt) {
                GuardDebugManager.broadcast(this,
                        "🎯 Applying " + shootContext.activeSpells.size() + " spell effect(s) to arrow entity",
                        Formatting.LIGHT_PURPLE);

                for (RegistryEntry<Spell> spellEntry : shootContext.activeSpells) {
                    GuardDebugManager.broadcast(this,
                            "  🔧 Adding spell to arrow: " + spellEntry.getKey().get().getValue(),
                            Formatting.GOLD);

                    arrowExt.applyArrowPerks(spellEntry);

                    GuardDebugManager.broadcast(this,
                            "    ✅ Spell added to arrow carried list",
                            Formatting.GREEN);
                    GuardDebugManager.broadcast(this,
                            "    🔍 Arrow carried spells: " + arrowExt.getCarriedSpells().size(),
                            Formatting.YELLOW);
                }
            }
            this.setArrowShootContext(null);
        } else {
            GuardDebugManager.broadcast(this,
                    "⏭️ Skipping spell application: context=" + (shootContext != null) +
                            ", firedBySpell=" + (shootContext != null && shootContext.firedBySpell) +
                            ", hasSpells=" + (shootContext != null && !shootContext.activeSpells.isEmpty()),
                    Formatting.DARK_GRAY);
        }

        double dx = target.getX() - this.getX();
        double dy = target.getBodyY(0.3333333333333333D) - projectile.getY();
        double dz = target.getZ() - this.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        projectile.setVelocity(dx, dy + dist * 0.2D, dz, 1.6F, 14 - this.getWorld().getDifficulty().getId() * 4);

        GuardDebugManager.broadcast(this,
                "🚀 Arrow velocity set: dx=" + String.format("%.2f", dx) + ", dy=" + String.format("%.2f", dy) + ", dz=" + String.format("%.2f", dz),
                Formatting.GRAY);

        this.playSound(SoundEvents.ENTITY_SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.getWorld().spawnEntity(projectile);

        if (projectile instanceof ArrowExtension arrowExt) {
            GuardDebugManager.broadcast(this,
                    "🔍 Arrow carried spells AFTER spawning in world: " + arrowExt.getCarriedSpells().size(),
                    Formatting.YELLOW);
            for (var spell : arrowExt.getCarriedSpells()) {
                GuardDebugManager.broadcast(this,
                        "    • Carried: " + spell.getKey().get().getValue(),
                        Formatting.GRAY);
            }
        }

        GuardDebugManager.broadcast(this,
                "🌍 Arrow spawned in world at " + projectile.getBlockPos(),
                Formatting.GRAY);

        weapon.damage(1, this, EquipmentSlot.MAINHAND);

        GuardDebugManager.broadcast(this, "🏹 Arrow launched!", Formatting.GREEN);
    }

    @Override
    public void equipStack(EquipmentSlot slotIn, ItemStack stack) {
        super.equipStack(slotIn, stack);
        switch (slotIn) {
            case HEAD -> {
                if (this.guardInventory.getStack(0).isEmpty())
                    this.guardInventory.setStack(0, this.armorItems.get(slotIn.getEntitySlotId()));
            }
            case CHEST -> {
                if (this.guardInventory.getStack(1).isEmpty())
                    this.guardInventory.setStack(1, this.armorItems.get(slotIn.getEntitySlotId()));
            }
            case LEGS -> {
                if (this.guardInventory.getStack(2).isEmpty())
                    this.guardInventory.setStack(2, this.armorItems.get(slotIn.getEntitySlotId()));
            }
            case FEET -> {
                if (this.guardInventory.getStack(3).isEmpty())
                    this.guardInventory.setStack(3, this.armorItems.get(slotIn.getEntitySlotId()));
            }
            case OFFHAND -> this.guardInventory.setStack(4, this.handItems.get(slotIn.getEntitySlotId()));
            case MAINHAND -> {
                this.guardInventory.setStack(5, this.handItems.get(slotIn.getEntitySlotId()));
                spellManager.refresh();
            }
            default -> {}
        }
    }

    public int getGuardVariant() {
        return this.dataTracker.get(GUARD_VARIANT);
    }

    @Override
    public ItemStack getProjectileType(ItemStack shootable) {
        if (shootable.getItem() instanceof RangedWeaponItem) {
            Predicate<ItemStack> predicate = ((RangedWeaponItem) shootable.getItem()).getHeldProjectiles();
            ItemStack itemstack = RangedWeaponItem.getHeldProjectile(this, predicate);
            return itemstack.isEmpty() ? new ItemStack(Items.ARROW) : itemstack;
        } else {
            return ItemStack.EMPTY;
        }
    }

    public int getKickTicks() {
        return this.kickTicks;
    }

    public boolean isFollowing() {
        return this.dataTracker.get(FOLLOWING);
    }

    public void setFollowing(boolean following) {
        this.dataTracker.set(FOLLOWING, following);
    }

    public boolean isHired() {
        return isTamed();
    }

    public void setHired(boolean hired) {
        setTamed(hired, false);
        if (hired) {
            this.protectHiredEquipment();
        }
    }

    public void releaseGuard() {
        setTamed(false, false);
        setOwnerUuid(null);
        hotvFollowerId = null;
        setFollowing(false);
        this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 1.0F);
        this.setEquipmentDropChance(EquipmentSlot.OFFHAND, 1.0F);
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) {
        return false;
    }

    @Override
    public @Nullable PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
        return null;
    }

    public void registerSpellSummon(LivingEntity entity) {
        if (entity != null) {
            spellSummons.add(entity.getUuid());
            entity.addCommandTag("guardvillagers:spell_summon");
            entity.addCommandTag("guardvillagers:summon_of:" + getUuidAsString());
        }
    }

    public boolean isSpellSummon(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        pruneSpellSummons();
        return spellSummons.contains(entity.getUuid())
                || entity.getCommandTags().contains("guardvillagers:summon_of:" + getUuidAsString());
    }

    private void pruneSpellSummons() {
        spellSummons.removeIf(id -> {
            if (!(getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld)) {
                return false;
            }
            Entity entity = serverWorld.getEntity(id);
            return entity == null || !entity.isAlive();
        });
    }

    @Override
    public boolean canTarget(LivingEntity target) {
        return GuardTargeting.passesGuardFilters(this, target) && super.canTarget(target);
    }

    @Override
    public void tickRiding() {
        super.tickRiding();
        if (this.getVehicle() instanceof PathAwareEntity creatureentity) {
            this.bodyYaw = creatureentity.bodyYaw;
        }
    }

    @Override
    public void postShoot() {
        this.despawnCounter = 0;
    }

    @Override
    public void setTarget(LivingEntity entity) {
        if (entity instanceof GuardEntity || entity instanceof VillagerEntity || entity instanceof IronGolemEntity)
            return;
        super.setTarget(entity);
    }

    public void gossip(VillagerEntity villager, long gameTime) {
        if ((gameTime < this.lastGossipTime || gameTime >= this.lastGossipTime + 1200L) && (gameTime < villager.gossipStartTime || gameTime >= villager.gossipStartTime + 1200L)) {
            this.gossips.shareGossipFrom(villager.getGossip(), this.random, 10);
            this.lastGossipTime = gameTime;
            villager.gossipStartTime = gameTime;
        }
    }

    @Override
    public void setCharging(boolean charging) {
        this.setChargingCrossbowGuard(charging);
    }

    @Override
    public void knockback(LivingEntity entityIn) {
        if (this.isKicking()) {
            this.setKicking(false);
        }
        super.knockback(entityIn);
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack heldStack = player.getStackInHand(hand);

        if (GuardEffectiveConfig.allowHiring(this) && !this.isHired() && !player.shouldCancelInteraction()) {
            Identifier hiringId = this.hiringItemOverride != null
                    ? this.hiringItemOverride
                    : Identifier.tryParse(GuardVillagersConfig.hiringItem);
            if (hiringId != null && !heldStack.isEmpty()
                    && Registries.ITEM.getId(heldStack.getItem()).equals(hiringId)) {
                if (!this.getWorld().isClient()) {
                    int cost = this.hiringCostOverride != null
                            ? this.hiringCostOverride
                            : GuardEffectiveConfig.hiringItemCount(this);
                    if (heldStack.getCount() >= cost) {
                        if (!player.getAbilities().creativeMode) {
                            heldStack.decrement(cost);
                        }
                        this.setOwnerUuid(player.getUuid());
                        this.setTamed(true, false);
                        this.hotvFollowerId = null;
                        this.setFollowing(true);
                        this.protectHiredEquipment();
                        player.sendMessage(Text.translatable("guardvillagers.hiring.hired"), true);
                    } else {
                        player.sendMessage(Text.translatable("guardvillagers.hiring.not_enough",
                                cost, Text.translatable(heldStack.getItem().getTranslationKey())), true);
                    }
                }
                return ActionResult.SUCCESS;
            }
        }

        if (this.isHired()) {
            boolean isOwner = this.getOwnerId() != null && this.getOwnerId().equals(player.getUuid());
            if (!isOwner) {
                if (!this.getWorld().isClient()) {
                    player.sendMessage(Text.translatable("guardvillagers.hiring.already_hired"), true);
                }
                return ActionResult.CONSUME;
            }
        }

        boolean hasHeroAccess = playerHasHeroInteractionAccess(player);
        boolean configValues = hasHeroAccess && GuardEffectiveConfig.giveGuardStuffHotv(this)
                || hasHeroAccess && GuardEffectiveConfig.setGuardPatrolHotv(this)
                || hasHeroAccess && GuardEffectiveConfig.giveGuardStuffHotv(this) && GuardEffectiveConfig.setGuardPatrolHotv(this)
                || hasHeroAccess && this.getPlayerEntityReputation(player) >= GuardEffectiveConfig.reputationRequirement(this)
                || hasHeroAccess && !GuardEffectiveConfig.giveGuardStuffHotv(this) && !GuardEffectiveConfig.setGuardPatrolHotv(this)
                || this.getOwnerId() != null && this.getOwnerId().equals(player.getUuid());
        boolean inventoryRequirements = !player.shouldCancelInteraction();
        if (inventoryRequirements) {
            if (this.getTarget() != player && this.canMoveVoluntarily() && configValues && this.canPlayerOpenGui(player)) {
                if (player instanceof ServerPlayerEntity) {
                    this.openGui((ServerPlayerEntity) player);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.CONSUME;
        }
        return super.interactMob(player, hand);
    }

    @Override
    public void onInteractionWith(EntityInteraction interaction, Entity entity) {

    }

    @Override
    public void onInventoryChanged(Inventory sender) {
        if (!this.getWorld().isClient()) {
            spellManager.refresh();
        }
    }

    @Override
    public void damageArmor(DamageSource damageSource, float damage) {
        if (this.isHired() || this.immutableEquipment) {
            return;
        }
        if (damage >= 0.0F) {
            damage = damage / 4.0F;
            if (damage < 1.0F) {
                damage = 1.0F;
            }
            var list = Arrays.stream(EquipmentSlot.values()).filter(EquipmentSlot::isArmorSlot).toList();
            for (int i = 0; i < this.guardInventory.size(); ++i) {
                if (i >= list.size()) break;
                ItemStack itemstack = this.guardInventory.getStack(i);

                if ((!damageSource.isOf(DamageTypes.ON_FIRE) || !itemstack.getItem().getComponents().contains(DataComponentTypes.FIRE_RESISTANT)) && itemstack.getItem() instanceof ArmorItem) {
                    itemstack.damage((int) damage, this, list.get(i));
                }
            }
        }
    }

    @Override
    public void onStruckByLightning(ServerWorld world, LightningEntity lightning) {
        if (!GuardVillagersConfig.lightningConvertsGuardToWitch) {
            super.onStruckByLightning(world, lightning);
            return;
        }
        if (world.getDifficulty() != Difficulty.PEACEFUL) {
            WitchEntity witchentity = EntityType.WITCH.create(world);
            if (witchentity == null) return;
            witchentity.copyPositionAndRotation(this);
            witchentity.initialize(world, world.getLocalDifficulty(witchentity.getBlockPos()), SpawnReason.CONVERSION, null);
            witchentity.setAiDisabled(this.isAiDisabled());
            witchentity.setCustomName(this.getCustomName());
            witchentity.setCustomNameVisible(this.isCustomNameVisible());
            witchentity.setPersistent();
            world.spawnNewEntityAndPassengers(witchentity);
            this.discard();
        } else {
            super.onStruckByLightning(world, lightning);
        }
    }

    @Override
    public UUID getAngryAt() {
        return this.persistentAngerTarget;
    }

    @Override
    public void setAngryAt(UUID arg0) {
        this.persistentAngerTarget = arg0;
    }

    @Override
    public int getAngerTime() {
        return this.remainingPersistentAngerTime;
    }

    @Override
    public void setAngerTime(int arg0) {
        this.remainingPersistentAngerTime = arg0;
    }

    @Override
    public void chooseRandomAngerTime() {
        this.setAngerTime(angerTime.get(random));
    }

    public void openGui(ServerPlayerEntity player) {
        if (player.currentScreenHandler != player.playerScreenHandler) {
            player.closeHandledScreen();
        }
        this.interacting = true;
        if (!this.getWorld().isClient()) {
            player.openHandledScreen(new GuardScreenHandlerFactory());
        }
    }

    public void setGuardVariant(int i) {
        this.dataTracker.set(GUARD_VARIANT, i);
    }

    private class GuardScreenHandlerFactory implements ExtendedScreenHandlerFactory {
        private GuardEntity guard() {
            return GuardEntity.this;
        }

        @Override
        public Text getDisplayName() {
            return this.guard().getDisplayName();
        }

        @Override
        public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
            var guardInv = this.guard().guardInventory;
            return new GuardVillagerScreenHandler(syncId, inv, guardInv, this.guard());        }

        @Override
        public Object getScreenOpeningData(ServerPlayerEntity player) {
            return new GuardData(guard().getId());
        }
    }

    public boolean isEating() {
        return GuardEatFoodGoal.isConsumable(this.getActiveItem()) && this.isUsingItem();
    }

    public boolean isPatrolling() {
        return this.dataTracker.get(PATROLLING);
    }

    public void setPatrolling(boolean patrolling) {
        this.dataTracker.set(PATROLLING, patrolling);
    }

    public GuardVillagersConfig.SupportBuffPriority getBuffPriority() {
        return buffPriority;
    }

    public void setBuffPriority(GuardVillagersConfig.SupportBuffPriority priority) {
        this.buffPriority = priority;
    }

    public void cycleBuffPriority() {
        GuardVillagersConfig.SupportBuffPriority[] values = GuardVillagersConfig.SupportBuffPriority.values();
        this.buffPriority = values[(this.buffPriority.ordinal() + 1) % values.length];
    }

    public GuardVillagersConfig.FollowFormation getFollowFormation() {
        return followFormation;
    }

    public void setFollowFormation(GuardVillagersConfig.FollowFormation formation) {
        this.followFormation = formation;
    }

    public void cycleFollowFormation() {
        GuardVillagersConfig.FollowFormation[] values = GuardVillagersConfig.FollowFormation.values();
        this.followFormation = values[(this.followFormation.ordinal() + 1) % values.length];
    }

    @Override
    public boolean canUseRangedWeapon(RangedWeaponItem item) {
        return item instanceof BowItem
                || item instanceof CrossbowItem
                || GuardItemTags.isRangedDamageWeapon(new ItemStack(item))
                || super.canUseRangedWeapon(item);
    }

    public static class GuardEntityData implements EntityData {
        public final int variantData;

        public GuardEntityData(int type) {
            this.variantData = type;
        }
    }

    public static class DefendVillageGuardEntityGoal extends TrackTargetGoal {
        private final GuardEntity guard;
        private LivingEntity villageAggressorTarget;

        public DefendVillageGuardEntityGoal(GuardEntity guardIn) {
            super(guardIn, false, true);
            this.guard = guardIn;
            this.setControls(EnumSet.of(Goal.Control.TARGET, Goal.Control.MOVE));
        }

        @Override
        public boolean canStart() {
            Box box = this.guard.getBoundingBox().expand(10.0D, 8.0D, 10.0D);
            List<VillagerEntity> list = guard.getWorld().getNonSpectatingEntities(VillagerEntity.class, box);
            List<PlayerEntity> list1 = guard.getWorld().getNonSpectatingEntities(PlayerEntity.class, box);
            for (VillagerEntity villager : list) {
                for (PlayerEntity player : list1) {
                    int i = villager.getReputation(player);
                    if (i <= GuardEffectiveConfig.reputationRequirementToBeAttacked(this.guard)) {
                        this.villageAggressorTarget = player;
                    }
                }
            }
            return villageAggressorTarget != null && !villageAggressorTarget.hasStatusEffect(StatusEffects.HERO_OF_THE_VILLAGE) && !this.villageAggressorTarget.isSpectator() && !((PlayerEntity) this.villageAggressorTarget).isCreative();
        }

        @Override
        public void start() {
            this.guard.setTarget(this.villageAggressorTarget);
            super.start();
        }
    }

    public static class FollowHeroGoal extends Goal {
        private static final double CLOSE_RANGE = 3.0D;
        private static final double HIRED_TELEPORT_RANGE = 12.0D;

        public final GuardEntity guard;

        public FollowHeroGoal(GuardEntity mob) {
            this.guard = mob;
            this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
        }

        @Override
        public void tick() {
            LivingEntity owner = guard.getOwner();
            if (owner == null) {
                guard.getNavigation().stop();
                return;
            }

            double distance = guard.distanceTo(owner);
            if (guard.isHired() && distance > HIRED_TELEPORT_RANGE) {
                guard.getNavigation().stop();
                Vec3d safeTeleportPos = findSafeTeleportNear(guard, owner);
                if (safeTeleportPos == null) return;
                guard.refreshPositionAndAngles(safeTeleportPos.x, safeTeleportPos.y, safeTeleportPos.z, owner.getYaw(), owner.getPitch());
                guard.setVelocity(Vec3d.ZERO);
                guard.getLookControl().lookAt(owner, 30.0F, 30.0F);
                return;
            }

            if (distance > CLOSE_RANGE) {
                guard.getNavigation().startMovingTo(owner, 0.7D);
                guard.getLookControl().lookAt(owner, 30.0F, 30.0F);
            } else {
                guard.getNavigation().stop();
            }
        }

        @Override
        public boolean shouldContinue() {
            return this.canStart();
        }

        @Override
        public boolean canStart() {
            if (!guard.isFollowing() || guard.getOwner() == null) return false;
            LivingEntity target = guard.getTarget();
            return target == null || !target.isAlive();
        }

        @Override
        public void stop() {
            this.guard.getNavigation().stop();
        }
    }

    private static Vec3d findSafeTeleportNear(GuardEntity guard, LivingEntity owner) {
        World world = guard.getWorld();
        if (!(world instanceof net.minecraft.server.world.ServerWorld serverWorld)) return null;

        Random rng = guard.getRandom();
        BlockPos ownerPos = owner.getBlockPos();

        for (int attempt = 0; attempt < 10; attempt++) {
            int dx = rng.nextBetween(-3, 3);
            int dz = rng.nextBetween(-3, 3);
            int x = ownerPos.getX() + dx;
            int z = ownerPos.getZ() + dz;
            int surfaceY = serverWorld.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos teleportPos = new BlockPos(x, surfaceY, z);
            if (world.getBlockState(teleportPos).isAir()
                    && world.getBlockState(teleportPos.up()).isAir()) {
                return Vec3d.ofBottomCenter(teleportPos);
            }
        }
        return null;
    }

    public boolean isHoldingHolyFocus() {
        return getSpellManager().hasHealingSpells() || getSpellManager().hasSupportSpells();
    }

    public boolean isPriest() {
        return dev.sterner.guardvillagers.common.ai.GuardCombatRole.resolve(this)
                == dev.sterner.guardvillagers.common.ai.GuardCombatRole.SupportRole.PRIEST;
    }

    public boolean hasFoodInOffhand() {
        ItemStack off = this.getOffHandStack();
        if (off.isEmpty()) return false;

        net.minecraft.component.type.FoodComponent food = off.get(net.minecraft.component.DataComponentTypes.FOOD);
        if (food != null) return true;

        return off.getUseAction() == net.minecraft.util.UseAction.EAT;
    }

    

    public boolean isShield(ItemStack stack) {
        if (stack.isEmpty()) return false;
        
        if (stack.getItem() instanceof net.minecraft.item.ShieldItem) return true;
        
        return stack.getUseAction() == net.minecraft.util.UseAction.BLOCK;
    }

    

    public boolean hasShield() {
        return isShield(this.getOffHandStack());
    }
}