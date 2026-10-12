package dev.sterner.guardvillagers.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.network.GuardBuffPriorityPacket;
import dev.sterner.guardvillagers.common.network.GuardDismissPacket;
import dev.sterner.guardvillagers.common.network.GuardFollowPacket;
import dev.sterner.guardvillagers.common.network.GuardFormationPacket;
import dev.sterner.guardvillagers.common.network.GuardPatrolPacket;
import dev.sterner.guardvillagers.common.screenhandler.GuardVillagerScreenHandler;
import dev.sterner.guardvillagers.common.special.GuardEffectiveConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ButtonTextures;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.minecraft.util.math.MathHelper;

import java.util.List;

public class GuardVillagerScreen extends HandledScreen<GuardVillagerScreenHandler> {
    private static final Identifier GUARD_GUI_TEXTURES = GuardVillagers.id("textures/gui/inventory.png");
    private static final ButtonTextures GUARD_FOLLOWING_ICONS = new ButtonTextures(GuardVillagers.id("following/following"), GuardVillagers.id("following/following_highlighted"));
    private static final ButtonTextures GUARD_NOT_FOLLOWING_ICONS = new ButtonTextures(GuardVillagers.id("following/not_following"), GuardVillagers.id("following/not_following_highlighted"));
    private static final ButtonTextures GUARD_PATROLLING_ICONS = new ButtonTextures(GuardVillagers.id("patrolling/patrolling1"), GuardVillagers.id("patrolling/patrolling2"));
    private static final ButtonTextures GUARD_NOT_PATROLLING_ICONS = new ButtonTextures(GuardVillagers.id("patrolling/notpatrolling1"), GuardVillagers.id("patrolling/notpatrolling2"));
    private static final Identifier ARMOR_EMPTY_TEXTURE = Identifier.ofVanilla("hud/armor_empty");
    private static final Identifier ARMOR_HALF_TEXTURE = Identifier.ofVanilla("hud/armor_half");
    private static final Identifier ARMOR_FULL_TEXTURE = Identifier.ofVanilla("hud/armor_full");

    private static final String FOLLOW_TOOLTIP_KEY = "guardvillagers.gui.button.follow";
    private static final String PATROL_TOOLTIP_KEY = "guardvillagers.gui.button.patrol";
    private static final String PRIORITY_TOOLTIP_KEY = "guardvillagers.gui.button.buff_priority.value";
    private static final String PRIORITY_KEY_PREFIX = "guardvillagers.gui.buff_priority.";
    private static final String FORMATION_TOOLTIP_KEY = "guardvillagers.gui.button.follow_formation.value";
    private static final String FORMATION_KEY_PREFIX = "guardvillagers.gui.follow_formation.";
    private static final String SHORT_SUFFIX = ".short";
    private static final String DISMISS_LABEL_KEY = "guardvillagers.gui.button.dismiss.label";
    private static final String DISMISS_CONFIRM_LABEL_KEY = "guardvillagers.gui.button.dismiss.confirm_label";
    private static final String DISMISS_TOOLTIP_KEY = "guardvillagers.gui.button.dismiss";
    private static final String DISMISS_CONFIRM_TOOLTIP_KEY = "guardvillagers.gui.button.dismiss.confirm";
    private static final String HEALTH_TOOLTIP_KEY = "guardvillagers.gui.health";
    private static final String ARMOR_TOOLTIP_KEY = "guardvillagers.gui.armor";

    private static final int TITLE_X = 80;
    private static final int TITLE_RIGHT_PADDING = 6;
    private static final int PLAYER_INVENTORY_TITLE_X = 100;
    private static final int TITLE_COLOR = 0x404040;

    private static final int STAT_X = 80;
    private static final int HEALTH_Y = 20;
    private static final int ARMOR_Y = 30;
    private static final int STAT_ICON_COUNT = 10;
    private static final int STAT_ICON_SPACING = 8;
    private static final int STAT_ICON_SIZE = 9;
    private static final int ARMOR_POINTS_PER_ICON = 2;
    private static final int MAX_DISPLAYED_ARMOR = STAT_ICON_COUNT * ARMOR_POINTS_PER_ICON;

    private static final int MODEL_LEFT = 26;
    private static final int MODEL_TOP = 8;
    private static final int MODEL_RIGHT = 76;
    private static final int MODEL_BOTTOM = 82;
    private static final int MODEL_SIZE = 30;
    private static final float MODEL_Y_OFFSET = 0.0625F;

