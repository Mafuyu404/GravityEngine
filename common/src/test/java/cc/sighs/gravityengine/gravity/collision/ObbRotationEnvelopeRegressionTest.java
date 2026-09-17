package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObbRotationEnvelopeRegressionTest {
    @Test
    void sampledCornersAcrossTheArcAreContainedByTheEnvelope() {
        OrientedBox start = new OrientedBox(
                new Vec3d(100.0D, 200.0D, 300.0D),
                new Vec3d(1.0D, 2.0D, 3.0D),
                OrthonormalFrame3d.IDENTITY
        );
        Vec3d pivotWorld = start.localPointToWorld(
                new Vec3d(0.5D, -0.25D, 0.75D)
        );
        ObbAngularTrajectory trajectory = new ObbAngularTrajectory(
                start,
                Quatd.rotationZ(0.7D),
                ObbAngularTrajectory.Pivot.at(
                        start,
                        pivotWorld,
                        start.orientation()
                )
        );
        OrientedBox envelope = trajectory.envelope(0.0D, 1.0D);

        for (int sample = 0; sample <= 10; sample++) {
            OrientedBox body = trajectory.bodyAt(sample / 10.0D);
            assertVecEquals(
                    pivotWorld,
                    body.localPointToWorld(trajectory.pivot().body()),
                    1.0E-9D
            );
            for (int bits = 0; bits < 8; bits++) {
                Vec3d corner = body.corner(
                        (bits & 1) == 0 ? -1.0D : 1.0D,
                        (bits & 2) == 0 ? -1.0D : 1.0D,
                        (bits & 4) == 0 ? -1.0D : 1.0D
                );
                assertContained(envelope, corner, 1.0E-7D);
            }
        }
    }

    @Test
    void tinyRotationKeepsTheStartingPoseAndPivotFixed() {
        OrientedBox start = new OrientedBox(
                new Vec3d(1.0D, 2.0D, 3.0D),
                new Vec3d(1.0D, 1.0D, 1.0D),
                OrthonormalFrame3d.IDENTITY
        );
        Vec3d pivotWorld = start.localPointToWorld(new Vec3d(0.25D, 0.5D, -0.25D));
        ObbAngularTrajectory trajectory = new ObbAngularTrajectory(
                start,
                Quatd.rotationZ(1.0E-16D),
                ObbAngularTrajectory.Pivot.at(
                        start,
                        pivotWorld,
                        start.orientation()
                )
        );

        OrientedBox envelope = trajectory.envelope(0.0D, 1.0D);

        assertEquals(start.orientation(), envelope.orientation());
        assertVecEquals(
                pivotWorld,
                envelope.localPointToWorld(trajectory.pivot().body()),
                0.0D
        );
    }

    @Test
    void nearPiRotationStillProducesConservativeContainment() {
        OrientedBox start = new OrientedBox(
                Vec3d.ZERO,
                new Vec3d(0.5D, 1.5D, 2.5D),
                OrthonormalFrame3d.IDENTITY
        );
        Vec3d pivotWorld = start.localPointToWorld(new Vec3d(0.5D, 0.0D, 0.0D));
        ObbAngularTrajectory trajectory = new ObbAngularTrajectory(
                start,
                Quatd.rotationZ(Math.PI * 0.999D),
                ObbAngularTrajectory.Pivot.at(
                        start,
                        pivotWorld,
                        start.orientation()
                )
        );
        OrientedBox envelope = trajectory.envelope(0.0D, 1.0D);

        for (int sample = 0; sample <= 8; sample++) {
            OrientedBox body = trajectory.bodyAt(sample / 8.0D);
            for (int bits = 0; bits < 8; bits++) {
                Vec3d corner = body.corner(
                        (bits & 1) == 0 ? -1.0D : 1.0D,
                        (bits & 2) == 0 ? -1.0D : 1.0D,
                        (bits & 4) == 0 ? -1.0D : 1.0D
                );
                assertContained(envelope, corner, 1.0E-6D);
            }
        }
    }

    private static void assertContained(
            OrientedBox envelope,
            Vec3d point,
            double tolerance
    ) {
        Vec3d local = envelope.worldPointToLocal(point);
        Vec3d half = envelope.halfExtents();
        assertTrue(Math.abs(local.x()) <= half.x() + tolerance);
        assertTrue(Math.abs(local.y()) <= half.y() + tolerance);
        assertTrue(Math.abs(local.z()) <= half.z() + tolerance);
    }

    private static void assertVecEquals(Vec3d expected, Vec3d actual, double eps) {
        assertEquals(expected.x(), actual.x(), eps);
        assertEquals(expected.y(), actual.y(), eps);
        assertEquals(expected.z(), actual.z(), eps);
    }
}
