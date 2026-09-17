package cc.sighs.gravityengine.gravity.minecraft.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensionPolicy;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.KinematicPose;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Installed physical body authority and custom-gravity geometry construction.
 *
 * Contract:
 * - P = entity.position() is Vanilla's network/world position anchor;
 * - C = P + WORLD_UP * dimensions.height/2, independent of frame and Qbody;
 * - Fg = C - gravityUp * dimensions.height/2 is a support reference,
 *   not a support witness or the anatomical lower endpoint of arbitrary Qbody;
 * - installed non-default reference geometry uses a rounded axial {@code CharacterCapsule} built from
 *   authoritative dimensions, center/positionAnchor, and the gravity-up axis;
 * - Vanilla-owned geometry uses its actual AABB as an exact box, independently of solver routing;
 * - for custom capsules Entity#getBoundingBox is only the conservative enclosing AABB;
 * - the exact body and public AABB always share the same center.
 */
public final class GravityEntityGeometry {
    /** Internal exact-body/proxy agreement tolerance (one micrometre). */
    public static final double GEOMETRY_FRAME_EPSILON = 1.0E-6D;
    public static final double GEOMETRY_FRAME_EPSILON_SQUARED =
            GEOMETRY_FRAME_EPSILON * GEOMETRY_FRAME_EPSILON;

    private GravityEntityGeometry() {}

    /** Vanilla's current dimensions, including NeoForge Size event overrides. */
    public static EntityDimensions dimensions(Entity entity) {
        return GravityEntityAccess.cast(entity).gravityengine$installedDimensions();
    }

    public record InterpolatedBody(Vec3 positionAnchor, Vec3 center, GravityFrame frame) {}

    public static Vec3 positionAnchor(Entity entity) {
        return entity.position();
    }

    /** P -> C. Height is EntityDimensions height, never enclosing-AABB height. */
    public static Vec3 bodyCenterFromPositionAnchor(Vec3 positionAnchor, double height) {
        return positionAnchor.add(0.0D, height * 0.5D, 0.0D);
    }

    /** C -> P, including after a dimensions transaction. */
    public static Vec3 positionAnchorFromBodyCenter(Vec3 bodyCenter, double height) {
        return bodyCenter.subtract(0.0D, height * 0.5D, 0.0D);
    }

    /** C -> Fg. This reference point does not establish physical support. */
    public static Vec3 gravityFeetFromBodyCenter(Vec3 bodyCenter, double height, Vec3 gravityDown) {
        return bodyCenter.add(gravityDown.normalize().scale(height * 0.5D));
    }

    /** Fg -> C; presentation/reference conversion only, never network decoding. */
    public static Vec3 bodyCenterFromGravityFeet(Vec3 gravityFeet, double height, Vec3 gravityDown) {
        return gravityFeet.subtract(gravityDown.normalize().scale(height * 0.5D));
    }

    public static Vec3 gravityFeet(Entity entity, GravityFrame frame) {
        return gravityFeetFromBodyCenter(bodyCenter(entity, frame),
                dimensions(entity).height(),
                MinecraftMathAdapter.toMinecraft(frame.down()));
    }

    /**
     * Broad-phase center of the installed enclosing proxy. Exact custom
     * bodies must use {@link #bodyCenter(Entity, GravityFrame)} so orientation
     * and dimensions remain authoritative.
     */
    public static Vec3 proxyCenter(Entity entity) {
        return entity.getBoundingBox().getCenter();
    }

    public static Vec3 bodyCenter(Entity entity, GravityFrame frame) {
        return bodyCenterFromPositionAnchor(entity.position(), dimensions(entity).height());
    }

    /**
     * Exact custom-gravity collision body for an entity under a frame. The
     * entity convenience lives here (the physics adapter layer) so the pure
     * geometry model stays free of {@code Entity} dependencies.
     */
    public static CharacterCapsule exactBody(Entity entity, GravityFrame frame) {
        return candidateBody(dimensions(entity), entity.position(), frame.up());
    }

    /** Exact center of the currently installed custom body. */
    public static Vec3 installedBodyCenter(Entity entity) {
        GravityFrame installed = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent().operationState().geometryReferenceFrame();
        if (installed == null) {
            throw new IllegalStateException(
                    "custom body has no installed collision axis"
            );
        }
        return bodyCenter(entity, installed);
    }

    public static Vec3 eyePosition(Entity entity, Vec3 center, Vec3 down) {
        return eyePosition(
                entity,
                center,
                GravityFrame.downOnly(
                        MinecraftMathAdapter.toVec3d(down)
                )
        );
    }

