package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryHotPathRegressionTest {
    private static final double EPS = 1.0E-12D;

    @Test
    void legalNearUnitFrameIsStillNormalizedBySat() {
        double scale = 1.0D + 2.0E-11D;
        OrthonormalFrame3d nearUnit = new OrthonormalFrame3d(
                scale, 0.0D, 0.0D,
                0.0D, scale, 0.0D,
                0.0D, 0.0D, scale
        );
        Obb3d first = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                nearUnit
        );

        ObbOverlapResult result = ObbSat.overlapDetached(
                first,
                first.moved(new Vec3d(2.0D, 0.0D, 0.0D))
        );

        assertEquals(ObbOverlapResult.Status.TOUCHING, result.status());
    }

    @Test
    void rotatedSatClassificationAndAxisIndexStayDeterministic() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(0.37D)
        );
        Obb3d first = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                frame
        );
        Obb3d overlapping = first.moved(frame.axisX().multiply(0.5D));
        ObbOverlapResult overlap = ObbSat.overlapDetached(first, overlapping);
        assertEquals(ObbOverlapResult.Status.OVERLAPPING, overlap.status());
        assertTrue(overlap.hasMtv());
        assertEquals(0, overlap.axisIndex());
        assertEquals(1.5D, overlap.penetration(), 1.0E-9D);

        ObbOverlapResult separated = ObbSat.overlapDetached(
                first,
                first.moved(new Vec3d(2.5D, 0.0D, 0.0D))
        );
        assertEquals(ObbOverlapResult.Status.SEPARATED, separated.status());
    }

    @Test
    void parallelCrossAxisDegeneracyDoesNotRemovePrimarySeparator() {
        Obb3d first = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY
        );
        ObbOverlapResult result = ObbSat.overlapDetached(
                first,
                first.moved(new Vec3d(2.0D, 0.0D, 0.0D))
        );

        assertEquals(ObbOverlapResult.Status.TOUCHING, result.status());
        assertEquals(0, result.axisIndex());
        assertEquals(Vec3d.X.negate(), result.normal());
    }

    @Test
    void fusedPointTransformsRoundTripWithNonZeroOriginAndLargeCoordinates() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationY(-0.4D)
        );
        Vec3d origin = new Vec3d(1.0E7D, -2.0E7D, 3.0E7D);
        Vec3d point = new Vec3d(1.0E7D + 3.0D, -2.0E7D + 4.0D, 3.0E7D - 5.0D);

        Vec3d local = frame.worldPointToLocal(point, origin);
        Vec3d roundTrip = frame.localPointToWorld(local, origin);

        assertVecEquals(point, roundTrip, 1.0E-9D);
    }

    @Test
    void freeVectorTransformsNeverApplyTranslation() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationY(0.8D)
        );
        OrientedBox first = new OrientedBox(
                new Vec3d(10.0D, 20.0D, 30.0D),
                new Vec3d(1.0D, 2.0D, 3.0D),
                frame
        );
        OrientedBox second = new OrientedBox(
                new Vec3d(1000.0D, -2000.0D, 3000.0D),
                first.halfExtents(),
                frame
        );
        Vec3d vector = new Vec3d(1.0D, -2.0D, 0.5D);

        assertVecEquals(
                first.worldVectorToLocal(vector),
                second.worldVectorToLocal(vector),
                0.0D
        );
        assertNotEquals(
                first.worldPointToLocal(vector),
                second.worldPointToLocal(vector)
        );
        assertVecEquals(
                vector,
                first.localVectorToWorld(first.worldVectorToLocal(vector)),
                1.0E-12D
        );
    }

    private static void assertVecEquals(Vec3d expected, Vec3d actual, double eps) {
        assertEquals(expected.x(), actual.x(), eps);
        assertEquals(expected.y(), actual.y(), eps);
        assertEquals(expected.z(), actual.z(), eps);
    }
}
