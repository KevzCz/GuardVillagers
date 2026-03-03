package dev.sterner.guardvillagers.common.screenhandler;

import com.mojang.datafixers.util.Pair;
import dev.sterner.guardvillagers.*;
import dev.sterner.guardvillagers.common.entity.*;
import dev.sterner.guardvillagers.common.network.*;
import net.minecraft.entity.*;
import net.minecraft.entity.player.*;
import net.minecraft.inventory.*;
import net.minecraft.item.*;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.*;
import net.minecraft.util.*;

public class GuardVillagerScreenHandler extends ScreenHandler {

    private final PlayerEntity player;
    public final GuardEntity guardEntity;
    public final Inventory guardInventory;
    private static final EquipmentSlot[] EQUIPMENT_SLOT_ORDER = new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public GuardVillagerScreenHandler(int syncId, PlayerInventory playerInventory, GuardData buf) {
        this(syncId, playerInventory, playerInventory.player.getWorld().getEntityById(buf.guardId()) instanceof GuardEntity guard ? guard : null);
    }

    public GuardVillagerScreenHandler(int syncId, PlayerInventory playerInventory, GuardEntity guardEntity) {
        this(syncId, playerInventory, guardEntity.guardInventory, guardEntity);
    }

    public GuardVillagerScreenHandler(int id, PlayerInventory playerInventory, Inventory inventory, GuardEntity guardEntity) {
        super(GuardVillagers.GUARD_SCREEN_HANDLER, id);
        this.guardInventory = inventory;
        this.player = playerInventory.player;
        this.guardEntity = guardEntity;
        inventory.onOpen(playerInventory.player);
        this.addSlot(new Slot(guardInventory, 0, 8, 9) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return EQUIPMENT_SLOT_ORDER[0] == guardEntity.getPreferredEquipmentSlot(stack) && GuardVillagers.hotvChecker(player, guardEntity);
            }

            @Override
            public int getMaxItemCount() {
                return 1;
            }

            @Override
            public void setStack(ItemStack stack) {
                super.setStack(stack);
                guardEntity.equipStack(EquipmentSlot.HEAD, stack);
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public Pair<Identifier, Identifier> getBackgroundSprite() {
                return Pair.of(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, PlayerScreenHandler.EMPTY_HELMET_SLOT_TEXTURE);
            }
        });
        this.addSlot(new Slot(guardInventory, 1, 8, 26) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return EQUIPMENT_SLOT_ORDER[1] == guardEntity.getPreferredEquipmentSlot(stack) && GuardVillagers.hotvChecker(player, guardEntity);
            }

            @Override
            public int getMaxItemCount() {
                return 1;
            }