    public static Vec3 eyePosition(Entity entity, Vec3 center, GravityFrame frame) {
        double offsetFromCenter = entity.getEyeHeight() - entity.getBbHeight() * 0.5D;
        return center.add(
                MinecraftMathAdapter.toMinecraft(frame.up())
                        .scale(offsetFromCenter));
    }

    /**
     * Eye position at the installed exact body under the authoritative
     * environmental frame.
     */
    public static Vec3 eyePosition(Entity entity) {
        GravityFrame frame =
                GravityFrameAccess.authoritativeFrame(entity);
        return eyePosition(entity, bodyCenter(entity, frame), frame);
    }

    /** Interpolated eye position for a partial-tick presentation query. */
    public static Vec3 eyePosition(Entity entity, float partialTick) {
        InterpolatedBody body = interpolatedBody(
                entity,
                GravityFrameAccess.authoritativeFrame(entity),
                partialTick
        );
        return eyePosition(entity, body.center(), body.frame());
    }

    public static Vec3 eyePosition(Vec3 center, double bodyHeight, double eyeHeight, Vec3 down) {
        double offsetFromCenter = eyeHeight - bodyHeight * 0.5D;
        return center.add(down.normalize().reverse().scale(offsetFromCenter));
    }

    /**
     * Produces one interpolated frame and center for camera, model and debug
     * rendering. The caller supplies the frame; no gravity sampling occurs here.
     */
    public static InterpolatedBody interpolatedBody(Entity entity, GravityFrame frame, float partialTick) {
        Vec3 positionAnchor = interpolatedPositionAnchor(entity, partialTick);

        Vec3 center = bodyCenterFromPositionAnchor(positionAnchor, dimensions(entity).height());

        return new InterpolatedBody(positionAnchor, center, frame);
    }

    public static GravityFrame frameAtPositionAnchor(Entity entity, GravityState state, Vec3 positionAnchor) {
        Vec3 center = bodyCenterFromPositionAnchor(positionAnchor, dimensions(entity).height());
        return GravityFrame.fromState(
                state,
                MinecraftMathAdapter.toVec3d(center)
        );
    }

    /** Installed physical representation, independent of collision solver routing.
     * External geometry does not turn a Vanilla box into a custom capsule. */
    public static CollisionBody body(Entity entity, GravityFrame frame) {
        if (!cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(entity)) {
            return cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox.axisAligned(
                    MinecraftCollisionGeometryAdapter.toAabb3d(entity.getBoundingBox()));
        }
        return candidateBody(
                entity, dimensions(entity), entity.position(), frame
        );
    }

    public static CharacterCapsule candidateBody(Entity entity, EntityDimensions dimensions,
            Vec3 positionAnchor, GravityFrame frame) {
        return candidateBody(dimensions, positionAnchor, frame.up());
    }

    /** Sole production constructor from Vanilla P, dimensions and collision up. */
    public static CharacterCapsule candidateBody(EntityDimensions dimensions, Vec3 positionAnchor, Vec3 up) {
        return candidateBody(
                dimensions,
                positionAnchor,
                MinecraftMathAdapter.toVec3d(up)
        );
    }

    public static CharacterCapsule candidateBody(
            EntityDimensions dimensions,
            Vec3 positionAnchor,
            Vec3d up
    ) {
        return characterBodyAtCenter(
                dimensions.width(),
                dimensions.height(),
                bodyCenterFromPositionAnchor(
                        positionAnchor,
                        dimensions.height()
                ),
                up
        );
    }

    public static CharacterCapsule candidateBody(EntityDimensions dimensions, Vec3 positionAnchor, GravityFrame frame) {
        return candidateBody(dimensions, positionAnchor, frame.up());
    }

    public static CharacterCapsule characterBodyAtCenter(double width, double height, Vec3 center, Vec3 up) {
        return characterBodyAtCenter(
                width,
                height,
                center,
                MinecraftMathAdapter.toVec3d(up)
        );
    }

    public static CharacterCapsule characterBodyAtCenter(
            double width,
            double height,
            Vec3 center,
            Vec3d up
    ) {
        CharacterDimensionPolicy.requireCapsule(width, height);

        return CharacterCapsule.fromDimensions(
                MinecraftMathAdapter.toVec3d(center),
                width,
                height,
                up
        );
    }

    public static CharacterCapsule candidateBodyAtCenter(Entity entity, EntityDimensions dimensions,
            Vec3 center, GravityFrame frame) {
        return characterBodyAtCenter(dimensions.width(), dimensions.height(), center, frame.up());
    }

