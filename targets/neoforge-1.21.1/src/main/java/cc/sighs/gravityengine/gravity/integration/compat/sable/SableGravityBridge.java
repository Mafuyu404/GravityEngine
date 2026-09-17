package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.math.Vec3d;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Vector3d;
import java.util.Map;
import java.util.WeakHashMap;

/** COM-only gravity replacement by a corrective velocity increment before each physics substep.
 * The pipeline retains its baseline acceleration; this bridge cancels that baseline
 * only for bodies with an active field contribution. It does not model tidal torque. */
public final class SableGravityBridge {
    private static final Map<SubLevelPhysicsSystem, Vec3d> BASELINES = new WeakHashMap<>();
    private SableGravityBridge() {}

    public static void initialized(SubLevelPhysicsSystem system, double x, double y, double z) {
        BASELINES.put(system, new Vec3d(x,y,z));
    }

    public static Vec3d correctiveVelocityIncrement(Vec3d accelerationTicks, Vec3d baselineSeconds,
                                                    double seconds) {
        if (!accelerationTicks.isFinite() || !baselineSeconds.isFinite()
                || !Double.isFinite(seconds) || seconds <= 0)
            throw new IllegalArgumentException("invalid Sable gravity inputs");
        return accelerationTicks.multiply(400).subtract(baselineSeconds).multiply(seconds);
    }

    public static void apply(ServerSubLevel body, SubLevelPhysicsSystem system,
                             RigidBodyHandle handle, double seconds) {
        if (!Boolean.parseBoolean(System.getProperty("gravityengine.sableGravity", "true"))) return;
        var mass = body.getMassTracker();
        if (mass == null || mass.isInvalid()) return;
        var baseline = BASELINES.get(system);
        if (baseline == null) throw new IllegalStateException("Sable physics baseline was not captured");
        var center = body.logicalPose().transformPosition(mass.getCenterOfMass(), new Vector3d());
        var velocity = handle.getLinearVelocity(new Vector3d());
        var sample = GravityEngineApi.sample(body.getLevel(), new Vec3d(center.x,center.y,center.z),
                new Vec3d(velocity.x,velocity.y,velocity.z).divide(20), body.getLevel().getGameTime(), seconds * 20);
        if (sample.coverage() != cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE || !sample.fieldPresent()) return;
        // Use the acceleration/velocity boundary: Rapier's impulse API consults
        // cached inverse mass which is not initialized until a new body's first
        // step. Direct delta-v works on that first step and is mass-independent,
        // as gravitational acceleration must be. No force is passed as acceleration.
        var increment = correctiveVelocityIncrement(sample.acceleration(), baseline, seconds);
        handle.addLinearAndAngularVelocity(new Vector3d(increment.x(), increment.y(), increment.z()),
                new Vector3d());
    }
}
