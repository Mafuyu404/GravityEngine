package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContactConstraintProjectorRegressionTest {
    private static final double EPS = 1.0E-12D;

    @Test
    void nonUnitConstraintInputIsCanonicalizedOnce() {
        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        new Vec3d(-1.0D, 2.0D, 3.0D),
                        List.of(new ContactConstraintProjector.Constraint(
                                new Vec3d(2.0D, 0.0D, 0.0D),
                                4.0D
                        ))
                );

        assertEquals(Vec3d.X, result.activePlanes().get(0).normal());
        assertEquals(2.0D, result.activePlanes().get(0).minimumDot(), 0.0D);
        assertVecEquals(new Vec3d(2.0D, 2.0D, 3.0D),
                result.requireProjectedVector(), EPS);
    }

    @Test
    void singlePlaneProjectionStaysExact() {
        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        new Vec3d(-1.0D, 2.0D, 3.0D),
                        List.of(new ContactConstraintProjector.Constraint(
                                Vec3d.X,
                                0.0D
                        ))
                );

        assertVecEquals(new Vec3d(0.0D, 2.0D, 3.0D),
                result.requireProjectedVector(), EPS);
        assertEquals(ContactConstraintProjector.Status.PROJECTED, result.status());
    }

    @Test
    void narrowWedgeUsesTheRankTwoOrthogonalBasisPath() {
        double angle = 1.0E-4D;
        Vec3d secondNormal = new Vec3d(
                Math.cos(angle),
                Math.sin(angle),
                0.0D
        );
        Vec3d desired = new Vec3d(-1.0D, -0.5D * angle, 0.0D);

        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        desired,
                        List.of(
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.X,
                                        0.0D
                                ),
                                new ContactConstraintProjector.Constraint(
                                        secondNormal,
                                        0.0D
                                )
                        )
                );

        assertEquals(2, result.activeRank());
        assertFeasible(result, desired);
        assertEquals(2, result.activePlanes().size());
    }

    @Test
    void rankThreeCornerProjectionReconstructsAndFeasible() {
        Vec3d desired = new Vec3d(-1.0D, -2.0D, -3.0D);
        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        desired,
                        List.of(
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.X, 0.0D),
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.Y, 0.0D),
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.Z, 0.0D)
                        )
                );

        assertEquals(3, result.activeRank());
        assertVecEquals(Vec3d.ZERO, result.requireProjectedVector(), 0.0D);
        assertFeasible(result, desired);
    }

    @Test
    void infeasibleAffineSetReportsInfeasibleWithoutVector() {
        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        Vec3d.ZERO,
                        List.of(
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.X, 2.0D),
                                new ContactConstraintProjector.Constraint(
                                        new Vec3d(-1.0D, 0.0D, 0.0D), 0.0D)
                        )
                );

        assertEquals(ContactConstraintProjector.Status.INFEASIBLE, result.status());
        assertTrue(result.infeasible());
        assertTrue(result.projectedVector().isEmpty());
        assertThrows(IllegalStateException.class, result::requireProjectedVector);
    }

    @Test
    void nonPenetratingFallbackRemovesOnlyInwardVelocity() {
        Vec3d result = ContactConstraintProjector.projectNonPenetrating(
                new Vec3d(-2.0D, 3.0D, 4.0D),
                List.of(new ContactConstraintProjector.Constraint(
                        Vec3d.X,
                        0.0D
                ))
        );

        assertVecEquals(new Vec3d(0.0D, 3.0D, 4.0D), result, EPS);
        assertTrue(result.dot(Vec3d.X) >= 0.0D);
    }

    @Test
    void duplicateCanonicalNormalsKeepTheStricterBound() {
        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        new Vec3d(-1.0D, 0.0D, 0.0D),
                        List.of(
                                new ContactConstraintProjector.Constraint(
                                        new Vec3d(2.0D, 0.0D, 0.0D),
                                        2.0D
                                ),
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.X,
                                        0.0D
                                )
                        )
                );

        assertEquals(1, result.activeRank());
        assertEquals(1, result.activePlanes().size());
        assertVecEquals(new Vec3d(1.0D, 0.0D, 0.0D),
                result.requireProjectedVector(), 0.0D);
    }

    @Test
    void constraintPermutationAndActiveSetTieOrderingAreDeterministic() {
        List<ContactConstraintProjector.Constraint> constraints = List.of(
                new ContactConstraintProjector.Constraint(Vec3d.X, 0.0D),
                new ContactConstraintProjector.Constraint(Vec3d.Y, 0.0D),
                new ContactConstraintProjector.Constraint(Vec3d.Z, 0.0D)
        );
        Vec3d desired = new Vec3d(-1.0D, -1.0D, -1.0D);

        ContactConstraintProjector.Result first =
                ContactConstraintProjector.project(desired, constraints);
        ArrayList<ContactConstraintProjector.Constraint> reversed =
                new ArrayList<>(constraints);
        Collections.reverse(reversed);
        ContactConstraintProjector.Result second =
                ContactConstraintProjector.project(desired, reversed);

        assertVecEquals(first.requireProjectedVector(),
                second.requireProjectedVector(), 0.0D);
        assertEquals(first.activeRank(), second.activeRank());
        assertEquals(first.activePlanes(), second.activePlanes());
    }

    @Test
    void linearPartAndAffineOffsetReconstructEveryProjectedResult() {
        List<List<ContactConstraintProjector.Constraint>> cases = List.of(
                List.of(new ContactConstraintProjector.Constraint(
                        new Vec3d(0.7D, -0.4D, 0.2D), 0.3D)),
                List.of(
                        new ContactConstraintProjector.Constraint(
                                new Vec3d(1.0D, 0.05D, 0.0D), 0.1D),
                        new ContactConstraintProjector.Constraint(
                                new Vec3d(0.05D, 1.0D, 0.0D), -0.2D)
                ),
                List.of(
                        new ContactConstraintProjector.Constraint(
                                new Vec3d(1.0D, 0.0D, 0.0D), 0.0D),
                        new ContactConstraintProjector.Constraint(
                                new Vec3d(0.0D, 1.0D, 0.0D), 0.0D),
                        new ContactConstraintProjector.Constraint(
                                new Vec3d(0.0D, 0.0D, 1.0D), 0.0D)
                )
        );
        Vec3d desired = new Vec3d(-1.2D, -0.7D, -0.4D);

        for (List<ContactConstraintProjector.Constraint> constraints : cases) {
            ContactConstraintProjector.Result result =
                    ContactConstraintProjector.project(desired, constraints);
            assertFalse(result.infeasible());
            Vec3d reconstructed = result.linearPart()
                    .apply(desired)
                    .add(result.affineOffset());
            assertVecEquals(
                    result.requireProjectedVector(),
                    reconstructed,
                    1.0E-9D
            );
        }
    }

    @Test
    void linearPartBelongsToTheSelectedActiveSetNotTheLastCandidate() {
        ContactConstraintProjector.Result result =
                ContactConstraintProjector.project(
                        new Vec3d(-1.0D, -2.0D, 5.0D),
                        List.of(
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.X, 0.0D),
                                new ContactConstraintProjector.Constraint(
                                        Vec3d.Y, -2.0D)
                        )
                );

        assertEquals(1, result.activeRank());
        assertVecEquals(new Vec3d(0.0D, -2.0D, 5.0D),
                result.requireProjectedVector(), 0.0D);
        assertVecEquals(
                new Vec3d(0.0D, 7.0D, 7.0D),
                result.linearPart().apply(new Vec3d(7.0D, 7.0D, 7.0D))
                        .add(result.affineOffset()),
                1.0E-12D
        );
    }

    private static void assertFeasible(
            ContactConstraintProjector.Result result,
            Vec3d desired
    ) {
        Vec3d projected = result.requireProjectedVector();
        assertTrue(Double.isFinite(projected.x()));
        assertTrue(Double.isFinite(projected.y()));
        assertTrue(Double.isFinite(projected.z()));
        for (ContactConstraintProjector.Constraint constraint : result.constraints()) {
            assertTrue(constraint.normal().dot(projected)
                    >= constraint.minimumDot()
                    - ContactConstraintProjector.FEASIBILITY_EPSILON);
        }
        Vec3d reconstructed = result.linearPart()
                .apply(desired)
                .add(result.affineOffset());
        assertVecEquals(projected, reconstructed, 1.0E-9D);
    }

    private static void assertVecEquals(Vec3d expected, Vec3d actual, double eps) {
        assertEquals(expected.x(), actual.x(), eps);
        assertEquals(expected.y(), actual.y(), eps);
        assertEquals(expected.z(), actual.z(), eps);
    }
}
