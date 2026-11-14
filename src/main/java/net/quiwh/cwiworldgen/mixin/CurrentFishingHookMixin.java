package net.quiwh.cwiworldgen.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.quiwh.cwiworldgen.hydrology.current.CurrentEntityPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Protect the real retrieval impulse, including catches hooked after they already existed. */
@Mixin(FishingHook.class)
public abstract class CurrentFishingHookMixin {
    @Redirect(method = "retrieve", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/item/ItemEntity;setDeltaMovement(DDD)V"))
    private void cwiWorldgen$protectCaughtItem(ItemEntity item, double x, double y, double z) {
        item.setDeltaMovement(x, y, z);
        CurrentEntityPolicy.markReeled(item);
    }

    @Inject(method = "pullEntity", at = @At("HEAD"))
    private void cwiWorldgen$protectHookedItem(Entity entity, CallbackInfo ci) {
        if (entity instanceof ItemEntity item) CurrentEntityPolicy.markReeled(item);
    }
}
