package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PersistentProjectileEntity.class)
public class PersistentProjectileEntityMixin {

    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void guardvillagers$triggerArrowPassives(EntityHitResult entityHitResult, CallbackInfo ci) {
        PersistentProjectileEntity self = (PersistentProjectileEntity) (Object) this;
        Entity owner = self.getOwner();
        Entity target = entityHitResult.getEntity();

        if (!(owner instanceof GuardEntity guard) || guard.getWorld().isClient()) {
            return;
        }

        if (!(target instanceof LivingEntity livingTarget) || !livingTarget.isAlive()) {
            return;
        }

        List<GuardSpellManager.CategorizedSpell> passives = guard.getSpellManager().getAllPassiveSpells();
        
        int triggeredCount = 0;
        for (GuardSpellManager.CategorizedSpell passive : passives) {
            Spell spell = passive.entry().value();
            
            if (spell.passive == null || spell.passive.triggers == null) {
                continue;
            }

            for (Spell.Trigger trigger : spell.passive.triggers) {
                if (trigger.type != Spell.Trigger.Type.ARROW_IMPACT) {
                    continue;
                }

                if (trigger.chance > 0 && trigger.chance < 1.0f) {
                    if (guard.getRandom().nextFloat() > trigger.chance) {
                        GuardDebugManager.broadcast(guard,
                                "  🎲 " + passive.spellId().getPath() + " chance failed (" + (trigger.chance * 100) + "%)",
                                Formatting.GRAY);
                        continue;
                    }
                }

                GuardDebugManager.broadcast(guard,
                        "⚡ Arrow passive: " + passive.spellId().getPath(),
                        Formatting.YELLOW);

                executeArrowPassiveSpell(guard, livingTarget, passive, spell, trigger);
                triggeredCount++;
                break;
            }
        }

        if (triggeredCount > 0) {
            GuardDebugManager.broadcast(guard,
                    "🏹 Triggered " + triggeredCount + " arrow passive(s)",
                    Formatting.AQUA);
        }
    }

    private void executeArrowPassiveSpell(GuardEntity guard, LivingEntity target, 
            GuardSpellManager.CategorizedSpell passive, Spell spell, Spell.Trigger trigger) {
        
        Vec3d impactPosition = target.getPos().add(0.0, target.getHeight() / 2.0, 0.0);
        
        SpellHelper.ImpactContext context = new SpellHelper.ImpactContext()
                .power(SpellPower.getSpellPower(spell.school, guard))
                .position(impactPosition);

        LivingEntity effectTarget = target;
        if (trigger.target_override != null) {
            String overrideName = trigger.target_override.name();
            if ("CASTER".equals(overrideName)) {
                effectTarget = guard;
            }
        }

        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.CLOUD) {
            try {
                SpellHelper.placeCloud(
                        guard.getWorld(),
                        guard,
                        target,
                        target.getPos(),
                        passive.entry(),
                        context
                );
            } catch (Exception e) {
                GuardDebugManager.broadcast(guard,
                        "  ❌ Cloud placement failed: " + e.getMessage(),
                        Formatting.RED);
            }
        } else if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
            double radius = spell.range > 0 ? spell.range : 3.0;
            List<LivingEntity> areaTargets = guard.getWorld().getEntitiesByClass(
                    LivingEntity.class,
                    target.getBoundingBox().expand(radius),
                    e -> e.isAlive() && e != guard && guard.canTarget(e)
            );

            boolean includeCaster = spell.target.area != null && spell.target.area.include_caster;
            if (includeCaster) {
                SpellHelper.performImpacts(
                        guard.getWorld(),
                        guard,
                        guard,
                        guard,
                        passive.entry(),
                        spell.impacts,
                        context
                );
            }

            for (LivingEntity areaTarget : areaTargets) {
                SpellHelper.performImpacts(
                        guard.getWorld(),
                        guard,
                        areaTarget,
                        guard,
                        passive.entry(),
                        spell.impacts,
                        context
                );
            }
        } else {
            SpellHelper.performImpacts(
                    guard.getWorld(),
                    guard,
                    effectTarget,
                    guard,
                    passive.entry(),
                    spell.impacts,
                    context
            );
        }
    }
}