    /**
     * P-preserving construction policy: update the collision axis while
     * retaining the entity/network position exactly.
     *
     * <p>Packet validation forbids a support-preserving preparation
     * translation. At fixed dimensions this is also the full pose-anchor
     * policy, because the canonical character geometry keeps
     * {@code C = P + worldUp * height/2}: one exact body at one P exists per
     * axis, so a separate center-preserving candidate could never differ from
     * this one.</p>
     *
     * <p>The returned pose is the loader-neutral value; this method only
     * supplies the Minecraft dimension capture and the exact-body
     * construction.</p>
     */
    public static KinematicPose posePreservingPositionAnchor(
            EntityDimensions dimensions,
            Vec3d positionAnchor,
            GravityFrame frame
    ) {
        Vec3d center = KinematicPose.characterCenter(
                positionAnchor,
                dimensions.height()
        );
        return new KinematicPose(
                positionAnchor,
                center,
                frame,
                characterBodyAtCenter(
                        dimensions.width(),
                        dimensions.height(),
                        MinecraftMathAdapter.toMinecraft(center),
                        MinecraftMathAdapter.toMinecraft(frame.up())
                )
        );
    }

    /** Rebuilds the custom broad-phase AABB while preserving the positionAnchor anchor. */
    public static void installFromPositionAnchor(Entity entity, GravityFrame frame, Vec3 positionAnchor) {
        CollisionBody body = candidateBody(
                entity, dimensions(entity), positionAnchor, frame
        );
        commitCustomBody(entity, frame, positionAnchor, body);
    }

    /** Rebuilds custom geometry while preserving the supplied body center. */
    public static void installFromCenter(Entity entity, GravityFrame frame, Vec3 center) {
        Vec3 positionAnchor = positionAnchorFromBodyCenter(center, dimensions(entity).height());
        EntityDimensions dimensions = dimensions(entity);
        CollisionBody body = candidateBodyAtCenter(
                entity, dimensions, center, frame
        );
        commitCustomBody(entity, frame, positionAnchor, body);
    }

    /**
     * Called after vanilla applies collision-resolved translation. P remains
     * authoritative; the translated proxy is discarded and rebuilt from the
     * committed positionAnchor plus the immutable operation frame.
     */
    public static void reanchorAfterVanillaTranslation(Entity entity, GravityFrame frame) {
        CollisionBody body = candidateBody(entity, dimensions(entity), entity.position(), frame);
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        try (var ignored = runtime.openGeometryMutation()) {
            entity.setBoundingBox(
                    MinecraftCollisionGeometryAdapter.toMinecraft(
                            body.enclosingAabb()));
            runtime.setInstalledCollisionAxisFromFrame(frame);
        }
    }

    /** P from an exact body's authoritative local height, never its rotated proxy. */
    public static Vec3 positionAnchorFromBody(CharacterCapsule body) {
        return positionAnchorFromBodyCenter(MinecraftMathAdapter.toMinecraft(body.center()),
                body.totalHeight());
    }

