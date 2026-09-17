package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrthonormalFrame3dTest {
    private static final double TIGHT = 1.0E-14D;

    @Test
    void identityIsTheCanonicalWorldFrame() {
        assertEquals(Vec3d.X, OrthonormalFrame3d.IDENTITY.axisX());
        assertEquals(Vec3d.Y, OrthonormalFrame3d.IDENTITY.axisY());
        assertEquals(Vec3d.Z, OrthonormalFrame3d.IDENTITY.axisZ());
    }

    @Test
    void vectorRoundTripsAreExactForIdentity() {
        Vec3d value = new Vec3d(1.0D, -2.0D, 3.5D);

        assertEquals(value, OrthonormalFrame3d.IDENTITY.worldToLocal(value));
        assertEquals(value, OrthonormalFrame3d.IDENTITY.localToWorld(value));
    }

    @Test
    void rotatedBasisRoundTripsWorldAndLocalVectors() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationY(0.7D)
                        .multiply(Quatd.rotationX(-0.3D))
                        .normalized()
        );
        Vec3d world = new Vec3d(1.0D, 2.0D, -4.0D);
        Vec3d local = frame.worldToLocal(world);

        assertVecEquals(world, frame.localToWorld(local), TIGHT);
        assertVecEquals(local, frame.worldToLocal(frame.localToWorld(local)), TIGHT);
    }

    @Test
    void pointTransformsUseTheSuppliedOrigin() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationY(-0.4D).normalized()
        );
        Vec3d origin = new Vec3d(10.0D, 20.0D, 30.0D);
        Vec3d point = new Vec3d(11.0D, 18.0D, 33.0D);

        assertVecEquals(
                point,
                frame.localPointToWorld(
                        frame.worldPointToLocal(point, origin),
                        origin
                ),
                TIGHT
        );
    }

    @Test
    void invalidBasesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrthonormalFrame3d(
                        2.0D, 0.0D, 0.0D,
                        0.0D, 1.0D, 0.0D,
                        0.0D, 0.0D, 1.0D
                ));
        assertThrows(IllegalArgumentException.class,
                () -> new OrthonormalFrame3d(
                        1.0D, 0.0D, 0.0D,
                        1.0D, 0.0D, 0.0D,
                        0.0D, 0.0D, 1.0D
                ));
        assertThrows(IllegalArgumentException.class,
                () -> new OrthonormalFrame3d(
                        1.0D, 0.0D, 0.0D,
                        0.0D, -1.0D, 0.0D,
                        0.0D, 0.0D, 1.0D
                ));
    }

    @Test
    void handednessIsRightHanded() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(1.1D).normalized()
        );

        assertTrue(frame.axisX().cross(frame.axisY())
                .distance(frame.axisZ()) < 1.0E-12D);
    }

    private static void assertVecEquals(
            Vec3d expected,
            Vec3d actual,
            double tolerance
    ) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }
}
