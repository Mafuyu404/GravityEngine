package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural coverage for the common angular-dynamics layer: sustained roll
 * torque, release, reverse input, substep consistency, flight-axis authority,
 * gravity-direction independence, heading/roll separation, damping, the
 * antiparallel heading case and numeric validation.
 */
class AngularDynamicsTest {
    private static final double DT = 0.05D;
    private static final double ROLL_TORQUE = 6.283185307179586D;
    private static final double MOMENTUM_TOLERANCE = 1.0E-12D;

    private static BodyAttitudeConfigSnapshot profile(
            double inertia,
            double rollTorque,
            double headingGain,
            double headingDamping,
            double angularDamping
    ) {
        return new BodyAttitudeConfigSnapshot(
                0.35D,
                180.0D,
                inertia,
                rollTorque,
                headingGain,
                headingDamping,
                angularDamping,
                0.0D,
                0.1D,
                1.0D,
                1.0E-8D,
                1.0E-10D,
                0.05D,
                8
        );
    }

    private static BodyAttitudeControlProfile elytraControl() {
        return new BodyAttitudeControlProfile(
                BodyAttitudeConstraintKind.ELYTRA_ALIGNED,
                1.0D,
                new BodyAttitudeControlAuthority(0.0D, 0.0D, 1.0D)
        );
    }

    private static BodyAttitudeStepContext context(
            BodyAttitudeInput input,
            Vec3d lookForwardWorld,
            Vec3d velocityWorld,
            GravityFrame frame
    ) {
        return new BodyAttitudeStepContext(
                frame,
                elytraControl(),
                input,
                lookForwardWorld,
                velocityWorld,
                DT
        );
    }

    private static BodyAttitudeState dynamicState(
            Quatd worldFromBody,
            Vec3d angularMomentumWorld
    ) {
        return BodyAttitudeState.dynamic(
                worldFromBody,
                new AngularMomentumState(
                        angularMomentumWorld,
                        EffectiveAngularInertia.UNIT),
                0L,
                0L
        );
    }

    private static Vec3d flightForward(Quatd worldFromBody) {
        return AttitudeSpaceTransform.elytraForwardWorld(worldFromBody);
    }

    private static BodyAttitudeStepResult step(
            BodyAttitudeState state,
            BodyAttitudeInput input,
            BodyAttitudeConfigSnapshot config
    ) {
        return BodyAttitudeController.step(
                state,
                context(
                        input,
                        flightForward(state.currentWorldFromBody()),
                        Vec3d.ZERO,
                        GravityFrame.DEFAULT),
                config
        );
    }

    private static Vec3d momentumOf(BodyAttitudeState state) {
        return state.angularMomentum().orElseThrow().angularMomentumWorld();
    }

    // ---------------------------------------------------------------- A

    @Test
    void heldRollTorqueAccumulatesMomentumAndNeverSettlesAtAFixedAngle() {
        BodyAttitudeConfigSnapshot config = profile(1.0D, ROLL_TORQUE, 0.0D, 0.0D, 0.0D);
        BodyAttitudeState state = dynamicState(Quatd.IDENTITY, Vec3d.ZERO);
        Vec3d forward = flightForward(Quatd.IDENTITY);

        for (int i = 0; i < 10; i++) {
            state = step(state, BodyAttitudeInput.rollOnly(1.0D), config).nextState();
        }
        Vec3d afterTen = momentumOf(state);
        Quatd orientationAfterTen = state.currentWorldFromBody();

        for (int i = 0; i < 10; i++) {
            state = step(state, BodyAttitudeInput.rollOnly(1.0D), config).nextState();
        }
        Vec3d afterTwenty = momentumOf(state);
        Quatd orientationAfterTwenty = state.currentWorldFromBody();

        Vec3d expectedTen = forward.multiply(ROLL_TORQUE * DT * 10.0D);
        Vec3d expectedTwenty = forward.multiply(ROLL_TORQUE * DT * 20.0D);
        assertEquals(0.0D, afterTen.distance(expectedTen), MOMENTUM_TOLERANCE);
        assertEquals(0.0D, afterTwenty.distance(expectedTwenty), MOMENTUM_TOLERANCE);

        double angleTen = Quatd.angularDistance(Quatd.IDENTITY, orientationAfterTen);
        double angleTwenty = Quatd.angularDistance(Quatd.IDENTITY, orientationAfterTwenty);
        assertTrue(angleTwenty > angleTen + 1.0E-6D,
                "held roll input must keep rotating the body, not stop at a fixed roll angle");
        assertTrue(angleTwenty > 3.0D * angleTen,
                "a constant torque keeps accelerating the rotation");
    }

