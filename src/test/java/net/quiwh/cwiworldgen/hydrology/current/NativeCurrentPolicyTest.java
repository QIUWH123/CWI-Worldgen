package net.quiwh.cwiworldgen.hydrology.current;

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.config.CurrentConfig;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeCurrentPolicyTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void changedGameplayPolicyRoundTripsAndRejectsOldFieldsWithoutReconnect(GameTestHelper helper) {
        var before = CurrentPolicyPayload.capture(1).policy();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CurrentConfig.ENABLED.set(false);
            CurrentConfig.PLAYER_STRENGTH.set(.25);
            CurrentConfig.BOAT_STRENGTH.set(1.75);
            CurrentConfig.ITEM_DRIFT_SPEED.set(.5);
            CurrentConfig.AQUATIC_HOLD.set(false);
            CurrentConfig.BOAT_SURFACE.set(false);
            var changed = CurrentPolicyPayload.capture(30);
            CurrentPolicyPayload.STREAM_CODEC.encode(buffer, changed);
            var decoded = CurrentPolicyPayload.STREAM_CODEC.decode(buffer);
            helper.assertTrue(decoded.equals(changed), "Current policy changed in transport");
            RiverCurrents.clearClientPolicy();
            RiverCurrents.receivePolicy(new CurrentPolicyPayload(10, before));
            RiverCurrents.receivePolicy(decoded);
            helper.assertTrue(RiverCurrents.clientPolicy().equals(changed.policy()), "Connected client kept its login settings");
            helper.assertTrue(!RiverCurrents.currentFieldRevision(29) && !RiverCurrents.currentFieldRevision(30)
                            && RiverCurrents.currentFieldRevision(31), "Reload accepted a stale current field");
            RiverCurrents.receivePolicy(new CurrentPolicyPayload(20, before));
            helper.assertTrue(RiverCurrents.clientPolicy().equals(changed.policy()), "Delayed policy reverted connected client settings");
            RiverCurrents.clearClientPolicy();
            helper.assertTrue(RiverCurrents.clientPolicy().equals(CurrentGameplayPolicy.WAITING)
                            && RiverCurrents.currentFieldRevision(1), "Disconnect leaked another server's policy");
            System.out.println("CWI_REVIEW_POLICY=codec:true,live_snapshot:true,stale_fields_rejected:true,disconnect_reset:true");
        } finally {
            buffer.release();
            CurrentConfig.ENABLED.set(before.enabled());
            CurrentConfig.PLAYER_STRENGTH.set(before.playerStrength());
            CurrentConfig.BOAT_STRENGTH.set(before.boatStrength());
            CurrentConfig.ITEM_DRIFT_SPEED.set(before.itemDriftSpeed());
            CurrentConfig.AQUATIC_HOLD.set(before.aquaticHold());
            CurrentConfig.BOAT_SURFACE.set(before.boatSurface());
            RiverCurrents.clearClientPolicy();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void gameplayPolicyRejectsUnboundedAndNonFiniteWireValues(GameTestHelper helper) {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 2.01}) {
            boolean rejected = false;
            try { new CurrentGameplayPolicy(true, bad, 1, 1.5, true, true); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Player current policy accepted " + bad);
        }
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            buffer.writeVarLong(1);
            buffer.writeBoolean(true);
            buffer.writeDouble(1);
            buffer.writeDouble(Double.NaN);
            buffer.writeDouble(1.5);
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
            boolean rejected = false;
            try { CurrentPolicyPayload.STREAM_CODEC.decode(buffer); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Malformed remote boat policy was accepted");
        } finally { buffer.release(); }
        helper.succeed();
    }
}