    /**
     * Single commit point for all live custom bodies. The supplied body must
     * already represent {@code positionAnchor + WORLD_UP * height/2}; the public AABB is
     * derived from it and never feeds back into the positionAnchor anchor.
     */
    public static void commitCustomBody(
            Entity entity,
            GravityFrame frame,
            Vec3 positionAnchor,
            CollisionBody body
    ) {
        if (entity == null || frame == null || positionAnchor == null || body == null) {
            throw new NullPointerException("custom body commit arguments");
        }
        if (GravityDebugLog.shouldLog(entity)) {
            Vec3 oldPositionAnchor = entity.position();
            Vec3 oldCenter = entity.getBoundingBox().getCenter();
            Vec3 positionDeltaWorld = positionAnchor.subtract(oldPositionAnchor);
            Vec3 centerDeltaWorld =
                    MinecraftMathAdapter.toMinecraft(body.center())
                            .subtract(oldCenter);
            Vec3d positionDeltaLocal = frame.worldToLocal(
                    MinecraftMathAdapter.toVec3d(positionDeltaWorld));
            Vec3d centerDeltaLocal = frame.worldToLocal(
                    MinecraftMathAdapter.toVec3d(centerDeltaWorld));
            if (Math.abs(positionDeltaLocal.y()) >= 0.02D
                    || Math.abs(centerDeltaLocal.y()) >= 0.02D) {
                GravityOperationState runtime = GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState();
                Vec3 velocity = entity.getDeltaMovement();
                GravityDebugLog.log(
                        entity,
                        "geometry-commit",
                        "runtimeInMove=%s runtimeApplyingGeometry=%s oldPositionAnchor=%s "
                                + "newPositionAnchor=%s oldCenter=%s newCenter=%s "
                                + "positionDeltaWorld=%s positionDeltaLocal=%s "
                                + "centerDeltaWorld=%s centerDeltaLocal=%s "
                                + "velocityWorld=%s velocityLocal=%s caller=%s",
                        runtime.isInMove(),
                        runtime.isApplyingGeometry(),
                        GravityDebugLog.vec(oldPositionAnchor),
                        GravityDebugLog.vec(positionAnchor),
                        GravityDebugLog.vec(oldCenter),
                        GravityDebugLog.vec(body.center()),
                        GravityDebugLog.vec(positionDeltaWorld),
                        GravityDebugLog.vec(positionDeltaLocal),
                        GravityDebugLog.vec(centerDeltaWorld),
                        GravityDebugLog.vec(centerDeltaLocal),
                        GravityDebugLog.vec(velocity),
                        GravityDebugLog.vec(
                                frame.worldToLocal(
                                        MinecraftMathAdapter.toVec3d(velocity))
                        ),
                        GravityDebugLog.callerStack()
                );
            }
        }
        CollisionBody expected = candidateBody(
                entity, dimensions(entity), positionAnchor, frame
        );
        boolean axesMatch = expected.geometricallyEquals(body);
        if (!axesMatch || !centersMatch(
                MinecraftMathAdapter.toMinecraft(expected.center()),
                MinecraftMathAdapter.toMinecraft(body.center()))
                || !aabbsMatch(expected.enclosingAabb(), body.enclosingAabb())) {
            throw new IllegalArgumentException(
                    "custom body does not match positionAnchor/frame: positionAnchor=" + positionAnchor
                            + ", frame=" + frame
                            + ", expected=" + expected
                            + ", body=" + body
            );
        }

        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        try (GravityOperationState.GeometryScope ignored = runtime.openGeometryMutation()) {
            entity.setPosRaw(positionAnchor.x, positionAnchor.y, positionAnchor.z);
            entity.setBoundingBox(
                    MinecraftCollisionGeometryAdapter.toMinecraft(
                            body.enclosingAabb()));
            runtime.setInstalledCollisionAxisFromFrame(frame);
        }
        requireGeometryMatchesFrame(entity, frame, "custom body commit");
    }

    /** Central vanilla/proxy commit; Vanilla ownership has no installed custom collision axis. */
    public static void commitVanillaBody(Entity entity, Vec3 positionAnchor, AABB box) {
        GravityOperationState runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        try (GravityOperationState.GeometryScope ignored = runtime.openGeometryMutation()) {
            entity.setPosRaw(positionAnchor.x, positionAnchor.y, positionAnchor.z);
            entity.setBoundingBox(box);
            runtime.clearInstalledCollisionAxis();
        }
    }

    /** Transaction rollback that restores geometry ownership metadata as well. */
    public static void restoreGeometry(
            Entity entity,
            Vec3 positionAnchor,
            AABB box,
            GravityFrame installedFrame
    ) {
        if (installedFrame != null) {
            CollisionBody body = candidateBody(
                    entity, dimensions(entity), positionAnchor, installedFrame
            );
            if (!aabbsMatch(body.enclosingAabb(), box)) {
                throw new IllegalStateException(
                        "saved custom geometry does not match its collision axis"
                );
            }
            commitCustomBody(entity, installedFrame, positionAnchor, body);
        } else {
            commitVanillaBody(entity, positionAnchor, box);
        }
    }

