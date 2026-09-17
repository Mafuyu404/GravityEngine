package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AngularMomentumState;
import cc.sighs.gravityengine.attitude.AttitudeDynamicHandoff;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.attitude.BodyAttitudeConstraintKind;
import cc.sighs.gravityengine.attitude.BodyAttitudeControlAuthority;
import cc.sighs.gravityengine.attitude.BodyAttitudeControlProfile;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.EffectiveAngularInertia;
import cc.sighs.gravityengine.attitude.SemanticView;
import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistentSeed;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership coverage for the centralized replicated/persisted install seam.
 *
 * <p>One install decides the owner once: a kinematic install carries pose
 * only, a dynamic install carries {@code q + L_world} bound to a receiving
 * profile inertia, and neither disk nor wire is allowed to carry a second
 * inertia authority. {@code omega_world} stays derived.</p>
 */
class BodyAttitudeOwnershipTest {
    private static BodyAttitudeControlProfile elytraControl() {
        return new BodyAttitudeControlProfile(
                BodyAttitudeConstraintKind.ELYTRA_ALIGNED,
                1.0D,
                new BodyAttitudeControlAuthority(0.0D, 0.0D, 1.0D)
        );
    }

    private static BodyAttitudeComponent.Snapshot snapshot(
            BodyAttitudeState state
    ) {
        return snapshot(state, BodyAttitudeOwnership.ACTIVE);
    }

    private static BodyAttitudeComponent.Snapshot snapshot(
            BodyAttitudeState state,
            BodyAttitudeOwnership ownership
    ) {
        BodyRelativeViewState view =
                BodyRelativeViewState.fromSemantic(
                        new SemanticView(Quatd.rotationY(0.25D)),
                        state.currentWorldFromBody(),
                        new AttitudeSpaceTransform.LocalLookAngles(0, 0)
                );
        return new BodyAttitudeComponent.Snapshot(
                state,
                view,
                null,
                BodyAttitudeDecision.active(elytraControl()),
                BodyAttitudeContinuity.CONTINUOUS,
                ownership,
                BodyAttitudeComponent.NO_LOCAL_SIMULATION_STEP,
                0L,
                0L,
                BodyAttitudeComponent.NO_AUTHORITATIVE_SERVER_GAME_TICK,
                BodyAttitudeComponent.NO_AUTHORITATIVE_REVISION,
                1L,
                1L,
                true
        );
    }

    // ------------------------------------------------------- install seam

    @Test
    void dynamicInstallBindsMomentumToTheReceivingInertia() {
        Vec3d momentum = new Vec3d(1.0D, 0.5D, -0.25D);
        EffectiveAngularInertia inertia =
                EffectiveAngularInertia.of(2.0D);

        BodyAttitudeState dynamic =
                AttitudeDynamicHandoff.installDynamic(
                        Quatd.IDENTITY,
                        momentum,
                        inertia,
                        0L,
                        0L,
                        true
                );

        assertTrue(dynamic.hasAngularDynamics());
        assertEquals(
                momentum,
                dynamic.angularMomentum()
                        .orElseThrow()
                        .angularMomentumWorld()
        );
        assertEquals(
                inertia,
                dynamic.effectiveInertia().orElseThrow()
        );
        /* omega_world stays derived from q + L + I. */
        assertEquals(
                momentum.multiply(0.5D),
                dynamic.angularVelocityWorld()
        );
        assertThrows(
                IllegalStateException.class,
                () -> dynamic.withKinematicOrientation(
                        Quatd.rotationY(0.3D)
                )
        );
    }

    @Test
    void kinematicInstallCarriesNoFabricatedDynamics() {
        BodyAttitudeState kinematic =
                AttitudeDynamicHandoff.installKinematic(
                        Quatd.IDENTITY,
                        0L,
                        0L,
                        true
                );

        assertFalse(kinematic.hasAngularDynamics());
        assertTrue(kinematic.angularMomentum().isEmpty());
        assertTrue(kinematic.effectiveInertia().isEmpty());
        assertEquals(Vec3d.ZERO, kinematic.angularVelocityWorld());
        /* A kinematic owner may rewrite q without an explicit handoff. */
        assertEquals(
                0.0D,
                Quatd.angularDistance(
                        Quatd.rotationY(0.3D),
                        kinematic.withKinematicOrientation(
                                Quatd.rotationY(0.3D)
                        ).currentWorldFromBody()
                ),
                1.0E-12D
        );
    }