    // ---------------------------------------------------------------- B

    @Test
    void releaseRemovesOnlyPlayerRollAndConservesMomentum() {
        BodyAttitudeConfigSnapshot config = profile(1.0D, ROLL_TORQUE, 6.0D, 0.0D, 0.0D);
        BodyAttitudeState state = dynamicState(Quatd.IDENTITY, Vec3d.ZERO);
        for (int i = 0; i < 10; i++) {
            state = step(state, BodyAttitudeInput.rollOnly(1.0D), config).nextState();
        }
        Vec3d momentum = momentumOf(state);
        Quatd before = state.currentWorldFromBody();

        for (int i = 0; i < 10; i++) {
            BodyAttitudeStepResult result =
                    step(state, BodyAttitudeInput.NONE, config);
            assertEquals(0.0D, result.appliedTorqueWorld().length(),
                    MOMENTUM_TOLERANCE,
                    "heading control must not act on a pure axial roll");
            state = result.nextState();
        }

        assertEquals(momentum, momentumOf(state));
        assertTrue(Quatd.angularDistance(before, state.currentWorldFromBody()) > 1.0E-3D,
                "the body keeps rotating after release because momentum is conserved");
    }

    // ---------------------------------------------------------------- C

    @Test
    void reverseInputDeceleratesThroughZeroWithoutOrientationJumps() {
        BodyAttitudeConfigSnapshot config = profile(1.0D, ROLL_TORQUE, 0.0D, 0.0D, 0.0D);
        BodyAttitudeState state = dynamicState(Quatd.IDENTITY, Vec3d.ZERO);
        for (int i = 0; i < 10; i++) {
            state = step(state, BodyAttitudeInput.rollOnly(1.0D), config).nextState();
        }
        Vec3d forward = flightForward(Quatd.IDENTITY);
        double previousAlong = momentumOf(state).dot(forward);

        for (int i = 0; i < 20; i++) {
            Quatd before = state.currentWorldFromBody();
            state = step(state, BodyAttitudeInput.rollOnly(-1.0D), config).nextState();
            double along = momentumOf(state).dot(forward);
            assertTrue(along <= previousAlong + MOMENTUM_TOLERANCE,
                    "reverse input must reduce the existing momentum first");
            assertTrue(Quatd.angularDistance(before, state.currentWorldFromBody()) < 0.5D,
                    "orientation must stay continuous across the reversal");
            previousAlong = along;
        }

        assertEquals(0.0D,
                momentumOf(state).dot(forward) + ROLL_TORQUE * DT * 10.0D,
                MOMENTUM_TOLERANCE);
        assertTrue(momentumOf(state).dot(forward) < 0.0D);
    }

    // ---------------------------------------------------------------- D

    @Test
    void simultaneousOppositeInputProducesZeroPlayerRollTorque() {
        BodyAttitudeConfigSnapshot config = profile(1.0D, ROLL_TORQUE, 0.0D, 0.0D, 0.0D);
        Quatd q = Quatd.rotationX(0.4D).normalized();
        AngularMomentumState dynamics = AngularMomentumState.rest(
                EffectiveAngularInertia.UNIT);
        AttitudeTorqueContext torqueContext = new AttitudeTorqueContext(
                q,
                dynamics.angularVelocityWorld(q),
                dynamics.angularMomentumWorld(),
                EffectiveAngularInertia.UNIT,
                new FlightIntent(flightForward(q), flightForward(q), Vec3d.ZERO, 0.0D),
                BodyAttitudeInput.NONE,
                elytraControl(),
                config,
                DT
        );
        AngularTorqueAccumulator accumulator = new AngularTorqueAccumulator();
        ElytraPlayerRollTorquePolicy.INSTANCE.accumulate(accumulator, torqueContext);
        assertEquals(Vec3d.ZERO,
                accumulator.contribution(
                        AngularTorqueAccumulator.TorqueSource.PLAYER_ROLL));

        BodyAttitudeState state = BodyAttitudeState.dynamic(q, dynamics, 0L, 0L);
        BodyAttitudeStepResult result =
                step(state, BodyAttitudeInput.NONE, config);
        assertEquals(Vec3d.ZERO, momentumOf(result.nextState()));
    }

