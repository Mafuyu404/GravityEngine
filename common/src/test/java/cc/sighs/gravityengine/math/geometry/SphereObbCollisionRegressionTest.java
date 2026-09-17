package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbCollision;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbContact;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbSweepHit;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereSweepResult;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SphereObbCollisionRegressionTest {
    private static final double EPS = 1.0E-12D;

    @Test
    void outsideFaceEdgeCornerAndTouchingStayClassified() {
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY
        );

        assertEquals(0.5D, SphereObbCollision.signedDistance(
                new Sphere3d(new Vec3d(2.0D, 0.0D, 0.0D), 0.5D), box
        ), EPS);
        assertEquals(0.0D, SphereObbCollision.signedDistance(
                new Sphere3d(new Vec3d(2.0D, 0.0D, 0.0D), 1.0D), box
        ), EPS);
        assertTrue(SphereObbCollision.signedDistance(
                new Sphere3d(new Vec3d(1.5D, 1.5D, 0.0D), 0.5D), box
        ) > 0.0D);
        assertTrue(SphereObbCollision.signedDistance(
                new Sphere3d(new Vec3d(1.5D, 1.5D, 1.5D), 0.5D), box
        ) > 0.0D);
    }

    @Test
    void centerInsideUsesNearestFaceAndDeterministicAxisOrder() {
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY
        );
        Sphere3d sphere = new Sphere3d(Vec3d.ZERO, 0.5D);

        SphereObbContact contact = SphereObbCollision.contact(sphere, box);
        assertEquals(1.5D, contact.penetration(), EPS);
        assertEquals(new Vec3d(-1.0D, 0.0D, 0.0D),
                contact.normalFromSphereToBox());
        assertEquals(new Vec3d(1.0D, 0.0D, 0.0D), contact.pointOnBox());
        assertEquals(-1.5D, SphereObbCollision.signedDistance(sphere, box), EPS);
    }

    @Test
    void rotatedBoxInsideContactReturnsWorldSpaceNormal() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(Math.PI * 0.5D)
        );
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                frame
        );
        Sphere3d sphere = new Sphere3d(Vec3d.ZERO, 0.5D);

        SphereObbContact contact = SphereObbCollision.contact(sphere, box);
        Vec3d expectedNormal = frame.axisX().multiply(-1.0D);

        assertVecEquals(expectedNormal, contact.normalFromSphereToBox(), EPS);
        assertNotEquals(new Vec3d(-1.0D, 0.0D, 0.0D),
                contact.normalFromSphereToBox());
        assertVecEquals(frame.axisX(), contact.pointOnBox(), EPS);
        assertEquals(1.5D, contact.penetration(), EPS);
        assertEquals(-1.5D, SphereObbCollision.signedDistance(sphere, box), EPS);
    }

    @Test
    void nonCardinalRotatedBoxInsideContactUsesSelectedWorldAxis() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(0.37D)
                        .multiply(Quatd.rotationY(-0.61D))
                        .multiply(Quatd.rotationX(0.23D))
                        .normalized()
        );
        Obb3d box = new Obb3d(
                10.0D, -3.0D, 7.0D,
                1.0D, 2.0D, 3.0D,
                frame
        );
        Vec3d localCenter = new Vec3d(-0.1D, 0.5D, 0.2D);
        Sphere3d sphere = new Sphere3d(
                box.localPointToWorld(localCenter),
                0.25D
        );

        SphereObbContact contact = SphereObbCollision.contact(sphere, box);
        Vec3d expectedNormal = frame.axisX();
        Vec3d expectedPoint = box.localPointToWorld(-1.0D, 0.5D, 0.2D);

        assertVecEquals(expectedNormal, contact.normalFromSphereToBox(), EPS);
        assertVecEquals(expectedPoint, contact.pointOnBox(), EPS);
        assertEquals(1.15D, contact.penetration(), EPS);
    }

    @Test
    void sweepInitialOverlapReusesTheWorldSpaceStaticNormal() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(Math.PI * 0.5D)
        );
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                frame
        );
        Sphere3d sphere = new Sphere3d(Vec3d.ZERO, 0.5D);

        SphereSweepResult result = SphereObbCollision.sweepDetailed(
                sphere,
                box,
                new Vec3d(1.0D, 2.0D, 3.0D)
        );
        assertTrue(result.hit().isPresent());
        SphereObbSweepHit hit = result.hit().orElseThrow();
        assertVecEquals(
                frame.axisX().multiply(-1.0D),
                hit.normalFromSphereToBox(),
                EPS
        );
        assertEquals(0.0D, hit.timeOfImpact(), 0.0D);
    }

    @Test
    void sweepInitialSurfaceTouchReusesTheWorldSpaceStaticNormal() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(Math.PI * 0.5D)
        );
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                frame
        );
        Sphere3d sphere = new Sphere3d(box.localPointToWorld(1.0D, 0.0D, 0.0D), 0.5D);

        SphereSweepResult result = SphereObbCollision.sweepDetailed(
                sphere,
                box,
                Vec3d.ZERO
        );
        assertTrue(result.hit().isPresent());
        assertVecEquals(
                frame.axisX().multiply(-1.0D),
                result.hit().orElseThrow().normalFromSphereToBox(),
                EPS
        );
    }

    @Test
    void rotatedBoxKeepsLocalFaceSemantics() {
        OrthonormalFrame3d frame = BodyOrientation3d.frame(
                Quatd.rotationZ(Math.PI * 0.5D)
        );
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 2.0D, 1.0D,
                frame
        );
        Sphere3d outside = new Sphere3d(
                new Vec3d(2.5D, 0.0D, 0.0D),
                0.5D
        );
        assertEquals(0.0D, SphereObbCollision.signedDistance(outside, box), EPS);
        assertTrue(SphereObbCollision.intersects(outside, box));
    }

    @Test
    void translationalSweepFindsExactEntryAndHonorsIntervalBoundaries() {
        Obb3d box = new Obb3d(
                3.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY
        );
        Sphere3d sphere = new Sphere3d(Vec3d.ZERO, 1.0D);

        Optional<SphereObbSweepHit> hit = SphereObbCollision.sweep(
                sphere,
                box,
                new Vec3d(-10.0D, 0.0D, 0.0D)
        );
        assertTrue(hit.isPresent());
        assertEquals(0.1D, hit.orElseThrow().timeOfImpact(), 1.0E-9D);
    }

    @Test
    void indeterminateSweepStateIsExplicit() {
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY
        );
        SphereSweepResult result = SphereObbCollision.sweepDetailed(
                new Sphere3d(Vec3d.ZERO, 1.0D),
                box,
                new Vec3d(Double.NaN, 0.0D, 0.0D)
        );

        assertEquals(SphereSweepResult.Status.INDETERMINATE, result.status());
        assertFalse(result.diagnostic().isBlank());
    }

    private static void assertVecEquals(Vec3d expected, Vec3d actual, double eps) {
        assertEquals(expected.x(), actual.x(), eps);
        assertEquals(expected.y(), actual.y(), eps);
        assertEquals(expected.z(), actual.z(), eps);
    }
}
