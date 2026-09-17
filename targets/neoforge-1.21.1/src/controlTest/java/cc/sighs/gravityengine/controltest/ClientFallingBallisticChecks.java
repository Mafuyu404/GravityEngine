package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityFallingBlockAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Same initial operands, actual transformed server/client tick, native entity-data decoding.
 * Actors are unregistered so no real tracking correction can conceal prediction divergence. */
final class ClientFallingBallisticChecks {
    private record Step(Vec3 position, Vec3 velocity, boolean ground) {}
    private record Case(Direction down, List<SynchedEntityData.DataValue<?>> data, List<Step> steps) {}
    private static volatile List<Case> cases;
    private static volatile Throwable failure;
    private static Vec3 start;
    private static boolean started, done;
    static boolean tick(Minecraft mc) {
        if (done) return true;
        if (failure != null) throw new AssertionError("server ballistic fixture", failure);
        if (!started) {
            started = true;
            start = mc.player.position().add(0, 90, 0);
            var player = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level = mc.getSingleplayerServer().getPlayerList().getPlayer(player).serverLevel();
                    var results = new ArrayList<Case>();
                    for (var down : Direction.values()) {
                        var axis = down.getNormal();
                        var acceleration = new Vec3d(axis.getX(), axis.getY(), axis.getZ()).multiply(.017);
                        var actor = new FallingBlockEntity(EntityType.FALLING_BLOCK, level);
                        actor.setPos(start); actor.setDeltaMovement(.013, .007, -.009);
                        var steps = new ArrayList<Step>();
                        try (var lease = GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID, GravityFieldDefinition.named(
                                ResourceLocation.fromNamespaceAndPath("gravityengine_control_tests", "ballistic_parity"),
                                q -> new GravityFieldSample(acceleration), new GravityInfluenceVolume() {
                                    public boolean contains(Vec3d point) { return point.distanceSquared(new Vec3d(start.x,start.y,start.z)) < 2500; }
                                    public Optional<GravityFieldBounds> finiteBounds() { return Optional.of(new GravityFieldBounds(
                                            start.x-50,start.y-50,start.z-50,start.x+50,start.y+50,start.z+50)); }
                                }, GravityFieldCompositionMode.OVERRIDE, 1))) {
                            for (int i=0;i<40;i++) {
                                actor.tick();
                                steps.add(new Step(actor.position(),actor.getDeltaMovement(),actor.onGround()));
                                if (actor.isRemoved()) throw new AssertionError("unexpected server free-flight landing");
                            }
                            results.add(new Case(down, actor.getEntityData().getNonDefaultValues(), List.copyOf(steps)));
                        } finally { actor.discard(); }
                    }
                    cases = List.copyOf(results);
                } catch (Throwable error) { failure = error; }
            });
            return false;
        }
        if (cases == null) return false;
        // The client intentionally has no field registry and no evaluator to reconstruct.
        GravityFieldRuntime.remove(mc.level);
        for (var fixture : cases) {
            var actor = new FallingBlockEntity(EntityType.FALLING_BLOCK, mc.level);
            actor.setPos(start); actor.setDeltaMovement(.013, .007, -.009);
            actor.getEntityData().assignValues(fixture.data);
            var snapshot = ((GravityFallingBlockAccess)actor).gravityengine$ballisticSnapshot();
            if (snapshot == null
                    || snapshot.down() != fixture.down
                    || !snapshot.customAcceleration()
                    || !snapshot.engineCollision()) {
                throw new AssertionError(
                        "snapshot decoding"
                );
            }
            for (int i=0;i<fixture.steps.size();i++) {
                actor.tick();
                var expected = fixture.steps.get(i);
                if (actor.position().distanceToSqr(expected.position)>1e-16
                        || actor.getDeltaMovement().distanceToSqr(expected.velocity)>1e-16
                        || actor.onGround()!=expected.ground)
                    throw new AssertionError("ballistic divergence " + fixture.down + " tick=" + i + " actual="+actor.position()+" expected="+expected.position);
                if ((i+1)%20==0) {
                    var before=actor.position();
                    actor.lerpTo(expected.position.x,expected.position.y,expected.position.z,0,0,3);
                    if (actor.position().distanceToSqr(before)>1e-16) throw new AssertionError("tracking correction jump");
                }
            }
            if (GravityFieldRuntime.getIfPresent(mc.level)!=null) throw new AssertionError("client ballistic tick created a field registry");
            actor.discard();
        }
        var waiting = new FallingBlockEntity(EntityType.FALLING_BLOCK, mc.level);
        waiting.setPos(start); waiting.tick();
        if (waiting.getDeltaMovement().lengthSqr()!=0) throw new AssertionError("missing initial snapshot injected -Y gravity");
        waiting.discard();
        done=true;
        System.out.println("CLIENT_FALLING_BALLISTIC_PARITY_PASSED six_axes ticks=240 no_registry corrections=12 missing_snapshot");
        return true;
    }
}