    // ---------------------------------------------------------------- E

    @Test
    void substepPartitionDoesNotChangeTheIntegratedResult() {
        double inertia = 1.5D;
        double torque = 4.0D;
        double duration = 0.2D;
        Quatd start = Quatd.rotationZ(0.4D).normalized();
        Vec3d axis = flightForward(start);
        AngularMomentumState dynamics = AngularMomentumState.rest(
                EffectiveAngularInertia.of(inertia));

        AngularDynamicsStep coarse = integratePartition(
                start, dynamics, axis, torque, duration, 1);
        AngularDynamicsStep medium = integratePartition(
                start, dynamics, axis, torque, duration, 5);
        AngularDynamicsStep fine = integratePartition(
                start, dynamics, axis, torque, duration, 20);

        assertEquals(0.0D, coarse.angularMomentumWorld()
                .distance(medium.angularMomentumWorld()), 1.0E-12D);
        assertEquals(0.0D, coarse.angularMomentumWorld()
                .distance(fine.angularMomentumWorld()), 1.0E-12D);
        assertEquals(0.0D, Quatd.angularDistance(
                coarse.worldFromBody(), fine.worldFromBody()), 1.0E-9D);

        /*
         * The same contract through the production step: a finer configured
         * substep partition must produce the same attitude and momentum.
         */
        BodyAttitudeConfigSnapshot coarseConfig =
                profile(inertia, torque, 0.0D, 0.0D, 0.0D);
        BodyAttitudeConfigSnapshot fineConfig = new BodyAttitudeConfigSnapshot(
                0.35D, 180.0D, inertia, torque, 0.0D, 0.0D, 0.0D,
                0.0D, 0.1D, 1.0D, 1.0E-8D, 1.0E-10D, 0.01D, 64);

        BodyAttitudeState coarseState = dynamicState(start, Vec3d.ZERO);
        BodyAttitudeState fineState = dynamicState(start, Vec3d.ZERO);
        for (int i = 0; i < 4; i++) {
            coarseState = step(coarseState, BodyAttitudeInput.rollOnly(1.0D), axis, coarseConfig);
            fineState = step(fineState, BodyAttitudeInput.rollOnly(1.0D), axis, fineConfig);
        }
        assertEquals(0.0D, momentumOf(coarseState).distance(momentumOf(fineState)),
                1.0E-12D);
        assertEquals(0.0D, Quatd.angularDistance(
                coarseState.currentWorldFromBody(),
                fineState.currentWorldFromBody()), 1.0E-9D);
    }

    private static BodyAttitudeState step(
            BodyAttitudeState state,
            BodyAttitudeInput input,
            Vec3d lookForward,
            BodyAttitudeConfigSnapshot config
    ) {
        return BodyAttitudeController.step(
                state,
                context(input, lookForward, Vec3d.ZERO, GravityFrame.DEFAULT),
                config
        ).nextState();
    }

    private static AngularDynamicsStep integratePartition(
            Quatd start,
            AngularMomentumState dynamics,
            Vec3d axis,
            double torque,
            double duration,
            int substeps
    ) {
        double dt = duration / substeps;
        Quatd q = start;
        AngularMomentumState current = dynamics;
        Vec3d torqueWorld = axis.normalized().multiply(torque);
        for (int i = 0; i < substeps; i++) {
            AngularDynamicsStep solved = AngularDynamicsSolver.integrate(
                    q, current, torqueWorld, dt, 1.0E-8D);
            q = solved.worldFromBody();
            current = new AngularMomentumState(
                    solved.angularMomentumWorld(), current.inertia());
        }
        return new AngularDynamicsStep(
                q, current.angularMomentumWorld(),
                current.angularVelocityWorld(q));
    }

    // ---------------------------------------------------------------- F

