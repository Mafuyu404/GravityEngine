package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import java.util.Objects;
import java.util.Optional;

/**
 * Loader-neutral authoritative gravity state of one entity.
 *
 * <p>This owner holds everything about an entity's gravity that still has a
 * complete meaning after deleting every Minecraft/loader dependency:</p>
 *
 * <ul>
 *   <li>assigned gravity/authority and field-presence evidence;</li>
 *   <li>assignment revision and its snapshot-initialization state;</li>
 *   <li>authoritative suppression and its influence revision;</li>
 *   <li>local client suppression;</li>
 *   <li>committed application and its application epoch;</li>
 *   <li>the load-bootstrap flag;</li>
 *   <li>the latest desired (pending) application target;</li>
 *   <li>generic remote snapshot acceptance and revision monotonicity;</li>
 *   <li>durable-assignment transfer between replacement entities.</li>
 * </ul>
 *
 * <p>It deliberately owns no {@code Entity}, {@code Level}, {@code CompoundTag},
 * loader handle, logger or transport type. Persistence encoding, entity
 * identity, attachment lifecycle and diagnostics belong to the target adapter
 * that wraps this state.</p>
 */
public final class GravityEntityState {
    /** Outcome of one remote snapshot acceptance attempt. */
    public enum SnapshotAcceptance {
        ACCEPTED,
        STALE,
        CONFLICT
    }

    /**
     * Latest desired application state; the committed application is the
     * comparison authority.
     */
    public record PendingTarget(
            GravityState desiredAssignment,
            GravitySuppressionReason desiredEffectiveSuppression
    ) {
        public PendingTarget {
            Objects.requireNonNull(desiredAssignment, "desiredAssignment");
            Objects.requireNonNull(
                    desiredEffectiveSuppression,
                    "desiredEffectiveSuppression");
        }
    }

    /**
     * Durable assignment tuple restored from persistence.
     *
     * <p>Only {@code state}, {@code authority} and {@code revision} are
     * durable. Field presence is transient evidence and must be re-evaluated
     * from the destination world.</p>
     */
    public record StoredAssignment(
            GravityState state,
            GravityAuthorityMode authority,
            long revision
    ) {
        public StoredAssignment {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(authority, "authority");

            if (revision < 0L) {
                throw new IllegalArgumentException(
                        "stored assignment revision must be non-negative: "
                                + revision
                );
            }
        }
    }

    /**
     * Durable direction vectors below this squared length are treated as
     * malformed persistence rather than as a degenerate gravity assignment.
     */
    private static final double MIN_STORED_DOWN_LENGTH_SQUARED = 1.0E-7D;

    private GravityState assignedState = GravityState.DEFAULT;
    private GravityAuthorityMode assignedAuthority = GravityAuthorityMode.FIELD;

    /*
     * Contribution-presence evidence for the current FIELD assignment.
     *
     * This is independent from the numeric resultant GravityState: active
     * fields may cancel to zero or sum exactly to GravityState.DEFAULT.
     * DIRECT authority never carries field presence.
     */
    private boolean assignedFieldPresent;
    private long assignmentRevision;
    private boolean assignmentSnapshotInitialized;

    private GravitySuppressionReason authoritativeSuppression =
            GravitySuppressionReason.NONE;
    private long influenceRevision;
    private boolean influenceSnapshotInitialized;
    private GravitySuppressionReason localClientSuppression;

    private CommittedGravityApplication committedApplication =
            CommittedGravityApplication.vanillaNoAssignment();
    private long applicationEpoch;
    private boolean applicationBootstrapPending;
    private PendingTarget pendingApplication;

    // -----------------------------------------------------------------
    // Assigned state
    // -----------------------------------------------------------------

    /**
     * Persisted/bootstrap assignment result.
     *
     * <p>This value is retained for save compatibility, synchronization and
     * startup reconstruction. It is not the canonical current physical FIELD
     * result once a runtime
     * {@link cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot}
     * exists: a velocity- or time-dependent field may produce a different
     * operation-local result.</p>
     */
    public GravityState assignedState() {
        return assignedState;
    }

    public GravityAuthorityMode assignedAuthority() {
        return assignedAuthority;
    }

    public long assignmentRevision() {
        return assignmentRevision;
    }

    public boolean assignedFieldPresent() {
        return assignedFieldPresent;
    }

