package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.field.FieldCoverage;
import cc.sighs.gravityengine.api.math.Vec3d;
import dev.ryanhcode.sable.api.physics.mass.MassTracker;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.Map;
import java.util.WeakHashMap;

/** Replaces Sable's uniform baseline with complete GE field evidence at its mass points.
 * Sable retains all rigid mass, velocity, pose and integration authority. */
public final class SableGravityBridge {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SableGravityBridge.class);
    private static final int MAX_MASS_POSITIONS = 65_536;
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
        if (!Double.isFinite(seconds) || seconds <= 0 || !baseline.isFinite())
            throw new IllegalArgumentException("invalid Sable gravity step");
        var pose = new Pose3d(body.logicalPose());
        if (!unitScale(pose) || !body.getPlot().getContraptions().isEmpty()) {
            applyComOnly(body, handle, mass.getCenterOfMass(), baseline, seconds);
            return;
        }

        var self = body.getSelfMassTracker();
        if (self == null || self.isInvalid()) return;
        var bounds = body.getPlot().getBoundingBox();
        var blocks = new LevelAccelerator(body.getLevel());
        var position = new BlockPos.MutableBlockPos();
        var worldCenter = pose.transformPosition(mass.getCenterOfMass(), new Vector3d());
        var linearVelocity = handle.getLinearVelocity(new Vector3d());
        var angularVelocity = handle.getAngularVelocity(new Vector3d());
        var netForce = new Vector3d();
        var netTorque = new Vector3d();
        var weightedCenter = new Vector3d();
        double capturedMass = 0;
        int checked = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (++checked > MAX_MASS_POSITIONS) {
                        LOGGER.warn("Sable gravity mass capture exceeded {} positions for {}", MAX_MASS_POSITIONS, body.getUniqueId());
                        return;
                    }
                    position.set(x, y, z);
                    var state = blocks.getBlockState(position);
                    double blockMass = PhysicsBlockPropertyHelper.getMass(blocks, position, state);
                    if (!Double.isFinite(blockMass) || blockMass < 0) {
                        LOGGER.warn("Sable gravity found invalid block mass at {} in {}", position, body.getUniqueId());
                        return;
                    }
                    if (blockMass == 0) continue;

                    var blockCenter = MassTracker.BLOCK_CENTER_OF_MASS.apply(blocks, state);
                    var localPoint = new Vector3d(x, y, z).add(blockCenter);
                    weightedCenter.fma(blockMass, localPoint);
                    capturedMass += blockMass;
                    var worldPoint = pose.transformPosition(localPoint, new Vector3d());
                    var radius = worldPoint.sub(worldCenter, new Vector3d());
                    var pointVelocity = new Vector3d(angularVelocity).cross(radius).add(linearVelocity);
                    var sample = GravityEngineApi.sample(body.getLevel(),
                            new Vec3d(worldPoint.x, worldPoint.y, worldPoint.z),
                            new Vec3d(pointVelocity.x, pointVelocity.y, pointVelocity.z).divide(20),
                            body.getLevel().getGameTime(), seconds * 20);
                    if (sample.coverage() != FieldCoverage.COMPLETE) {
                        LOGGER.debug("Sable gravity field coverage incomplete for {}", body.getUniqueId());
                        return;
                    }
                    if (!sample.fieldPresent()) continue;
                    var difference = sample.acceleration().multiply(400).subtract(baseline);
                    var force = new Vector3d(difference.x(), difference.y(), difference.z()).mul(blockMass);
                    netForce.add(force);
                    netTorque.add(new Vector3d(radius).cross(force));
                }
            }
        }

        if (!(capturedMass > 0) || !Double.isFinite(capturedMass)) return;
        weightedCenter.div(capturedMass);
        if (!sameMass(capturedMass, self.getMass()) || !sameMass(capturedMass, mass.getMass())
                || weightedCenter.distance(self.getCenterOfMass()) > 1.0E-7
                || weightedCenter.distance(mass.getCenterOfMass()) > 1.0E-7) {
            LOGGER.warn("Sable gravity mass capture disagrees with current mass tracker for {}", body.getUniqueId());
            return;
        }

        // Sable 2.0.5 Rapier has no usable inverse mass before a new body's first
        // step. Its velocity increment API works then and accepts world vectors.
        var deltaV = netForce.mul(seconds / mass.getMass());
        var orientation = new Quaterniond(pose.orientation()).normalize();
        var localTorqueImpulse = orientation.conjugate(new Quaterniond()).transform(netTorque.mul(seconds), new Vector3d());
        var localDeltaOmega = mass.getInverseInertiaTensor().transform(localTorqueImpulse, new Vector3d());
        var deltaOmega = orientation.transform(localDeltaOmega, new Vector3d());
        if (!finite(deltaV) || !finite(deltaOmega)) {
            LOGGER.warn("Sable gravity produced non-finite velocity correction for {}", body.getUniqueId());
            return;
        }
        if (deltaV.lengthSquared() > 0 || deltaOmega.lengthSquared() > 0)
            handle.addLinearAndAngularVelocity(deltaV, deltaOmega);
    }

    private static boolean unitScale(Pose3d pose) {
        var scale = pose.scale();
        return scale.x() == 1.0 && scale.y() == 1.0 && scale.z() == 1.0;
    }

    private static boolean sameMass(double a, double b) {
        return Double.isFinite(b) && Math.abs(a - b) <= Math.max(1.0E-8, Math.abs(b) * 1.0E-8);
    }

    private static boolean finite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void applyComOnly(ServerSubLevel body, RigidBodyHandle handle,
                                     org.joml.Vector3dc localCenter, Vec3d baseline, double seconds) {
        var center = body.logicalPose().transformPosition(localCenter, new Vector3d());
        var velocity = handle.getLinearVelocity(new Vector3d());
        var sample = GravityEngineApi.sample(body.getLevel(), new Vec3d(center.x,center.y,center.z),
                new Vec3d(velocity.x,velocity.y,velocity.z).divide(20), body.getLevel().getGameTime(), seconds * 20);
        if (sample.coverage() != FieldCoverage.COMPLETE || !sample.fieldPresent()) return;
        // Use the acceleration/velocity boundary: Rapier's impulse API consults
        // cached inverse mass which is not initialized until a new body's first
        // step. Direct delta-v works on that first step and is mass-independent,
        // as gravitational acceleration must be. No force is passed as acceleration.
        var increment = correctiveVelocityIncrement(sample.acceleration(), baseline, seconds);
        handle.addLinearAndAngularVelocity(new Vector3d(increment.x(), increment.y(), increment.z()),
                new Vector3d());
    }
}
