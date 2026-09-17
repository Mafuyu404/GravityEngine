package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.ScalarMath;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic actor-attitude control.
 *
 * <p>{@code REFERENCE_ALIGNED} and {@code FREE_ATTITUDE} run no angular
 * dynamics here: the angular stage applies no passive contribution at all.
 * Ordinary upright alignment comes from the accepted gravity reference and
 * heading representation, and the explicit low-gravity swim contract leaves
 * the body for kinematic joint following driven by the semantic view. Only
 * Elytra owns look/velocity alignment and aerodynamic angular dynamics.</p>
 *
 * <p>Actor quaternion changes never rotate the kinematic character collider or
 * translate its position.</p>
 */
public final class BodyAttitudeController {

    private BodyAttitudeController() {}

    public static BodyAttitudeStepResult step(
            BodyAttitudeState state,
            BodyAttitudeStepContext context,
            BodyAttitudeConfigSnapshot config
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");

        if (!state.initialized()) {
            throw new IllegalStateException(
                    "BodyAttitudeController.step requires an initialized state; "
                            + "the integration layer must capture the currently "
                            + "displayed body orientation first"
            );
        }

        BodyAttitudeConstraintKind constraint =
                context.controlProfile().constraint();

        /*
         * Ordinary reference-aligned production characters should normally be
         * INACTIVE and never reach this controller; keep the stationary behavior
         * as a safe fallback.
         *
         * The explicit FREE_ATTITUDE special contract (low-gravity swim) must not
         * receive ordinary gravity righting, an angular-inertia model or a
         * deferred gravity-righting target, regardless of whether environmental
         * gravity is zero or nonzero. This branch therefore applies no angular
         * dynamics at all; semantic view control and body-local joint following
         * continue through {@code BodyLookResolver} and
         * {@code BodyViewConstraintSolver} after this step.
         */
        if (constraint == BodyAttitudeConstraintKind.REFERENCE_ALIGNED
                || constraint == BodyAttitudeConstraintKind.FREE_ATTITUDE) {

            Quatd body = state.currentWorldFromBody();

            BodyAttitudeState next =
                    new BodyAttitudeState(
                            body,
                            body,
                            Vec3d.ZERO,
                            Math.incrementExact(state.tick()),
                            Math.incrementExact(state.revision()),
                            true
                    );

            return new BodyAttitudeStepResult(
                    next,
                    Vec3d.ZERO,
                    0.0D,
                    0,
                    false,
                    AngularTrajectory.stationary(
                            body,
                            Vec3d.ZERO
                    )
            );
        }

        Quatd original =
                state.currentWorldFromBody();

        Quatd current =
                original;

        Vec3d angularVelocity =
                state.angularVelocityWorld();

        double inputSampleDuration =
                context.dtSeconds();

        double simulatedDuration =
                simulatedDuration(
                        inputSampleDuration,
                        config
                );

        boolean timeClamped =
                inputSampleDuration > simulatedDuration;

        int substepCount =
                substepCount(
                        simulatedDuration,
                        config
                );

        double substepSeconds =
                simulatedDuration / substepCount;

        Vec3d initialAngularVelocity =
                angularVelocity;

        List<AngularTrajectory.AngularSegment> trajectorySegments =
                new java.util.ArrayList<>(
                        substepCount
                );

        Vec3d accelerationIntegral =
                Vec3d.ZERO;

        double rotationErrorRadians =
                0.0D;

        for (int i = 0; i < substepCount; i++) {
            Quatd substepStart =
                    current;

            Substep substep =
                    switch (constraint) {
                        case ELYTRA_ALIGNED ->
                                elytraControlled(
                                        current,
                                        angularVelocity,
                                        context,
                                        config,
                                        substepSeconds
                                );

                        case REFERENCE_ALIGNED, FREE_ATTITUDE ->
                                throw new IllegalStateException(
                                        constraint + " reached dynamic "
                                                + "attitude integration"
                                );
                    };

            double maximumAcceleration =
                    maximumAngularAcceleration(
                            context.controlProfile(),
                            config
                    );

            Vec3d acceleration =
                    BodyAttitudeMath.clampMagnitude(
                            substep.acceleration(),
                            maximumAcceleration
                    );

            angularVelocity =
                    BodyAttitudeMath.clampMagnitude(
                            angularVelocity.add(
                                    acceleration.multiply(
                                            substepSeconds
                                    )
                            ),
                            maximumAngularSpeed(
                                    context.controlProfile(),
                                    config
                            )
                    );

            /*
             * Elytra has an explicit aerodynamic flight frame and therefore keeps
             * its per-flight-axis rate caps.
             */
            if (constraint
                    == BodyAttitudeConstraintKind.ELYTRA_ALIGNED) {

                angularVelocity =
                        clampBodyAxisRates(
                                current,
                                angularVelocity,
                                config.elytraMaxPitchRateRadiansPerSecond(),
                                config.elytraMaxYawRateRadiansPerSecond(),
                                config.controllerRollRateRadiansPerSecond()
                        );
            }

            current =
                    BodyAttitudeMath.integrateWorldAngularVelocity(
                            current,
                            angularVelocity,
                            substepSeconds,
                            config.quaternionEpsilon()
                    );

            trajectorySegments.add(
                    new AngularTrajectory.AngularSegment(
                            substepStart,
                            current,
                            AngularTrajectory.Kind.DYNAMIC,
                            substepSeconds,
                            angularVelocity
                    )
            );

            accelerationIntegral =
                    accelerationIntegral.add(
                            acceleration.multiply(
                                    substepSeconds
                            )
                    );

            rotationErrorRadians =
                    substep.rotationErrorRadians();
        }

        Vec3d averageAcceleration =
                accelerationIntegral.multiply(
                        1.0D / simulatedDuration
                );

        BodyAttitudeState nextState =
                new BodyAttitudeState(
                        original,
                        current,
                        angularVelocity,
                        Math.incrementExact(state.tick()),
                        Math.incrementExact(state.revision()),
                        true
                );

        return new BodyAttitudeStepResult(
                nextState,
                averageAcceleration,
                rotationErrorRadians,
                substepCount,
                timeClamped,
                new AngularTrajectory(
                        initialAngularVelocity,
                        trajectorySegments
                )
        );
    }

