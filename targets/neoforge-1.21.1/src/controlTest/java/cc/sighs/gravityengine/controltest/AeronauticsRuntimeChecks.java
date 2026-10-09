package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.GravityFieldDefinition;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;

/** Executes the real native force methods. Reflection keeps Aeronautics out of absent-mod test loading. */
final class AeronauticsRuntimeChecks {
    static java.util.Set<String> run(ServerLevel level) throws ReflectiveOperationException {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d(); pose.position().set(80, 150, 80); pose.orientation().rotateZ(.4);
        var body = (ServerSubLevel)container.allocateSubLevel(java.util.UUID.randomUUID(), 48, 0, pose);
        var plot = body.getPlot();
        try {
            plot.newEmptyChunk(plot.getCenterChunk());
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.END_STONE.defaultBlockState(), 3);
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), 3);
            body.buildMassTracker(); body.updateMergedMassData(1);
            ContactBehaviorChecks.check(body.getMassTracker().getCenterOfMass() != null, "lift fixture native mass initialized");
            body.updateLastPose(); body.updateBoundingBox();
            var type = Class.forName("dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon");
            var graphType = Class.forName("dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.graph.BalloonLayerGraph");
            var graph = graphType.getConstructor(int.class).newInstance(0);
            var balloon = type.getConstructors()[0].newInstance(level,
                    new dev.ryanhcode.sable.util.LevelAccelerator(level), plot.getCenterBlock(), graph,
                    new it.unimi.dsi.fastutil.objects.ObjectArrayList<>());
            // A one-volume, already-filled native balloon fixture; gas thermodynamics stay upstream.
            field(type.getSuperclass(), "capacity").setInt(balloon, 1);
            field(type, "totalFilledVolume").setDouble(balloon, 1);
            field(type, "totalLift").setDouble(balloon, 2);
            ((Vector3d)field(type, "averagePosition").get(balloon)).set(.5, .5, .5);
            var apply = type.getMethod("applyForces", double.class);
            var floating = body.getFloatingBlockController();
            for (var acceleration : new Vec3d[]{new Vec3d(.03, 0, 0), new Vec3d(0,.03,0),
                    new Vec3d(.01,-.02,.03), Vec3d.ZERO}) {
                try (var publication = GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,
                        GravityFieldDefinition.block(ResourceLocation.fromNamespaceAndPath("gravityengine", "lift_test"),
                                BlockPos.ZERO, query -> new GravityFieldSample(acceleration),
                                GravityFields.infiniteInfluence(), GravityFieldCompositionMode.OVERRIDE, 1))) {
                    for (boolean complete : new boolean[]{true, false}) {
                        com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> complete
                                ? FieldCoverage.COMPLETE : FieldCoverage.INCOMPLETE);
                        var impulse = new Vector3d();
                        floating.physicsTick(1, .025, new Vector3d(), new Vector3d(), impulse, new Vector3d());
                        var nativeGravity = dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData
                                .getGravity(level, body.logicalPose().position());
                        var expectedWorld = complete ? new Vector3d(acceleration.x(),acceleration.y(),acceleration.z()).mul(400)
                                : nativeGravity;
                        var expectedLocal = body.logicalPose().orientation().transformInverse(expectedWorld, new Vector3d());
                        var floatingOperand = (Vector3d)field(floating.getClass(), "localGravity").get(null);
                        ContactBehaviorChecks.check(floatingOperand.distance(expectedLocal) < 1e-8,
                                "floating operand complete="+complete+" acceleration="+acceleration);
                        apply.invoke(balloon, .025);
                        var balloonOperand = (Vector3d)field(type, "gravity").get(null);
                        ContactBehaviorChecks.check(balloonOperand.distance(expectedLocal) < 1e-8,
                                "balloon operand complete="+complete+" acceleration="+acceleration);
                        if (expectedLocal.lengthSquared() > 0) {
                            var force = (Vector3d)field(type, "force").get(null);
                            ContactBehaviorChecks.check(force.dot(expectedLocal) < 0,
                                    "native balloon force opposes sampled gravity");
                        }
                    }
                }
            }
            System.out.println("AERONAUTICS_LIFT_CHECKS_PASSED native-balloon native-floating complete incomplete zero oblique inverted");
            return java.util.Set.of("balloon-gravity", "floating-gravity", "incomplete-native");
        } finally {
            com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> FieldCoverage.COMPLETE);
            container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            container.processSubLevelRemovals();
        }
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