    @Test
    void dynamicOwnerAtRestIsStillADynamicOwner() {
        BodyAttitudeState dynamicAtRest =
                AttitudeDynamicHandoff.installDynamic(
                        Quatd.IDENTITY,
                        Vec3d.ZERO,
                        EffectiveAngularInertia.UNIT,
                        0L,
                        0L,
                        true
                );

        assertTrue(dynamicAtRest.hasAngularDynamics());
        assertEquals(
                Vec3d.ZERO,
                dynamicAtRest.angularMomentum()
                        .orElseThrow()
                        .angularMomentumWorld()
        );
        assertEquals(Vec3d.ZERO, dynamicAtRest.angularVelocityWorld());
    }

    // ------------------------------------------------------ disk continuity

    @Test
    void persistenceSeedCarriesWorldMomentumOnly() {
        Vec3d momentum = new Vec3d(-0.5D, 2.0D, 0.25D);
        BodyAttitudeState dynamic =
                AttitudeDynamicHandoff.installDynamic(
                        Quatd.rotationZYX(0.6D, 0.7D, 0.8D),
                        momentum,
                        EffectiveAngularInertia.of(3.0D),
                        5L,
                        5L,
                        true
                );

        BodyAttitudePersistentSeed seed =
                BodyAttitudePersistentSeed
                        .capture(snapshot(dynamic))
                        .orElseThrow();

        assertEquals(
                momentum,
                seed.angularMomentumWorld().orElseThrow()
        );
        assertEquals(
                dynamic.currentWorldFromBody(),
                seed.worldFromBody()
        );
    }

    @Test
    void kinematicSeedPersistsNoMomentumEntry() {
        BodyAttitudeState kinematic =
                AttitudeDynamicHandoff.installKinematic(
                        Quatd.rotationZYX(0.6D, 0.7D, 0.8D),
                        5L,
                        5L,
                        true
                );

        BodyAttitudePersistentSeed seed =
                BodyAttitudePersistentSeed
                        .capture(snapshot(kinematic))
                        .orElseThrow();

        assertTrue(
                seed.angularMomentumWorld().isEmpty(),
                "a kinematic owner must not persist fabricated momentum"
        );
    }

    @Test
    void dynamicSeedRemainsDynamicAtZeroMomentum() {
        BodyAttitudeState dynamicAtRest =
                AttitudeDynamicHandoff.installDynamic(
                        Quatd.IDENTITY,
                        Vec3d.ZERO,
                        EffectiveAngularInertia.UNIT,
                        5L,
                        5L,
                        true
                );

        BodyAttitudePersistentSeed seed =
                BodyAttitudePersistentSeed
                        .capture(snapshot(dynamicAtRest))
                        .orElseThrow();

        /*
         * Presence of the entry - not the vector magnitude - records that the
         * saved owner was dynamic; effective inertia is not durable at all.
         */
        assertTrue(seed.angularMomentumWorld().isPresent());
        assertEquals(Vec3d.ZERO, seed.angularMomentumWorld().orElseThrow());
    }

    @Test
    void inactiveOwnerPersistsNothing() {
        BodyAttitudeState dynamic =
                AttitudeDynamicHandoff.installDynamic(
                        Quatd.IDENTITY,
                        new Vec3d(0.0D, 1.0D, 0.0D),
                        EffectiveAngularInertia.UNIT,
                        5L,
                        5L,
                        true
                );

        assertTrue(
                BodyAttitudePersistentSeed
                        .capture(snapshot(
                                dynamic,
                                BodyAttitudeOwnership.INACTIVE
                        ))
                        .isEmpty(),
                "only an active owner has durable continuity"
        );
    }

    // --------------------------------------------------------- units check

    @Test
    void angularVelocityIsMomentumOverTheBindingInertia() {
        BodyAttitudeState dynamic =
                AttitudeDynamicHandoff.installDynamic(
                        Quatd.IDENTITY,
                        new AngularMomentumState(
                                new Vec3d(0.0D, 2.0D, 0.0D),
                                EffectiveAngularInertia.of(4.0D)
                        ).angularMomentumWorld(),
                        EffectiveAngularInertia.of(4.0D),
                        0L,
                        0L,
                        true
                );

        assertEquals(
                new Vec3d(0.0D, 0.5D, 0.0D),
                dynamic.angularVelocityWorld()
        );
    }
}