    /**
     * Held Z/C-style input is a sustained angular-acceleration source about
     * the actual Elytra flight longitudinal axis.
     */
    private static Vec3d userControlAcceleration(
            Quatd body,
            BodyAttitudeInput input,
            BodyAttitudeControlAuthority authority,
            BodyAttitudeConfigSnapshot config
    ) {
        Vec3d rollAxis =
                AttitudeSpaceTransform.elytraForwardWorld(body);

        return rollAxis.multiply(
                input.rollAxis()
                        * authority.roll()
                        * config.elytraRollAngularAccelerationRadiansPerSecondSquared()
        );
    }

    private static Substep elytraControlled(
            Quatd current,
            Vec3d angularVelocity,
            BodyAttitudeStepContext context,
            BodyAttitudeConfigSnapshot config,
            double substepSeconds
    ) {
        /*
         * Qbody remains canonical humanoid/root state.
         *
         * Derive the Elytra flight frame explicitly instead of treating
         * humanoid +Z as flight forward.
         */
        Vec3d forward =
                AttitudeSpaceTransform.elytraForwardWorld(current);

        Vec3d dorsal =
                AttitudeSpaceTransform.elytraDorsalWorld(current);

        Vec3d belly =
                AttitudeSpaceTransform.elytraBellyWorld(current);

        /*
         * Gravity constraint:
         *
         *     belly -> resultant gravity down
         *
         * forward is a valid deterministic antiparallel fallback axis because
         * it is always perpendicular to belly.
         */
        Vec3d gravityError =
                BodyAttitudeMath.directionRotationError(
                        belly,
                        context.gravityFrame().down(),
                        forward,
                        config.vectorEpsilon()
                );

        double gravityScale =
                ScalarMath.clamp(
                        context.gravityFrame().strength()
                                / cc.sighs.gravityengine.gravity.GravityState
                                .VANILLA_STRENGTH,
                        0.0D,
                        config.elytraMaxGravityScale()
                );

        Vec3d gravityAcceleration =
                gravityError.multiply(
                        config.elytraGravityAlignmentGainPerSecondSquared()
                                * gravityScale
                );

        /*
         * Heading constraint:
         *
         *     flight forward -> resolved desired flight direction
         *
         * This constrains a direction only. It does not construct a complete
         * orientation quaternion and therefore does not implicitly reserve roll.
         */
        Vec3d desiredForward =
                BodyAttitudeMath.elytraDesiredForward(
                        current,
                        context.gravityFrame(),
                        context.lookForwardWorld(),
                        context.velocityWorld(),
                        config
                );

        Vec3d flightError =
                BodyAttitudeMath.directionRotationError(
                        forward,
                        desiredForward,
                        dorsal,
                        config.vectorEpsilon()
                );

        Vec3d flightAcceleration =
                flightError.multiply(
                        config.elytraFlightAlignmentGainPerSecondSquared()
                                * context.controlProfile()
                                .flightAlignmentWeight()
                );

        /*
         * Player roll shares the same DOF with gravity restoring.
         */
        Vec3d playerAcceleration =
                userControlAcceleration(
                        current,
                        context.input(),
                        context.controlProfile().userControl(),
                        config
                );

        /*
         * Use the exact discrete acceleration corresponding to continuous
         * exponential angular damping over this substep.
         *
         * This retains the "drag is an acceleration source" architecture while
         * preventing explicit-Euler sign reversal when drag * dt > 1.
         */
        Vec3d dragAcceleration =
                angularDragAcceleration(
                        angularVelocity,
                        config.elytraAngularDragPerSecond(),
                        substepSeconds
                );

        return new Substep(
                gravityAcceleration
                        .add(flightAcceleration)
                        .add(playerAcceleration)
                        .add(dragAcceleration),
                Math.max(
                        gravityError.length(),
                        flightError.length()
                )
        );
    }

