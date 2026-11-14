package net.quiwh.cwiworldgen.block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeAuditRegressionBlockTest {
    @GameTest(template = "empty")
    public static void magmaPadDamagesLandingAndWalkingAtItsActualCollisionSurface(GameTestHelper helper) {
        var padPos = magmaPadFixture(helper);
        var cow = cowAbovePad(helper, padPos);
        float before = cow.getHealth();
        landOnPad(helper, padPos, cow);
        printMagmaContact("landing", padPos, cow);
        helper.assertTrue(cow.getHealth() == before - 1, "Actual magma pad contact did not deal one hot-floor damage");
        for (int i = 0; i < 3; i++) {
            cow.move(MoverType.SELF, new Vec3(.03, -.01, 0));
            printMagmaContact("immune-walk-" + i, padPos, cow);
            assertPadContact(helper, padPos, cow);
        }
        helper.assertTrue(cow.getHealth() == before - 1, "Repeated contact bypassed normal damage immunity");
        cow.invulnerableTime = 0;
        cow.move(MoverType.SELF, new Vec3(.03, -.01, 0));
        printMagmaContact("cooled-walk", padPos, cow);
        assertPadContact(helper, padPos, cow);
        helper.assertTrue(cow.getHealth() == before - 2, "Walking on the pad stopped dealing hot-floor damage");
        helper.succeed();
    }

    private static void printMagmaContact(String stage, BlockPos padPos, Cow cow) {
        System.out.println("CWI_MAGMA_CONTACT=" + stage + ",health:" + cow.getHealth() + ",ground:" + cow.onGround()
                + ",position:" + cow.position() + ",feet:" + cow.blockPosition() + ",pad:" + padPos
                + ",legacy:" + cow.getOnPosLegacy() + ",supported:" + cow.isSupportedBy(padPos)
                + ",bounds:" + cow.getBoundingBox()
                + ",vertical:" + cow.verticalCollision + ",below:" + cow.verticalCollisionBelow
                + ",immunity:" + cow.invulnerableTime + ",velocity:" + cow.getDeltaMovement());
    }

    @GameTest(template = "empty")
    public static void magmaPadRespectsCarefulStepsAndDamageImmunity(GameTestHelper helper) {
        var padPos = magmaPadFixture(helper);
        for (int mode = 0; mode < 3; mode++) {
            var cow = cowAbovePad(helper, padPos);
            if (mode == 0) cow.setShiftKeyDown(true);
            if (mode == 1) cow.setInvulnerable(true);
            if (mode == 2) cow.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
            float before = cow.getHealth();
            landOnPad(helper, padPos, cow);
            cow.move(MoverType.SELF, new Vec3(.03, -.01, 0));
            assertPadContact(helper, padPos, cow);
            helper.assertTrue(cow.getHealth() == before, "Magma pad ignored careful stepping or immunity mode " + mode);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void campfireHeatRequiresADryLitFireForBothVariants(GameTestHelper helper) {
        for (var block : List.of(Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE)) {
            for (boolean lit : new boolean[]{false, true}) {
                for (boolean waterlogged : new boolean[]{false, true}) {
                    var state = block.defaultBlockState().setValue(CampfireBlock.LIT, lit)
                            .setValue(CampfireBlock.WATERLOGGED, waterlogged);
                    int heat = HeatPropagation.determineHeat(state);
                    helper.assertTrue(lit && !waterlogged ? heat > 0 : heat == 0,
                            "Wrong campfire heat " + heat + " for " + state);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void glowspursPlayOneSoundOnEachPowerTransition(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 3, 3));
        var block = (GlowspursBlock) WorldgenBlocks.get("glowspurs");
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(pos, block.defaultBlockState(), 2);
        var cow = EntityType.COW.create(level);
        helper.assertTrue(cow != null, "Could not create glowspurs occupant");
        var played = new ArrayList<PlayLevelSoundEvent.AtPosition>();
        Consumer<PlayLevelSoundEvent.AtPosition> listener = event -> {
            if (event.getLevel() == level && event.getPosition().equals(Vec3.atCenterOf(pos))) played.add(event);
        };
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, listener);
        try {
            block.entityInside(level.getBlockState(pos), level, pos, cow);
            helper.assertTrue(level.getBlockState(pos).getValue(GlowspursBlock.POWERED), "Glowspurs did not activate");
            block.entityInside(level.getBlockState(pos), level, pos, cow);
            helper.assertTrue(played.size() == 1, "Activation must emit exactly one sound");
            assertGlowspursSound(helper, played.get(0), 1F, .6F);
            block.tick(level.getBlockState(pos), level, pos, RandomSource.create(1));
            helper.assertTrue(!level.getBlockState(pos).getValue(GlowspursBlock.POWERED), "Glowspurs did not release");
            helper.assertTrue(played.size() == 2, "Release must emit exactly one sound");
            assertGlowspursSound(helper, played.get(1), .6F, .4F);
            block.tick(level.getBlockState(pos), level, pos, RandomSource.create(2));
            helper.assertTrue(played.size() == 2, "Unpowered glowspurs emitted a release sound");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    private static void assertGlowspursSound(GameTestHelper helper, PlayLevelSoundEvent.AtPosition sound,
                                              float volume, float pitch) {
        helper.assertTrue(sound.getSound() != null && sound.getSound().value() == SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH,
                "Glowspurs played the wrong transition sound");
        helper.assertTrue(sound.getSource() == SoundSource.BLOCKS && sound.getOriginalVolume() == volume
                && sound.getOriginalPitch() == pitch, "Glowspurs transition lost its original source, volume or pitch");
    }

    private static BlockPos magmaPadFixture(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 4, 4));
        for (var support : BlockPos.betweenClosed(pos.offset(-1, -2, -1), pos.offset(1, -2, 1))) {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 2);
        }
        // A cow's .9-block-wide body crosses the neighboring columns while walking.
        // Keep those columns lava too, without a raised stone ledge to step onto.
        for (var lava : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, -1, 1))) {
            level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 2);
        }
        var pad = WorldgenBlocks.get("magma_pad").defaultBlockState();
        level.setBlock(pos, pad, 2);
        helper.assertTrue(pad.canSurvive(level, pos), "Magma pad fixture has invalid support");
        return pos;
    }

    private static Cow cowAbovePad(GameTestHelper helper, BlockPos pos) {
        var cow = EntityType.COW.create(helper.getLevel());
        helper.assertTrue(cow != null, "Could not create magma pad occupant");
        cow.setNoAi(true);
        cow.setPos(pos.getX() + .5, pos.getY() + .25, pos.getZ() + .5);
        return cow;
    }

    private static void landOnPad(GameTestHelper helper, BlockPos pos, Cow cow) {
        // Real collision and inside-block dispatch, without unrelated entity/fluid ticks.
        cow.move(MoverType.SELF, new Vec3(0, -.5, 0));
        assertPadContact(helper, pos, cow);
    }

    private static void assertPadContact(GameTestHelper helper, BlockPos pos, Cow cow) {
        helper.assertTrue(cow.onGround() && cow.blockPosition().equals(pos.below())
                        && Math.abs(cow.getY() - (pos.getY() - 1. / 16.)) < 1.e-6,
                "Cow left the pad's actual collision surface: position=" + cow.position() + "; ground=" + cow.onGround());
        double lavaTop = pos.getY() - 1 + helper.getLevel().getFluidState(pos.below()).getHeight(helper.getLevel(), pos.below());
        helper.assertTrue(cow.getBoundingBox().minY > lavaTop + .01, "Lava contact would make hot-floor damage ambiguous");
    }
}