    @Test
    void rollTorqueActsAboutThePhysicalFlightForwardAxis() {
        Quatd start = Quatd.rotationZ(0.7D).rotateX(0.35D).normalized();
        Vec3d forward = flightForward(start);
        assertTrue(forward.distanceSquared(Vec3d.Y) > 1.0E-3D,
                "fixture must not use a canonical world axis");

        BodyAttitudeConfigSnapshot config = profile(1.0D, 3.0D, 0.0D, 0.0D, 0.0D);
        BodyAttitudeState state = dynamicState(start, Vec3d.ZERO);
        BodyAttitudeStepResult result =
                step(state, BodyAttitudeInput.rollOnly(1.0D), config);
        Vec3d momentum = momentumOf(result.nextState());
        assertEquals(0.0D, momentum.cross(forward).length(), MOMENTUM_TOLERANCE);
        assertEquals(3.0D * DT, momentum.dot(forward), MOMENTUM_TOLERANCE);
        assertTrue(momentum.cross(Vec3d.X).length() > 1.0E-3D);
    }

    // ---------------------------------------------------------------- G

    @Test
    void gravityFrameDirectionAndStrengthDoNotAffectAttitudeTorque() {
        Quatd start = Quatd.rotationY(0.6D).rotateZ(0.25D).normalized();
        Vec3d look = flightForward(start);
        BodyAttitudeConfigSnapshot config = profile(1.0D, 5.0D, 6.0D, 2.0D, 0.5D);
        BodyAttitudeState state = dynamicState(start, look.multiply(1.25D));
        BodyAttitudeInput input = BodyAttitudeInput.rollOnly(-0.75D);

        BodyAttitudeStepResult vanilla = BodyAttitudeController.step(
                state,
                context(input, look, Vec3d.ZERO, GravityFrame.DEFAULT),
                config);
        BodyAttitudeStepResult tilted = BodyAttitudeController.step(
                state,
                context(input, look, Vec3d.ZERO,
                        GravityFrame.fromDown(
                                new Vec3d(0.4D, 0.7D, -0.2D).normalized(),
                                GravityState.VANILLA_STRENGTH * 0.2D)),
                config);
        BodyAttitudeStepResult weak = BodyAttitudeController.step(
                state,
                context(input, look, Vec3d.ZERO,
                        GravityFrame.fromDown(
                                new Vec3d(-0.9D, 0.1D, 0.3D).normalized(),
                                GravityState.VANILLA_STRENGTH * 0.01D)),
                config);

        assertEquals(vanilla.appliedTorqueWorld(), tilted.appliedTorqueWorld());
        assertEquals(vanilla.appliedTorqueWorld(), weak.appliedTorqueWorld());
        assertEquals(vanilla.nextState().angularMomentum().orElseThrow(),
                tilted.nextState().angularMomentum().orElseThrow());
        assertEquals(vanilla.nextState().angularMomentum().orElseThrow(),
                weak.nextState().angularMomentum().orElseThrow());
        assertEquals(vanilla.nextState().currentWorldFromBody(),
                weak.nextState().currentWorldFromBody());
    }

    // ---------------------------------------------------------------- H

    @Test
    void headingControlNeverDampsPureAxialRoll() {
        BodyAttitudeConfigSnapshot config = profile(1.0D, 3.0D, 6.0D, 4.0D, 0.0D);

        /*
         * Exact case: a canonical attitude makes the axial/heading split
         * mathematically exact, so the heading contribution must be exactly
         * absent rather than merely small.
         */
        Quatd canonical = Quatd.IDENTITY;
        Vec3d canonicalForward = flightForward(canonical);
        AngularMomentumState canonicalDynamics = new AngularMomentumState(
                canonicalForward.multiply(2.0D), EffectiveAngularInertia.UNIT);
        AngularTorqueAccumulator exact = new AngularTorqueAccumulator();
        ElytraHeadingTorquePolicy.INSTANCE.accumulate(
                exact,
                new AttitudeTorqueContext(
                        canonical,
                        canonicalDynamics.angularVelocityWorld(canonical),
                        canonicalDynamics.angularMomentumWorld(),
                        EffectiveAngularInertia.UNIT,
                        new FlightIntent(
                                canonicalForward, canonicalForward,
                                Vec3d.ZERO, 0.0D),
                        BodyAttitudeInput.NONE,
                        elytraControl(),
                        config,
                        DT));
        assertEquals(Vec3d.ZERO,
                exact.contribution(
                        AngularTorqueAccumulator.TorqueSource.HEADING_CONTROL));

        /*
         * Rotated attitude: the axial component is rejected analytically, so
         * only floating-point noise remains and the roll momentum survives.
         */
        Quatd rotated = Quatd.rotationX(0.55D).normalized();
        Vec3d forward = flightForward(rotated);
        AngularMomentumState dynamics = new AngularMomentumState(
                forward.multiply(2.0D), EffectiveAngularInertia.UNIT);
        BodyAttitudeState state =
                BodyAttitudeState.dynamic(rotated, dynamics, 0L, 0L);
        BodyAttitudeStepResult result =
                step(state, BodyAttitudeInput.NONE, config);
        assertEquals(0.0D, result.appliedTorqueWorld().length(), MOMENTUM_TOLERANCE);
        assertEquals(2.0D, result.nextState().angularVelocityWorld().length(),
                MOMENTUM_TOLERANCE);
    }