    private static final int BUTTON_ROW_1_Y = 43;
    private static final int BUTTON_ROW_2_Y = 63;
    private static final int FOLLOW_BUTTON_X = 100;
    private static final int PATROL_BUTTON_X = 120;
    private static final int PRIORITY_BUTTON_X = 140;
    private static final int FORMATION_BUTTON_X = 120;
    private static final int DISMISS_BUTTON_X = 140;
    private static final int TOGGLE_BUTTON_WIDTH = 20;
    private static final int TOGGLE_BUTTON_HEIGHT = 18;
    private static final int SMALL_BUTTON_HEIGHT = 14;

    private final PlayerEntity player;
    private final GuardEntity guardEntity;
    private boolean patrolPressed;
    private boolean dismissArmed;

    public GuardVillagerScreen(GuardVillagerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, handler.guardEntity.getDisplayName());
        this.titleX = TITLE_X;
        this.playerInventoryTitleX = PLAYER_INVENTORY_TITLE_X;
        this.player = inventory.player;
        this.guardEntity = handler.guardEntity;
    }

    @Override
    public void removed() {
        guardEntity.isBeingViewedInGui = false;
        super.removed();
    }

    @Override
    protected void init() {
        super.init();
        guardEntity.isBeingViewedInGui = true;
        boolean isHired = guardEntity.isHired();
        boolean isHiredOwner = isHired && guardEntity.isOwnedBy(player);
        boolean heroControls = guardEntity.canPlayerUseHeroControls(player);
        boolean heroAccess = guardEntity.playerHasHeroInteractionAccess(player);

        if (guardEntity.isFollowable() && heroControls && (isHiredOwner || (!isHired && (!GuardEffectiveConfig.followHero(guardEntity) || heroAccess)))) {
            this.addDrawableChild(new GuardGuiButton(this.x + FOLLOW_BUTTON_X, this.y + BUTTON_ROW_1_Y, TOGGLE_BUTTON_WIDTH, TOGGLE_BUTTON_HEIGHT,
                    GUARD_FOLLOWING_ICONS, GUARD_NOT_FOLLOWING_ICONS, true, Text.translatable(FOLLOW_TOOLTIP_KEY),
                    button -> ClientPlayNetworking.send(new GuardFollowPacket(guardEntity.getId()))));
        }
        if (heroControls && (isHiredOwner || (!isHired && (!GuardEffectiveConfig.setGuardPatrolHotv(guardEntity) || heroAccess)))) {
            this.addDrawableChild(new GuardGuiButton(this.x + PATROL_BUTTON_X, this.y + BUTTON_ROW_1_Y, TOGGLE_BUTTON_WIDTH, TOGGLE_BUTTON_HEIGHT,
                    GUARD_PATROLLING_ICONS, GUARD_NOT_PATROLLING_ICONS, false, Text.translatable(PATROL_TOOLTIP_KEY),
                    button -> {
                        patrolPressed = !patrolPressed;
                        ClientPlayNetworking.send(new GuardPatrolPacket(guardEntity.getId(), patrolPressed));
                    }));
        }
        if (isHiredOwner) {
            this.addDrawableChild(ButtonWidget.builder(priorityLabel(), button -> {
                        ClientPlayNetworking.send(new GuardBuffPriorityPacket(guardEntity.getId()));
                        GuardVillagersConfig.SupportBuffPriority[] values = GuardVillagersConfig.SupportBuffPriority.values();
                        guardEntity.setBuffPriority(values[(guardEntity.getBuffPriority().ordinal() + 1) % values.length]);
                        button.setMessage(priorityLabel());
                        button.setTooltip(priorityTooltip());
                    })
                    .dimensions(this.x + PRIORITY_BUTTON_X, this.y + BUTTON_ROW_1_Y, TOGGLE_BUTTON_WIDTH, TOGGLE_BUTTON_HEIGHT)
                    .tooltip(priorityTooltip())
                    .build());
            this.addDrawableChild(ButtonWidget.builder(formationLabel(), button -> {
                        ClientPlayNetworking.send(new GuardFormationPacket(guardEntity.getId()));
                        GuardVillagersConfig.FollowFormation[] values = GuardVillagersConfig.FollowFormation.values();
                        guardEntity.setFollowFormation(values[(guardEntity.getFollowFormation().ordinal() + 1) % values.length]);
                        button.setMessage(formationLabel());
                        button.setTooltip(formationTooltip());
                    })
                    .dimensions(this.x + FORMATION_BUTTON_X, this.y + BUTTON_ROW_2_Y, TOGGLE_BUTTON_WIDTH, SMALL_BUTTON_HEIGHT)
                    .tooltip(formationTooltip())
                    .build());
            this.addDrawableChild(ButtonWidget.builder(Text.translatable(DISMISS_LABEL_KEY).formatted(Formatting.RED), button -> {
                        if (!dismissArmed) {
                            dismissArmed = true;
                            button.setMessage(Text.translatable(DISMISS_CONFIRM_LABEL_KEY).formatted(Formatting.RED));
                            button.setTooltip(Tooltip.of(Text.translatable(DISMISS_CONFIRM_TOOLTIP_KEY)));
                            return;
                        }
                        ClientPlayNetworking.send(new GuardDismissPacket(guardEntity.getId()));
                    })
                    .dimensions(this.x + DISMISS_BUTTON_X, this.y + BUTTON_ROW_2_Y, TOGGLE_BUTTON_WIDTH, SMALL_BUTTON_HEIGHT)
                    .tooltip(Tooltip.of(Text.translatable(DISMISS_TOOLTIP_KEY)))
                    .build());
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float partialTicks) {
        super.render(ctx, mouseX, mouseY, partialTicks);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
        this.drawStatTooltips(ctx, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ctx.drawTexture(GUARD_GUI_TEXTURES, this.x, this.y, 0, 0, this.backgroundWidth, this.backgroundHeight);
        float savedPrevBodyYaw = this.guardEntity.prevBodyYaw;
        this.guardEntity.prevBodyYaw = this.guardEntity.bodyYaw;
        this.guardEntity.isRenderingGuiPreview = true;
        InventoryScreen.drawEntity(ctx, this.x + MODEL_LEFT, this.y + MODEL_TOP, this.x + MODEL_RIGHT, this.y + MODEL_BOTTOM,
                MODEL_SIZE, MODEL_Y_OFFSET, mouseX, mouseY, this.guardEntity);
        this.guardEntity.isRenderingGuiPreview = false;
        this.guardEntity.prevBodyYaw = savedPrevBodyYaw;
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawText(this.textRenderer, this.fittedTitle(), this.titleX, this.titleY, TITLE_COLOR, false);
        ctx.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, TITLE_COLOR, false);
        this.drawHealth(ctx);
        this.drawArmor(ctx);
    }

    private void drawHealth(DrawContext ctx) {
        HeartType filled = guardEntity.hasStatusEffect(StatusEffects.POISON) ? HeartType.POISONED
                : guardEntity.hasStatusEffect(StatusEffects.WITHER) ? HeartType.WITHERED
                : guardEntity.isFrozen() ? HeartType.FROZEN
                : HeartType.NORMAL;
        float perHeart = guardEntity.getMaxHealth() / STAT_ICON_COUNT;
        float health = guardEntity.getHealth();
        for (int i = 0; i < STAT_ICON_COUNT; i++) {
            int iconX = STAT_X + i * STAT_ICON_SPACING;
            this.drawHeart(ctx, HeartType.CONTAINER, iconX, false);
            float remaining = health - i * perHeart;
            if (remaining >= perHeart) {
                this.drawHeart(ctx, filled, iconX, false);
            } else if (remaining > 0) {
                this.drawHeart(ctx, filled, iconX, true);
            }
        }
    }

    private void drawHeart(DrawContext ctx, HeartType type, int x, boolean half) {
        RenderSystem.enableBlend();
        ctx.drawGuiTexture(type.getTexture(half), x, HEALTH_Y, STAT_ICON_SIZE, STAT_ICON_SIZE);
        RenderSystem.disableBlend();
    }

    private void drawArmor(DrawContext ctx) {
        int armor = Math.min(guardEntity.getArmor(), MAX_DISPLAYED_ARMOR);
        for (int i = 0; i < STAT_ICON_COUNT; i++) {
            int iconX = STAT_X + i * STAT_ICON_SPACING;
            int points = armor - i * ARMOR_POINTS_PER_ICON;
            Identifier texture = points >= ARMOR_POINTS_PER_ICON ? ARMOR_FULL_TEXTURE : points > 0 ? ARMOR_HALF_TEXTURE : ARMOR_EMPTY_TEXTURE;
            ctx.drawGuiTexture(texture, iconX, ARMOR_Y, STAT_ICON_SIZE, STAT_ICON_SIZE);
        }
    }

    private void drawStatTooltips(DrawContext ctx, int mouseX, int mouseY) {
        int localX = mouseX - this.x;
        int localY = mouseY - this.y;
        int rowRight = STAT_X + (STAT_ICON_COUNT - 1) * STAT_ICON_SPACING + STAT_ICON_SIZE;
        if (localX >= STAT_X && localX < rowRight) {
            if (localY >= HEALTH_Y && localY < HEALTH_Y + STAT_ICON_SIZE) {
                ctx.drawTooltip(this.textRenderer, Text.translatable(HEALTH_TOOLTIP_KEY,
                        MathHelper.ceil(guardEntity.getHealth()), MathHelper.ceil(guardEntity.getMaxHealth())), mouseX, mouseY);
                return;
            }
            if (localY >= ARMOR_Y && localY < ARMOR_Y + STAT_ICON_SIZE) {
                ctx.drawTooltip(this.textRenderer, Text.translatable(ARMOR_TOOLTIP_KEY,
                        guardEntity.getArmor(), MathHelper.floor(guardEntity.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS))), mouseX, mouseY);
                return;
            }
        }
        int titleRight = this.backgroundWidth - TITLE_RIGHT_PADDING;
        if (this.isTitleTrimmed() && localX >= this.titleX && localX < titleRight
                && localY >= this.titleY && localY < this.titleY + this.textRenderer.fontHeight) {
            ctx.drawOrderedTooltip(this.textRenderer, List.of(this.title.asOrderedText()), mouseX, mouseY);
        }
    }

    private int titleMaxWidth() {
        return this.backgroundWidth - TITLE_RIGHT_PADDING - this.titleX;
    }

    private boolean isTitleTrimmed() {
        return this.textRenderer.getWidth(this.title) > this.titleMaxWidth();
    }

    private OrderedText fittedTitle() {
        if (!this.isTitleTrimmed()) {
            return this.title.asOrderedText();
        }
        int available = this.titleMaxWidth() - this.textRenderer.getWidth(ScreenTexts.ELLIPSIS);
        StringVisitable trimmed = this.textRenderer.trimToWidth(this.title, available);
        return Language.getInstance().reorder(StringVisitable.concat(trimmed, ScreenTexts.ELLIPSIS));
    }

    private Text priorityLabel() {
        return Text.translatable(PRIORITY_KEY_PREFIX + guardEntity.getBuffPriority().name().toLowerCase() + SHORT_SUFFIX);
    }

    private Tooltip priorityTooltip() {
        return Tooltip.of(Text.translatable(PRIORITY_TOOLTIP_KEY, Text.translatable(PRIORITY_KEY_PREFIX + guardEntity.getBuffPriority().name().toLowerCase())));
    }

    private Text formationLabel() {
        return Text.translatable(FORMATION_KEY_PREFIX + guardEntity.getFollowFormation().name().toLowerCase() + SHORT_SUFFIX);
    }

    private Tooltip formationTooltip() {
        return Tooltip.of(Text.translatable(FORMATION_TOOLTIP_KEY, Text.translatable(FORMATION_KEY_PREFIX + guardEntity.getFollowFormation().name().toLowerCase())));
    }

    class GuardGuiButton extends TexturedButtonWidget {
        private final ButtonTextures activeTexture;
        private final ButtonTextures inactiveTexture;
        private final boolean isFollowButton;

        GuardGuiButton(int x, int y, int width, int height, ButtonTextures activeTexture, ButtonTextures inactiveTexture, boolean isFollowButton, Text tooltip, ButtonWidget.PressAction onPress) {
            super(x, y, width, height, activeTexture, onPress);
            this.activeTexture = activeTexture;
            this.inactiveTexture = inactiveTexture;
            this.isFollowButton = isFollowButton;
            this.setTooltip(Tooltip.of(tooltip));
        }

        private boolean isActiveState() {
            return this.isFollowButton ? guardEntity.isFollowing() : guardEntity.isPatrolling();
        }

        @Override
        public void renderWidget(DrawContext graphics, int mouseX, int mouseY, float partialTicks) {
            ButtonTextures icon = this.isActiveState() ? this.activeTexture : this.inactiveTexture;
            graphics.drawGuiTexture(icon.get(this.isFocused(), this.isSelected()), this.getX(), this.getY(), this.width, this.height);
        }
    }

    @Environment(EnvType.CLIENT)
    enum HeartType {
        CONTAINER(Identifier.ofVanilla("hud/heart/container"), Identifier.ofVanilla("hud/heart/container")),
        NORMAL(Identifier.ofVanilla("hud/heart/full"), Identifier.ofVanilla("hud/heart/half")),
        POISONED(Identifier.ofVanilla("hud/heart/poisoned_full"), Identifier.ofVanilla("hud/heart/poisoned_half")),
        WITHERED(Identifier.ofVanilla("hud/heart/withered_full"), Identifier.ofVanilla("hud/heart/withered_half")),
        FROZEN(Identifier.ofVanilla("hud/heart/frozen_full"), Identifier.ofVanilla("hud/heart/frozen_half"));

        private final Identifier fullTexture;
        private final Identifier halfTexture;

        HeartType(Identifier fullTexture, Identifier halfTexture) {
            this.fullTexture = fullTexture;
            this.halfTexture = halfTexture;
        }

        Identifier getTexture(boolean half) {
            return half ? this.halfTexture : this.fullTexture;
        }
    }
}
