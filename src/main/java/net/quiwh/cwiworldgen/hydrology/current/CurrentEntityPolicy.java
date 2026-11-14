package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.quiwh.cwiworldgen.config.CurrentConfig;

/** Selects only the additional CWI current; it never changes vanilla/global fluid-push eligibility. */
public final class CurrentEntityPolicy {
    private CurrentEntityPolicy() {}
    public static final TagKey<EntityType<?>> IGNORES = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "ignores_current"));
    public static final TagKey<EntityType<?>> DRIFTS = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "drifts_in_current"));
    private static final String REEL_UNTIL = "cwi_worldgen_reeled_until";

    public static void markReeled(ItemEntity item) {
        item.getPersistentData().putLong(REEL_UNTIL, item.level().getGameTime() + 40);
    }

    public static boolean ignored(Entity entity) {
        if (entity instanceof FishingHook || entity.getType().is(IGNORES)) return true;
        if (entity instanceof ItemEntity && entity.getPersistentData().getLong(REEL_UNTIL) > entity.level().getGameTime()) return true;
        boolean aquaticHold = entity.level().isClientSide ? RiverCurrents.clientPolicy().aquaticHold() : CurrentConfig.AQUATIC_HOLD.get();
        if (!aquaticHold || drifts(entity)) return false;
        MobCategory category = entity.getType().getCategory();
        return category == MobCategory.WATER_CREATURE || category == MobCategory.WATER_AMBIENT
                || category == MobCategory.UNDERGROUND_WATER_CREATURE || category == MobCategory.AXOLOTLS
                || entity instanceof Drowned || entity instanceof Guardian || entity.getType().is(EntityTypeTags.AQUATIC)
                || entity instanceof LivingEntity living && living.canBreatheUnderwater()
                        && !entity.getType().is(EntityTypeTags.UNDEAD);
    }

    public static boolean drifts(Entity entity) { return entity.getType().is(DRIFTS); }
    public static double multiplier(Entity entity) {
        return entity instanceof Player ? entity.level().isClientSide
                ? RiverCurrents.clientPolicy().playerStrength() : CurrentConfig.PLAYER_STRENGTH.get()
                : CurrentConfig.MOB_STRENGTH.get();
    }
}