    private static Vec3d angularDragAcceleration(
            Vec3d angularVelocity,
            double dragPerSecond,
            double dtSeconds
    ) {
        if (angularVelocity.lengthSquared() == 0.0D
                || dragPerSecond == 0.0D) {
            return Vec3d.ZERO;
        }

        if (!Double.isFinite(dragPerSecond)
                || dragPerSecond < 0.0D) {
            throw new IllegalArgumentException(
                    "Elytra angular drag must be finite and non-negative"
            );
        }

        if (!Double.isFinite(dtSeconds)
                || dtSeconds <= 0.0D) {
            throw new IllegalArgumentException(
                    "Elytra drag dt must be finite and positive"
            );
        }

        /*
         * Want:
         *
         *     omegaNext = omega * exp(-k dt)
         *
         * while the outer integrator still performs:
         *
         *     omegaNext = omega + alphaDrag * dt
         *
         * Therefore:
         *
         *     alphaDrag =
         *         -omega * (1 - exp(-k dt)) / dt
         *
         * -expm1(-k dt) is numerically stable for very small dt.
         */
        double equivalentGain =
                -Math.expm1(-dragPerSecond * dtSeconds)
                        / dtSeconds;

        return angularVelocity.multiply(-equivalentGain);
    }

    /**
     * Elytra body-axis rate limiter.
     *
     * <p>The persistent Qbody is the canonical humanoid/root attitude, while
     * the rate semantics are expressed in the derived Elytra flight frame:
     *
     * <pre>
     * pitch -> flight left
     * yaw   -> flight dorsal
     * roll  -> flight forward
     * </pre>
     *
     * The values are caps only; none of them is a target angular speed.
     */
    static Vec3d clampBodyAxisRates(
            Quatd current,
            Vec3d angularVelocityWorld,
            double elytraMaxPitchRate,
            double elytraMaxYawRate,
            double controllerRollRate
    ) {
        requirePositiveRate(
                elytraMaxPitchRate,
                "elytraMaxPitchRate"
        );
        requirePositiveRate(
                elytraMaxYawRate,
                "elytraMaxYawRate"
        );
        requirePositiveRate(
                controllerRollRate,
                "controllerRollRate"
        );

        Vec3d pitchAxis =
                AttitudeSpaceTransform.elytraLeftWorld(current);

        Vec3d yawAxis =
                AttitudeSpaceTransform.elytraDorsalWorld(current);

        Vec3d rollAxis =
                AttitudeSpaceTransform.elytraForwardWorld(current);

        double pitchRate =
                angularVelocityWorld.dot(pitchAxis);

        double yawRate =
                angularVelocityWorld.dot(yawAxis);

        double rollRate =
                angularVelocityWorld.dot(rollAxis);

        Vec3d corrected = angularVelocityWorld;

        corrected = corrected.add(
                pitchAxis.multiply(
                        clampComponent(
                                pitchRate,
                                elytraMaxPitchRate
                        ) - pitchRate
                )
        );

        corrected = corrected.add(
                yawAxis.multiply(
                        clampComponent(
                                yawRate,
                                elytraMaxYawRate
                        ) - yawRate
                )
        );

        corrected = corrected.add(
                rollAxis.multiply(
                        clampComponent(
                                rollRate,
                                controllerRollRate
                        ) - rollRate
                )
        );

        return corrected;
    }

    private static double clampComponent(double value, double maximum) {
        return Math.max(-maximum, Math.min(maximum, value));
    }

    private static void requirePositiveRate(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(
                    name + " must be finite and positive");
        }
    }

    private static double maximumAngularAcceleration(
            BodyAttitudeControlProfile profile,
            BodyAttitudeConfigSnapshot config
    ) {
        return config.elytraMaxAngularAccelerationRadiansPerSecondSquared();
    }

    private static double maximumAngularSpeed(
            BodyAttitudeControlProfile profile,
            BodyAttitudeConfigSnapshot config
    ) {
        return config.elytraMaxAngularSpeedRadiansPerSecond();
    }

    private static double simulatedDuration(
            double inputSampleDuration,
            BodyAttitudeConfigSnapshot config
    ) {
        double maximumTime =
                config.maxSubstepSeconds() * config.maxSubsteps();
        double simulatedTime =
                Math.min(inputSampleDuration, maximumTime);
        if (!Double.isFinite(simulatedTime) || simulatedTime <= 0.0D) {
            throw new IllegalArgumentException(
                    "simulated time must be finite and positive");
        }
        return simulatedTime;
    }

    private static int substepCount(
            double simulatedDuration,
            BodyAttitudeConfigSnapshot config
    ) {
        int substepCount = Math.min(config.maxSubsteps(),
                Math.max(1, (int) Math.ceil(
                        simulatedDuration / config.maxSubstepSeconds())));
        if (substepCount > config.maxSubsteps()) {
            throw new IllegalStateException(
                    "substep count exceeds configured maximum");
        }
        return substepCount;
    }

    /** One evaluated controller response for one integration substep. */
    private record Substep(
            Vec3d acceleration,
            double rotationErrorRadians
    ) {}

}
