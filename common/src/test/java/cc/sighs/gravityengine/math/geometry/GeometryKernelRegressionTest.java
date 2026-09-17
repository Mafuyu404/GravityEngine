package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbCollision;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbContact;
import cc.sighs.gravityengine.gravity.collision.geometry.SphereObbSweepHit;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryKernelRegressionTest {
    @Test
    void aabbTranslationExpansionAndUnionRemainExact() {
        Aabb3d bounds = new Aabb3d(-1.0D, -2.0D, -3.0D, 1.0D, 2.0D, 3.0D);

        assertEquals(new Vec3d(0.0D, 0.0D, 0.0D), bounds.center());
        assertEquals(new Vec3d(2.0D, 4.0D, 6.0D), bounds.size());
        assertEquals(new Vec3d(1.0D, 2.0D, 3.0D), bounds.halfExtents());
        assertTrue(bounds.contains(Vec3d.ZERO));
        assertEquals(new Aabb3d(0.0D, -1.0D, -2.0D, 2.0D, 3.0D, 4.0D),
                bounds.translated(new Vec3d(1.0D, 1.0D, 1.0D)));
        assertEquals(new Aabb3d(-2.0D, -2.0D, -3.0D, 1.0D, 2.0D, 3.0D),
                bounds.union(new Aabb3d(-2.0D, -1.0D, -1.0D, 0.0D, 1.0D, 1.0D)));
    }

    @Test
    void obbSatClassifiesOverlapTouchAndSeparation() {
        Obb3d first = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY);

        ObbOverlapResult overlap = ObbSat.overlapDetached(
                first,
                first.moved(new Vec3d(0.5D, 0.0D, 0.0D)));
        assertEquals(ObbOverlapResult.Status.OVERLAPPING, overlap.status());
        assertEquals(1.5D, overlap.penetration(), 1.0E-12D);

        ObbOverlapResult touching = ObbSat.overlapDetached(
                first,
                first.moved(new Vec3d(2.0D, 0.0D, 0.0D)));
        assertEquals(ObbOverlapResult.Status.TOUCHING, touching.status());

        ObbOverlapResult separated = ObbSat.overlapDetached(
                first,
                first.moved(new Vec3d(2.5D, 0.0D, 0.0D)));
        assertEquals(ObbOverlapResult.Status.SEPARATED, separated.status());
    }

    @Test
    void obbSweepReportsEntryTimeAndMiss() {
        Obb3d moving = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY);
        Obb3d obstacle = moving.moved(new Vec3d(3.0D, 0.0D, 0.0D));

        ObbSweepResult hit = ObbSweep.sweep(
                moving,
                new Vec3d(10.0D, 0.0D, 0.0D),
                obstacle,
                new ObbScratch());
        assertEquals(ObbSweepResult.Status.HIT, hit.status());
        assertEquals(0.1D, hit.timeOfImpact(), 1.0E-12D);

        ObbSweepResult miss = ObbSweep.sweep(
                moving,
                new Vec3d(-1.0D, 0.0D, 0.0D),
                obstacle,
                new ObbScratch());
        assertEquals(ObbSweepResult.Status.NO_HIT, miss.status());
    }

    @Test
    void sphereObbContactAndSweepKeepWorldSpaceSemantics() {
        Obb3d box = new Obb3d(
                0.0D, 0.0D, 0.0D,
                1.0D, 1.0D, 1.0D,
                OrthonormalFrame3d.IDENTITY);
        Sphere3d sphere = new Sphere3d(new Vec3d(1.5D, 0.0D, 0.0D), 1.0D);

        assertEquals(-0.5D,
                SphereObbCollision.signedDistance(sphere, box),
                1.0E-12D);
        SphereObbContact contact = SphereObbCollision.contact(sphere, box);
        assertEquals(0.5D, contact.penetration(), 1.0E-12D);
        assertTrue(contact.normalFromSphereToBox().dot(Vec3d.X) < -0.99D);

        Optional<SphereObbSweepHit> hit = SphereObbCollision.sweep(
                new Sphere3d(Vec3d.ZERO, 1.0D),
                box.moved(new Vec3d(3.0D, 0.0D, 0.0D)),
                new Vec3d(-10.0D, 0.0D, 0.0D)
        );
        assertTrue(hit.isPresent());
        assertEquals(0.1D, hit.orElseThrow().timeOfImpact(), 1.0E-9D);
    }

    @Test
    void capsuleAndOrientedBoxExposeStableNeutralGeometry() {
        OrientedBox box = OrientedBox.fromDimensions(
                Vec3d.ZERO,
                2.0D,
                4.0D,
                OrthonormalFrame3d.IDENTITY
        );
        assertEquals(new Aabb3d(-1.0D, -2.0D, -1.0D, 1.0D, 2.0D, 1.0D),
                box.enclosingAabb());
        assertEquals(new Vec3d(1.0D, 2.0D, 1.0D),
                box.corner(1.0D, 1.0D, 1.0D));

        CharacterCapsule capsule = CharacterCapsule.fromDimensions(
                new Vec3d(0.0D, 1.0D, 0.0D),
                1.0D,
                2.0D,
                OrthonormalFrame3d.IDENTITY
        );
        assertEquals(new Vec3d(0.0D, 0.5D, 0.0D), capsule.a());
        assertEquals(new Vec3d(0.0D, 1.5D, 0.0D), capsule.b());
        assertEquals(new Aabb3d(-0.5D, 0.0D, -0.5D, 0.5D, 2.0D, 0.5D),
                capsule.enclosingAabb());
        assertEquals(2.0D, capsule.totalHeight(), 0.0D);
    }
}