    // ---------------------------------------------------------------- I

    @Test
    void genericAngularDampingDecaysRateWithoutAttractingARollAngle() {
        Quatd orientation = Quatd.rotationZ(0.9D).rotateY(0.2D).normalized();
        Vec3d axis = flightForward(orientation);
        Vec3d momentum = axis.multiply(3.0D);
        BodyAttitudeConfigSnapshot damped = profile(1.0D, 0.0D, 0.0D, 0.0D, 2.0D);

        BodyAttitudeState state = dynamicState(orientation, momentum);
        BodyAttitudeStepResult result =
                step(state, BodyAttitudeInput.NONE, damped);
        Vec3d next = momentumOf(result.nextState());
        Vec3d expected = momentum.multiply(1.0D - 2.0D * DT);
        assertEquals(0.0D, next.distance(expected), 1.0E-9D);
        assertTrue(next.length() < momentum.length());
        assertEquals(0.0D,
                next.normalized().cross(momentum.normalized()).length(),
                1.0E-9D,
                "damping must not introduce a fixed roll-angle attractor");

        BodyAttitudeConfigSnapshot undamped = profile(1.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        BodyAttitudeStepResult conserved =
                step(state, BodyAttitudeInput.NONE, undamped);
        assertEquals(momentum, momentumOf(conserved.nextState()));
    }

    // ---------------------------------------------------------------- J

    @Test
    void antiparallelHeadingIsFiniteDeterministicAndWorldAxisFree() {
        Quatd orientation = Quatd.rotationX(0.3D).normalized();
        Vec3d forward = flightForward(orientation);
        Vec3d dorsal = AttitudeSpaceTransform.elytraDorsalWorld(orientation);
        Vec3d error = BodyAttitudeMath.directionRotationError(
                forward, forward.negate(), dorsal, 1.0E-10D);

        assertTrue(error.isFinite());
        assertEquals(Math.PI, error.length(), 1.0E-9D);
        assertEquals(0.0D, error.dot(forward), 1.0E-9D);
        assertEquals(error, BodyAttitudeMath.directionRotationError(
                forward, forward.negate(), dorsal, 1.0E-10D));
        assertEquals(0.0D, error.normalized().cross(dorsal).length(), 1.0E-9D,
                "the antiparallel fallback must be the body-derived flight axis");

        /*
         * Equivariance: rotating the whole problem rotates the resolved axis.
         * A hidden world-up preference would break this identity.
         */
        Quatd global = Quatd.rotationY(1.1D).rotateX(-0.4D).normalized();
        Vec3d rotated = BodyAttitudeMath.directionRotationError(
                global.transform(forward),
                global.transform(forward.negate()),
                global.transform(dorsal),
                1.0E-10D);
        assertEquals(0.0D,
                rotated.distance(global.transform(error)), 1.0E-9D);

        Vec3d perturbed = forward.negate().add(dorsal.multiply(1.0E-6D)).normalized();
        Vec3d first = BodyAttitudeMath.directionRotationError(
                forward, perturbed, dorsal, 1.0E-10D);
        Vec3d second = BodyAttitudeMath.directionRotationError(
                forward, perturbed, dorsal, 1.0E-10D);
        assertEquals(first, second);
        assertEquals(0.0D, first.dot(forward), 1.0E-9D);
    }

    // ---------------------------------------------------------------- K

    @Test
    void elytraFlightAxisMappingIsPreservedAndSelfConsistent() {
        Quatd orientation = Quatd.rotationY(0.9D).rotateX(-0.35D).rotateZ(0.2D)
                .normalized();

        Vec3d left = AttitudeSpaceTransform.elytraLeftWorld(orientation);
        Vec3d dorsal = AttitudeSpaceTransform.elytraDorsalWorld(orientation);
        Vec3d forward = AttitudeSpaceTransform.elytraForwardWorld(orientation);
        Vec3d belly = AttitudeSpaceTransform.elytraBellyWorld(orientation);

        assertEquals(0.0D,
                forward.distance(
                        AttitudeSpaceTransform.bodyUpWorld(orientation)),
                0.0D);
        assertEquals(0.0D,
                dorsal.distance(
                        AttitudeSpaceTransform.bodyForwardWorld(orientation)
                                .negate()),
                0.0D);
        assertEquals(0.0D,
                belly.distance(
                        AttitudeSpaceTransform.bodyForwardWorld(orientation)),
                0.0D);
        assertEquals(0.0D,
                left.distance(
                        AttitudeSpaceTransform.bodyLeftWorld(orientation)),
                0.0D);

        assertEquals(0.0D, left.dot(dorsal), 1.0E-12D);
        assertEquals(0.0D, forward.dot(belly), 1.0E-12D);
        assertEquals(0.0D, left.dot(forward), 1.0E-12D);
        assertEquals(0.0D, left.dot(belly), 1.0E-12D);
        assertEquals(0.0D, dorsal.distance(belly.negate()), 1.0E-12D);
        assertEquals(0.0D, forward.cross(left).distance(dorsal), 1.0E-12D);

        /*
         * The mapping is a fixed body-local remap: it depends on q only. There
         * is no gravity operand, so no gravity frame can redefine the flight
         * axes or supply a belly-righting reference.
         */
        Quatd rotated = Quatd.rotationY(1.3D).multiply(orientation).normalized();
        assertEquals(0.0D,
                AttitudeSpaceTransform.elytraForwardWorld(rotated).distance(
                        Quatd.rotationY(1.3D)
                                .transform(forward)),
                1.0E-12D);
    }

    // ---------------------------------------------------------------- K

    @Test
    void invalidDynamicsOperandsAreRejectedAtTheBoundary() {
        Quatd orientation = Quatd.IDENTITY;
        AngularMomentumState dynamics = AngularMomentumState.rest(
                EffectiveAngularInertia.UNIT);

        assertThrows(IllegalArgumentException.class,
                () -> AngularDynamicsSolver.integrate(
                        orientation, dynamics,
                        new Vec3d(Double.NaN, 0.0D, 0.0D), DT, 1.0E-8D));
        assertThrows(IllegalArgumentException.class,
                () -> AngularDynamicsSolver.integrate(
                        orientation, dynamics,
                        new Vec3d(Double.POSITIVE_INFINITY, 0.0D, 0.0D),
                        DT, 1.0E-8D));
        assertThrows(IllegalArgumentException.class,
                () -> EffectiveAngularInertia.of(0.0D));
        assertThrows(IllegalArgumentException.class,
                () -> EffectiveAngularInertia.of(-1.0D));
        assertThrows(IllegalArgumentException.class,
                () -> AngularDynamicsSolver.integrate(
                        new Quatd(0.0D, 0.0D, 0.0D, 0.0D),
                        dynamics, Vec3d.ZERO, DT, 1.0E-8D));
        assertThrows(IllegalArgumentException.class,
                () -> AngularDynamicsSolver.integrate(
                        orientation, dynamics, Vec3d.ZERO, 0.0D, 1.0E-8D));
        assertThrows(IllegalArgumentException.class,
                () -> AngularDynamicsSolver.integrate(
                        orientation, dynamics, Vec3d.ZERO, -0.1D, 1.0E-8D));
        assertThrows(IllegalArgumentException.class,
                () -> AngularDynamicsSolver.integrate(
                        orientation, dynamics, Vec3d.ZERO, Double.NaN, 1.0E-8D));
        assertThrows(IllegalArgumentException.class,
                () -> new AngularTorqueAccumulator().add(
                        AngularTorqueAccumulator.TorqueSource.PLAYER_ROLL,
                        new Vec3d(Double.NaN, 0.0D, 0.0D)));
        assertThrows(IllegalArgumentException.class,
                () -> new AngularMomentumState(
                        new Vec3d(Double.POSITIVE_INFINITY, 0.0D, 0.0D),
                        EffectiveAngularInertia.UNIT));
    }
}
