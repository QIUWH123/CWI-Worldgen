package net.quiwh.cwiworldgen.hydrology.current;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Gameplay policy regressions use actual native entity types and elapsed server ticks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeStreamMotionTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void aquaticBobberAndTaggedExemptionsKeepOrdinaryMobsEligible(GameTestHelper helper) {
        var level = helper.getLevel();
        var fish = EntityType.COD.create(level);
        var drowned = EntityType.DROWNED.create(level);
        var cow = EntityType.COW.create(level);
        var zombie = EntityType.ZOMBIE.create(level);
        var floating = EntityType.ARMOR_STAND.create(level);
        var bobber = new FishingHook(EntityType.FISHING_BOBBER, level);
        helper.assertTrue(CurrentEntityPolicy.ignored(fish) && CurrentEntityPolicy.ignored(drowned),
                "Aquatic mobs receive an additional wasteland current");
        helper.assertTrue(CurrentEntityPolicy.ignored(bobber), "Fishing bobber loses stable retrieval behavior");
        helper.assertTrue(CurrentEntityPolicy.ignored(zombie), "Datapack ignores_current type tag is ineffective");
        helper.assertTrue(!CurrentEntityPolicy.ignored(cow), "Ordinary land mob is accidentally exempted");
        helper.assertTrue(CurrentEntityPolicy.drifts(floating), "Datapack drifts_in_current tag is ineffective");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 80)
    public static void reeledItemsKeepTheirImpulseForFortyTicks(GameTestHelper helper) {
        ItemEntity item = new ItemEntity(helper.getLevel(), 0, 80, 0, new ItemStack(Items.COD));
        item.setDeltaMovement(.37, .23, -.18);
        CurrentEntityPolicy.markReeled(item);
        helper.assertTrue(CurrentEntityPolicy.ignored(item), "Newly reeled item has no grace interval");
        helper.assertTrue(item.getDeltaMovement().x == .37 && item.getDeltaMovement().y == .23
                        && item.getDeltaMovement().z == -.18, "Marking retrieval changed the actual catch impulse");
        helper.runAfterDelay(39, () -> helper.assertTrue(CurrentEntityPolicy.ignored(item),
                "Reeled item became eligible before forty server ticks"));
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(!CurrentEntityPolicy.ignored(item), "Reeled grace never expires");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void actualFishingRetrievalProtectsNewCatchesAndHookedItems(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 3, 1));
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "cwi-retrieval-test"));
        player.setPos(origin.getX(), origin.getY(), origin.getZ());
        ItemStack rod = new ItemStack(Items.FISHING_ROD);
        player.setItemInHand(InteractionHand.MAIN_HAND, rod);
        FishingHook caught = new FishingHook(EntityType.FISHING_BOBBER, level);
        caught.setOwner(player);
        caught.setPos(origin.getX() + 4, origin.getY(), origin.getZ());
        AABB bounds = caught.getBoundingBox().inflate(1);
        var before = level.getEntitiesOfClass(ItemEntity.class, bounds).stream().map(ItemEntity::getUUID).toList();
        setHookField(caught, "nibble", 10);
        helper.assertTrue(caught.retrieve(rod) > 0, "Native fishing retrieval did not produce a catch");
        var catches = level.getEntitiesOfClass(ItemEntity.class, bounds).stream()
                .filter(item -> !before.contains(item.getUUID())).toList();
        helper.assertTrue(!catches.isEmpty(), "Native loot retrieval did not spawn an item");
        for (ItemEntity item : catches) {
            helper.assertTrue(CurrentEntityPolicy.ignored(item), "Actual retrieve mixin did not protect spawned catch");
            helper.assertTrue(item.getDeltaMovement().x < -.3 && item.getDeltaMovement().y > 0,
                    "Actual catch lost its vanilla impulse toward the fisher");
            item.discard();
        }

        FishingHook hooked = new FishingHook(EntityType.FISHING_BOBBER, level);
        hooked.setOwner(player);
        hooked.setPos(origin.getX() + 4, origin.getY(), origin.getZ());
        ItemEntity existing = new ItemEntity(level, hooked.getX(), hooked.getY(), hooked.getZ(), new ItemStack(Items.STICK));
        existing.setDeltaMovement(.03, .02, .01);
        setHookField(hooked, "hookedIn", existing);
        helper.assertTrue(hooked.retrieve(rod) == 3, "Existing hooked item did not use native retrieve branch");
        helper.assertTrue(CurrentEntityPolicy.ignored(existing), "Actual pullEntity mixin did not protect hooked item");
        helper.assertTrue(existing.getDeltaMovement().x < -.3 && existing.getDeltaMovement().y == .02,
                "Protecting hooked item changed its native pull impulse");
        helper.succeed();
    }

    private static void setHookField(FishingHook hook, String name, Object value) {
        try {
            var field = FishingHook.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(hook, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Native fishing fixture no longer matches field " + name, exception);
        }
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void surfaceControllerDoesNotLaunchOrBrakeAboveWaterBoats(GameTestHelper helper) {
        helper.assertTrue(CurrentMotion.surfaceVelocity(80, -.12, 83) > 0
                        && CurrentMotion.surfaceVelocity(80, -.12, 83) <= .15,
                "Surface following launches the boat or leaves it sinking");
        helper.assertTrue(CurrentMotion.surfaceVelocity(82.9, -.12, 83) < .05,
                "Surface correction does not damp near its target");
        helper.assertTrue(CurrentMotion.surfaceVelocity(83, -.12, 83) == 0,
                "Surface following overshoots a reached target");
        helper.assertTrue(CurrentMotion.surfaceVelocity(85, -.12, 83) == -.12,
                "Boat flying above the surface is held up");
        helper.succeed();
    }
    private NativeStreamMotionTest() {}
}