    /**
     * Exact rollback restore of a previously committed custom body and its
     * enclosing AABB.
     *
     * <p>Unlike {@link #restoreGeometry} this does not re-derive the AABB
     * from current dimensions/positionAnchor: the captured AABB is the authoritative
     * rollback value and is installed verbatim under the geometry mutation
     * scope.  The saved reference supplies only its collision axis; actor attitude and
     * environmental tangent history are not rollback geometry.</p>
     */
    public static void restoreExactGeometry(
            Entity entity,
            Vec3 positionAnchor,
            AABB box,
            GravityFrame installedFrame
    ) {
        if (entity == null || positionAnchor == null || box == null) {
            throw new NullPointerException(
                    "exact geometry restore arguments"
            );
        }
        if (!Double.isFinite(positionAnchor.x) || !Double.isFinite(positionAnchor.y)
                || !Double.isFinite(positionAnchor.z)
                || !Double.isFinite(box.minX) || !Double.isFinite(box.minY)
                || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxX)
                || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) {
            throw new IllegalArgumentException(
                    "exact geometry restore requires finite values"
            );
        }
        GravityOperationState runtime =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent().operationState();
        try (GravityOperationState.GeometryScope ignored =
                     runtime.openGeometryMutation()) {
            entity.setPosRaw(positionAnchor.x, positionAnchor.y, positionAnchor.z);
            entity.setBoundingBox(box);
            if (installedFrame != null) {
                runtime.setInstalledCollisionAxisFromFrame(installedFrame);
            } else {
                runtime.clearInstalledCollisionAxis();
            }
        }
    }

    public static boolean geometryMatchesFrame(Entity entity, GravityFrame frame) {
        if (entity == null || frame == null) return false;
        CollisionBody exact = candidateBody(
                entity,
                dimensions(entity),
                entity.position(),
                frame
        );
        return centersMatch(
                MinecraftMathAdapter.toMinecraft(exact.center()),
                entity.getBoundingBox().getCenter())
                && aabbsMatch(exact.enclosingAabb(), entity.getBoundingBox());
    }

    static boolean geometryMatchesFrame(
            Vec3 positionAnchor,
            EntityDimensions dimensions,
            GravityFrame frame,
            AABB liveProxy
    ) {
        CollisionBody exact = candidateBody(dimensions, positionAnchor, frame);
        return centersMatch(
                MinecraftMathAdapter.toMinecraft(exact.center()),
                liveProxy.getCenter())
                && aabbsMatch(exact.enclosingAabb(), liveProxy);
    }

    public static void requireGeometryMatchesFrame(
            Entity entity,
            GravityFrame frame,
            String context
    ) {
        if (geometryMatchesFrame(entity, frame)) return;
        CollisionBody exact = body(entity, frame);
        Vec3 proxy = proxyCenter(entity);
        Vec3 drift = proxy.subtract(
                MinecraftMathAdapter.toMinecraft(exact.center()));
        throw new IllegalStateException(
                context + ": custom geometry/frame mismatch: positionAnchor=" + entity.position()
                        + ", proxyCenter=" + proxy
                        + ", exactCenter=" + exact.center()
                        + ", drift=" + drift
                        + ", driftMagnitude=" + drift.length()
                        + ", frame=" + frame
                        + ", boundingBox=" + entity.getBoundingBox()
        );
    }

    static void requireMatchingCenters(Vec3 proxyCenter, Vec3 exactCenter) {
        if (!centersMatch(proxyCenter, exactCenter)) {
            Vec3 drift = proxyCenter.subtract(exactCenter);
            throw new IllegalStateException(
                    "pre-move custom geometry/frame mismatch: proxyCenter="
                            + proxyCenter + ", exactCenter=" + exactCenter
                            + ", drift=" + drift
                            + ", driftMagnitude=" + drift.length()
            );
        }
    }

    private static boolean centersMatch(Vec3 first, Vec3 second) {
        return first.subtract(second).lengthSqr()
                <= GEOMETRY_FRAME_EPSILON_SQUARED;
    }

    private static boolean aabbsMatch(AABB first, AABB second) {
        return approximatelyEqual(first.minX, second.minX)
                && approximatelyEqual(first.minY, second.minY)
                && approximatelyEqual(first.minZ, second.minZ)
                && approximatelyEqual(first.maxX, second.maxX)
                && approximatelyEqual(first.maxY, second.maxY)
                && approximatelyEqual(first.maxZ, second.maxZ);
    }

    private static boolean aabbsMatch(Aabb3d first, AABB second) {
        return aabbsMatch(
                MinecraftCollisionGeometryAdapter.toMinecraft(first), second);
    }

    private static boolean aabbsMatch(Aabb3d first, Aabb3d second) {
        return aabbsMatch(
                MinecraftCollisionGeometryAdapter.toMinecraft(first),
                MinecraftCollisionGeometryAdapter.toMinecraft(second));
    }

    private static boolean approximatelyEqual(double first, double second) {
        return Math.abs(first - second) <= GEOMETRY_FRAME_EPSILON;
    }

    /** Interpolated Vanilla world/network position anchor P used by presentation consumers. */
    public static Vec3 interpolatedPositionAnchor(Entity entity, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, entity.xo, entity.getX()),
                Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ())
        );
    }
}