    /**
     * Installs a new assigned state.
     *
     * @return {@code true} when observable sync data changed and the
     *         assignment revision advanced.
     */
    public boolean setAssigned(
            GravityState state,
            GravityAuthorityMode authority,
            boolean fieldPresent
    ) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(authority);
        boolean normalizedFieldPresent =
                normalizeFieldPresent(authority, fieldPresent);
        if (assignedState.sameSyncData(state)
                && assignedAuthority == authority
                && assignedFieldPresent == normalizedFieldPresent) {
            return false;
        }
        assignedState = state;
        assignedAuthority = authority;
        assignedFieldPresent = normalizedFieldPresent;
        assignmentRevision++;
        return true;
    }

    // -----------------------------------------------------------------
    // Authoritative / local suppression
    // -----------------------------------------------------------------

    public GravitySuppressionReason authoritativeSuppression() {
        return authoritativeSuppression;
    }

    public long influenceRevision() {
        return influenceRevision;
    }

    public boolean setAuthoritativeSuppression(
            GravitySuppressionReason reason
    ) {
        Objects.requireNonNull(reason);
        if (authoritativeSuppression == reason) {
            return false;
        }
        authoritativeSuppression = reason;
        influenceRevision++;
        return true;
    }

    public boolean acceptAuthoritativeSuppression(
            GravitySuppressionReason reason,
            long revision
    ) {
        Objects.requireNonNull(reason);
        if (revision <= influenceRevision) {
            return false;
        }
        authoritativeSuppression = reason;
        influenceRevision = revision;
        return true;
    }

    public GravitySuppressionReason localClientSuppression() {
        return localClientSuppression;
    }

    public void setLocalClientSuppression(
            GravitySuppressionReason reason
    ) {
        localClientSuppression = reason;
    }

    public GravitySuppressionReason effectiveSuppression() {
        return localClientSuppression != null
                ? localClientSuppression
                : authoritativeSuppression;
    }

    // -----------------------------------------------------------------
    // Committed application
    // -----------------------------------------------------------------

    public GravityState appliedState() {
        return committedApplication.appliedState();
    }

    public CommittedGravityApplication committedApplication() {
        return committedApplication;
    }

    public GravityApplicationPlan appliedPlan() {
        return committedApplication.plan();
    }

    public long applicationEpoch() {
        return applicationEpoch;
    }

    /**
     * True while the entity's load bootstrap is incomplete.
     *
     * <p>For ordinary entities this covers application reconstruction. A
     * host-level player bootstrap may keep the flag set until gravity
     * application, body-attitude restore and fresh stream establishment have
     * all succeeded. Never serialized.</p>
     */
    public boolean applicationBootstrapPending() {
        return this.applicationBootstrapPending;
    }

    public void clearApplicationBootstrapPending() {
        this.applicationBootstrapPending = false;
    }

    public void markApplicationBootstrapPending() {
        this.applicationBootstrapPending = true;
    }

    public void commitApplication(
            CommittedGravityApplication application
    ) {
        Objects.requireNonNull(application);
        boolean changed = !committedApplication.equals(application);
        committedApplication = application;
        if (changed) {
            applicationEpoch++;
        }
    }

    // -----------------------------------------------------------------
    // Pending target ownership
    // -----------------------------------------------------------------

    public PendingTarget pendingApplication() {
        return pendingApplication;
    }

    /**
     * Atomically take the current pending target without clearing reentrant
     * additions.
     */
    public PendingTarget takePending() {
        PendingTarget target = pendingApplication;
        pendingApplication = null;
        return target;
    }

    /** Store the latest target. Incoming state always replaces older state. */
    public void enqueueOrReplace(PendingTarget target) {
        pendingApplication = Objects.requireNonNull(target, "target");
    }

    public void enqueueIfAbsent(PendingTarget target) {
        Objects.requireNonNull(target, "target");

        if (pendingApplication == null) {
            pendingApplication = target;
        }
    }

    public boolean hasPending() {
        return pendingApplication != null;
    }

    // -----------------------------------------------------------------
    // Remote snapshot acceptance
    // -----------------------------------------------------------------

    /**
     * Accepts one remote assignment snapshot.
     *
     * <p>Acceptance is monotonic in {@code revision}. An equal revision with
     * identical sync data is stale; an equal revision with different data is
     * a conflict and changes nothing. The caller owns any diagnostics.</p>
     */
    public SnapshotAcceptance acceptRemoteAssignment(
            GravityState state,
            GravityAuthorityMode authority,
            boolean fieldPresent,
            long revision
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(authority, "authority");
        boolean normalizedFieldPresent =
                normalizeFieldPresent(authority, fieldPresent);

        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "assignment revision must be non-negative: " + revision
            );
        }

        if (!assignmentSnapshotInitialized) {
            assignSnapshot(
                    state,
                    authority,
                    normalizedFieldPresent,
                    revision
            );
            assignmentSnapshotInitialized = true;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision > assignmentRevision) {
            assignSnapshot(
                    state,
                    authority,
                    normalizedFieldPresent,
                    revision
            );
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision < assignmentRevision) {
            return SnapshotAcceptance.STALE;
        }

        if (assignedState.sameSyncData(state)
                && assignedAuthority == authority
                && assignedFieldPresent == normalizedFieldPresent) {
            return SnapshotAcceptance.STALE;
        }

        return SnapshotAcceptance.CONFLICT;
    }

    /**
     * Accepts one remote suppression snapshot with the same monotonic
     * revision and conflict semantics as assignment acceptance.
     */
    public SnapshotAcceptance acceptRemoteSuppression(
            GravitySuppressionReason suppression,
            long revision
    ) {
        Objects.requireNonNull(suppression, "suppression");

        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "influence revision must be non-negative: " + revision
            );
        }

        if (!influenceSnapshotInitialized) {
            authoritativeSuppression = suppression;
            influenceRevision = revision;
            influenceSnapshotInitialized = true;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision > influenceRevision) {
            authoritativeSuppression = suppression;
            influenceRevision = revision;
            return SnapshotAcceptance.ACCEPTED;
        }

        if (revision < influenceRevision) {
            return SnapshotAcceptance.STALE;
        }

        if (authoritativeSuppression == suppression) {
            return SnapshotAcceptance.STALE;
        }

        return SnapshotAcceptance.CONFLICT;
    }

    public void resetSnapshotInitialization() {
        assignmentSnapshotInitialized = false;
        influenceSnapshotInitialized = false;
    }

    // -----------------------------------------------------------------
    // Durability
    // -----------------------------------------------------------------

    /**
     * Decodes one durable assignment tuple from primitive persisted values.
     *
     * <p>This is protocol-neutral validation: non-finite components, a
     * degenerate stored direction, a negative revision and an unknown
     * authority id are all rejected as malformed persistence without throwing
     * at the caller.</p>
     */
    public static Optional<StoredAssignment> decodeStoredAssignment(
            double downX,
            double downY,
            double downZ,
            double strength,
            long revision,
            int authorityNetworkId
    ) {
        Vec3d down = new Vec3d(downX, downY, downZ);

        if (!down.isFinite()) {
            return Optional.empty();
        }

        double downLengthSquared = down.lengthSquared();
        if (!Double.isFinite(downLengthSquared)
                || downLengthSquared < MIN_STORED_DOWN_LENGTH_SQUARED) {
            return Optional.empty();
        }

        if (!Double.isFinite(strength)) {
            return Optional.empty();
        }

        if (revision < 0L) {
            return Optional.empty();
        }

        GravityAuthorityMode authority;
        try {
            authority = GravityAuthorityMode.fromNetworkId(authorityNetworkId);
        } catch (IllegalArgumentException malformedAuthority) {
            return Optional.empty();
        }

        GravityState state;
        try {
            state = new GravityState(down, strength);
        } catch (IllegalArgumentException malformedState) {
            return Optional.empty();
        }

        return Optional.of(
                new StoredAssignment(state, authority, revision)
        );
    }

    /**
     * Resets every derived value and installs one durable assignment.
     *
     * <p>Committed application, installed geometry evidence, revisions and
     * snapshot initialization are all discarded: a loaded entity must
     * re-derive them from its destination world.</p>
     */
    public void restoreDurableAssignment(StoredAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        resetForLoad();
        assignSnapshot(
                assignment.state(),
                assignment.authority(),
                false,
                assignment.revision()
        );
        this.applicationBootstrapPending = true;
    }

    /** Player replacement transfers durable assignment, never derived state. */
    public void copyDurableAssignmentFrom(GravityEntityState source) {
        Objects.requireNonNull(source, "source");
        restoreDurableAssignment(
                new StoredAssignment(
                        source.assignedState,
                        source.assignedAuthority,
                        source.assignmentRevision
                )
        );
    }

    /**
     * Discards every derived value, including committed application and
     * snapshot initialization, without touching durable assignment.
     *
     * <p>Every load path must mark the bootstrap pending, because even an
     * absent or malformed durable tuple still needs current FIELD evidence
     * before the first movement tick.</p>
     */
    public void resetForLoad() {
        this.authoritativeSuppression = GravitySuppressionReason.NONE;
        this.influenceRevision = 0L;
        this.localClientSuppression = null;

        this.committedApplication =
                CommittedGravityApplication.vanillaNoAssignment();
        this.applicationEpoch = 0L;
        this.applicationBootstrapPending = true;
        this.pendingApplication = null;

        this.assignedState = GravityState.DEFAULT;
        this.assignedAuthority = GravityAuthorityMode.FIELD;
        this.assignedFieldPresent = false;
        this.assignmentRevision = 0L;

        resetSnapshotInitialization();
    }

    private void assignSnapshot(
            GravityState state,
            GravityAuthorityMode authority,
            boolean normalizedFieldPresent,
            long revision
    ) {
        assignedState = state;
        assignedAuthority = authority;
        assignedFieldPresent = normalizedFieldPresent;
        assignmentRevision = revision;
    }

    private static boolean normalizeFieldPresent(
            GravityAuthorityMode authority,
            boolean fieldPresent
    ) {
        if (authority != GravityAuthorityMode.FIELD && fieldPresent) {
            throw new IllegalArgumentException(
                    "fieldPresent is valid only under FIELD authority");
        }
        return authority == GravityAuthorityMode.FIELD && fieldPresent;
    }
}
