package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BodyOrientation3dTest {
    private static final double TIGHT = 1.0E-12D;

    @Test
    void quaternionFrameQuaternionRoundTripPreservesRotation() {
        Quatd[] samples = {
                Quatd.IDENTITY,
                Quatd.rotationX(0.2D),
                Quatd.rotationY(-1.4D),
                Quatd.rotationZ(2.7D),
                Quatd.rotationY(1.1D).multiply(Quatd.rotationX(-0.8D))
        };

        for (Quatd sample : samples) {
            Quatd normalized = sample.normalized();
            Quatd roundTrip = BodyOrientation3d.quaternion(
                    BodyOrientation3d.frame(normalized)
            );
            assertEquals(0.0D,
                    Quatd.angularDistance(normalized, roundTrip),
                    TIGHT);
        }
    }

    @Test
    void frameQuaternionFrameRoundTripPreservesAxes() {
        Quatd[] samples = {
                Quatd.rotationX(-2.9D),
                Quatd.rotationY(0.35D).multiply(Quatd.rotationZ(0.9D)),
                Quatd.rotationZ(-3.0D).multiply(Quatd.rotationX(1.2D))
        };

        for (Quatd sample : samples) {
            OrthonormalFrame3d frame = BodyOrientation3d.frame(sample.normalized());
            OrthonormalFrame3d roundTrip = BodyOrientation3d.frame(
                    BodyOrientation3d.quaternion(frame)
            );
            assertTrue(BodyOrientation3d.matches(frame, roundTrip));
        }
    }

    @Test
    void tinyAngularDifferencesRemainDistinguishable() {
        Quatd first = Quatd.IDENTITY;
        Quatd second = Quatd.rotationY(1.0E-9D);

        assertEquals(1.0E-9D,
                BodyOrientation3d.angularDistance(first, second),
                1.0E-15D);
    }

    @Test
    void arbitraryRotationsProduceUnitAxes() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                new Quatd(0.2D, -0.4D, 0.7D, 0.5D).normalized()
        );

        assertEquals(1.0D, frame.axisX().lengthSquared(), 1.0E-14D);
        assertEquals(1.0D, frame.axisY().lengthSquared(), 1.0E-14D);
        assertEquals(1.0D, frame.axisZ().lengthSquared(), 1.0E-14D);
        assertTrue(Math.abs(frame.axisX().dot(frame.axisY())) < 1.0E-14D);
    }

    @Test
    void invalidQuaternionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BodyOrientation3d.normalized(
                        new Quatd(0.0D, 0.0D, 0.0D, 0.0D)));
        assertThrows(IllegalArgumentException.class,
                () -> BodyOrientation3d.normalized(
                        new Quatd(Double.NaN, 0.0D, 0.0D, 1.0D)));
    }

    @Test
    void transformUsesTheSameWorldFromBodyConventionAsTheFrame() {
        Quatd rotation = Quatd.rotationY(0.8D).normalized();
        OrthonormalFrame3d frame = BodyOrientation3d.frame(rotation);
        Vec3d value = new Vec3d(0.3D, -1.2D, 2.0D);

        assertEquals(
                rotation.transform(value).x(),
                frame.localToWorld(value).x(),
                TIGHT
        );
        assertEquals(
                rotation.transform(value).y(),
                frame.localToWorld(value).y(),
                TIGHT
        );
        assertEquals(
                rotation.transform(value).z(),
                frame.localToWorld(value).z(),
                TIGHT
        );
    }
}