            @Override
            public void setStack(ItemStack stack) {
                super.setStack(stack);
                guardEntity.equipStack(EquipmentSlot.CHEST, stack);
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public Pair<Identifier, Identifier> getBackgroundSprite() {
                return Pair.of(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, PlayerScreenHandler.EMPTY_CHESTPLATE_SLOT_TEXTURE);
            }
        });
        this.addSlot(new Slot(guardInventory, 2, 8, 44) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return EQUIPMENT_SLOT_ORDER[2] == guardEntity.getPreferredEquipmentSlot(stack) && GuardVillagers.hotvChecker(player, guardEntity);
            }

            @Override
            public int getMaxItemCount() {
                return 1;
            }

            @Override
            public void setStack(ItemStack stack) {
                super.setStack(stack);
                guardEntity.equipStack(EquipmentSlot.LEGS, stack);
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public Pair<Identifier, Identifier> getBackgroundSprite() {
                return Pair.of(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, PlayerScreenHandler.EMPTY_LEGGINGS_SLOT_TEXTURE);
            }
        });
        this.addSlot(new Slot(guardInventory, 3, 8, 62) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return EQUIPMENT_SLOT_ORDER[3] == guardEntity.getPreferredEquipmentSlot(stack) && GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public int getMaxItemCount() {
                return 1;
            }

            @Override
            public void setStack(ItemStack stack) {
                super.setStack(stack);
                guardEntity.equipStack(EquipmentSlot.FEET, stack);
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public Pair<Identifier, Identifier> getBackgroundSprite() {
                return Pair.of(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, PlayerScreenHandler.EMPTY_BOOTS_SLOT_TEXTURE);
            }
        });
        this.addSlot(new Slot(guardInventory, 4, 77, 62) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public void setStack(ItemStack stack) {
                super.setStack(stack);
                guardEntity.equipStack(EquipmentSlot.OFFHAND, stack);
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public Pair<Identifier, Identifier> getBackgroundSprite() {
                return Pair.of(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, PlayerScreenHandler.EMPTY_OFFHAND_ARMOR_SLOT);
            }
        });

        this.addSlot(new Slot(guardInventory, 5, 77, 44) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerIn, guardEntity);
            }

            @Override
            public void setStack(ItemStack stack) {
                super.setStack(stack);
                guardEntity.equipStack(EquipmentSlot.MAINHAND, stack);
            }
        });
        this.addSlot(new Slot(guardInventory, 6, 95, 62) {
            @Override
            public boolean canInsert(ItemStack stack) {
                if (!GuardVillagers.hotvChecker(player, guardEntity)) {
                    return false;
                }

                if (stack.isIn(net.spell_engine.api.tags.SpellEngineItemTags.SPELL_BOOK)) {
                    return true;
                }

                net.spell_engine.api.spell.container.SpellContainer container =
                        stack.get(net.spell_engine.api.spell.SpellDataComponents.SPELL_CONTAINER);
                return container != null && !container.spell_ids().isEmpty();
            }

            @Override
            public int getMaxItemCount() {
                return 1;
            }

            @Override
            public boolean canTakeItems(PlayerEntity playerIn) {
                return GuardVillagers.hotvChecker(playerInventory.player, guardEntity);
            }
        });
        for (int l = 0; l < 3; ++l) {
            for (int j1 = 0; j1 < 9; ++j1) {
                this.addSlot(new Slot(playerInventory, j1 + (l + 1) * 9, 8 + j1 * 18, 84 + l * 18));
            }
        }

        for (int i1 = 0; i1 < 9; ++i1) {
            this.addSlot(new Slot(playerInventory, i1, 8 + i1 * 18, 142));
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack itemstack1 = slot.getStack();
            itemstack = itemstack1.copy();
            int guardInvSize = this.guardInventory.size();

            if (index < guardInvSize) {
                if (!this.insertItem(itemstack1, guardInvSize, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {

                if (this.getSlot(6).canInsert(itemstack1) && !this.getSlot(6).hasStack()) {
                    if (!this.insertItem(itemstack1, 6, 7, false)) {
                        return ItemStack.EMPTY;
                    }
                }
                else if (this.getSlot(1).canInsert(itemstack1) && !this.getSlot(1).hasStack()) {
                    if (!this.insertItem(itemstack1, 1, 2, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (this.getSlot(0).canInsert(itemstack1)) {
                    if (!this.insertItem(itemstack1, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (guardInvSize <= 2 || ! this.insertItem(itemstack1, 2, guardInvSize, false)) {
                    int playerInvStart = guardInvSize + 27;
                    int hotbarStart = playerInvStart + 9;

                    if (index >= playerInvStart && index < hotbarStart) {
                        if (!this.insertItem(itemstack1, guardInvSize, playerInvStart, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else if (index >= guardInvSize && index < playerInvStart) {
                        if (!this.insertItem(itemstack1, playerInvStart, hotbarStart, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else if (! this.insertItem(itemstack1, playerInvStart, playerInvStart, false)) {
                        return ItemStack.EMPTY;
                    }
                    return ItemStack.EMPTY;
                }
            }

            if (itemstack1.isEmpty()) {
                slot.setStack(ItemStack.EMPTY);
            } else {
                slot.markDirty();
            }
        }
        return itemstack;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return this.guardInventory.canPlayerUse(player) && this.guardEntity.isAlive() && this.guardEntity.distanceTo(player) < 8.0F;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        this.guardInventory.onClose(player);
        this.guardEntity.interacting = false;
    }
}
