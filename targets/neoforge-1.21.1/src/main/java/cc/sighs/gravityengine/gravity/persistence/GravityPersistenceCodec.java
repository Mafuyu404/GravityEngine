package cc.sighs.gravityengine.gravity.persistence;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.Optional;

/**
 * Explicit gravity persistence decoding/encoding boundary.
 *
 * <p>Two schemas share one record layout:</p>
 *
 * <ul>
 *   <li>the current NeoForge serializable attachment, whose payload is the
 *   record compound itself;</li>
 *   <li>the bounded one-way legacy importer, which reads the record from the
 *   old root entity NBT entry {@code GravityEngineGravity} and never writes
 *   it back.</li>
 * </ul>
 *
 * <p>The importer is a read-only compatibility boundary, not a second
 * persistence owner: after migration the current attachment serializer is the
 * only writer.</p>
 */
public final class GravityPersistenceCodec {
    public enum DecodeStatus {
        ABSENT,
        CURRENT,
        MIGRATED,
        MALFORMED,
        UNSUPPORTED_LEGACY,
        UNSUPPORTED_FUTURE
    }

    public record DecodeResult(
            DecodeStatus status,
            Optional<GravityEntityState.StoredAssignment> assignment
    ) {
        public DecodeResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(assignment, "assignment");
        }

        static DecodeResult of(DecodeStatus status) {
            return new DecodeResult(status, Optional.empty());
        }
    }

    /** Old root entity NBT entry written by the pre-attachment implementation. */
    public static final String LEGACY_ROOT_TAG = "GravityEngineGravity";

    static final String NBT_VERSION = "FormatVersion";
    static final String NBT_DOWN_X = "DownX";
    static final String NBT_DOWN_Y = "DownY";
    static final String NBT_DOWN_Z = "DownZ";
    static final String NBT_STRENGTH = "Strength";
    static final String NBT_ASSIGNMENT_REVISION = "AssignmentRev";
    static final String NBT_AUTHORITY = "Authority";
    static final String NBT_FIELD_CONTINUITY = "FieldContinuity";

    /**
     * Version 5 adds the durable FIELD continuity provenance.
     *
     * <p>Version 4 recorded only state/authority/revision, so it cannot
     * distinguish "confirmed absent default FIELD" from "FIELD was present and
     * its resultant happened to equal default gravity". Version 4 data is
     * migrated only where provenance is provable from the legacy record.</p>
     */
    public static final int CURRENT_FORMAT_VERSION = 5;
    public static final int LEGACY_FIELD_CONTINUITY_FORMAT_VERSION = 4;

    private GravityPersistenceCodec() {}

    /**
     * Decodes the current attachment payload.
     *
     * <p>An empty compound is {@link DecodeStatus#ABSENT}: NeoForge never
     * invokes the serializer for a value that was not written, and the
     * serializer itself returns {@code null} for an intentionally empty
     * durable record.</p>
     */
    public static DecodeResult decodeAttachmentRecord(CompoundTag record) {
        Objects.requireNonNull(record, "record");
        if (record.isEmpty()) return DecodeResult.of(DecodeStatus.ABSENT);
        if (!record.contains(NBT_VERSION, CompoundTag.TAG_INT)) {
            return DecodeResult.of(DecodeStatus.MALFORMED);
        }
        int version = record.getInt(NBT_VERSION);
        if (version > CURRENT_FORMAT_VERSION) {
            return DecodeResult.of(DecodeStatus.UNSUPPORTED_FUTURE);
        }
        if (version == LEGACY_FIELD_CONTINUITY_FORMAT_VERSION) {
            return decodeLegacyV4(record);
        }
        if (version != CURRENT_FORMAT_VERSION) {
            return DecodeResult.of(
                    version > 0
                            ? DecodeStatus.UNSUPPORTED_LEGACY
                            : DecodeStatus.MALFORMED
            );
        }
        Optional<GravityEntityState.StoredAssignment> decoded =
                decodeCurrentRecord(record);
        return new DecodeResult(
                decoded.isPresent() ? DecodeStatus.CURRENT : DecodeStatus.MALFORMED,
                decoded
        );
    }

    /**
     * Read-only decode of the legacy root entity NBT entry.
     *
     * <p>Never writes the legacy key. Callers must apply the
     * new-attachment-wins precedence before invoking this.</p>
     */
    public static DecodeResult decodeLegacyEntityTag(CompoundTag entityTag) {
        Objects.requireNonNull(entityTag, "entityTag");
        if (!entityTag.contains(LEGACY_ROOT_TAG)) {
            return DecodeResult.of(DecodeStatus.ABSENT);
        }
        if (!entityTag.contains(LEGACY_ROOT_TAG, CompoundTag.TAG_COMPOUND)) {
            return DecodeResult.of(DecodeStatus.MALFORMED);
        }
        return decodeAttachmentRecord(entityTag.getCompound(LEGACY_ROOT_TAG));
    }

    static Optional<CompoundTag> encode(
            GravityEntityState.StoredAssignment assignment
    ) {
        Objects.requireNonNull(assignment, "assignment");
        if (assignment.state().isDefault()
                && assignment.authority() == GravityAuthorityMode.FIELD
                && assignment.fieldContinuity()
                == GravityEntityState.FieldContinuity.NONE
                && assignment.revision() == 0L) {
            /*
             * An ordinary absent default FIELD owns no durable fact. Omit the
             * record entirely; numeric equality with default gravity never
             * removes an explicit continuity seed.
             */
            return Optional.empty();
        }
        CompoundTag record = new CompoundTag();
        record.putInt(NBT_VERSION, CURRENT_FORMAT_VERSION);
        record.putDouble(NBT_DOWN_X, assignment.state().down().x());
        record.putDouble(NBT_DOWN_Y, assignment.state().down().y());
        record.putDouble(NBT_DOWN_Z, assignment.state().down().z());
        record.putDouble(NBT_STRENGTH, assignment.state().strength());
        record.putLong(NBT_ASSIGNMENT_REVISION, assignment.revision());
        record.putInt(NBT_AUTHORITY, assignment.authority().networkId());
        record.putBoolean(
                NBT_FIELD_CONTINUITY,
                assignment.fieldContinuity()
                        == GravityEntityState.FieldContinuity.SEED
        );
        return Optional.of(record);
    }

    static Optional<GravityEntityState.StoredAssignment> decodeCurrentRecord(
            CompoundTag record
    ) {
        Objects.requireNonNull(record, "record");

        if (!record.contains(NBT_VERSION, CompoundTag.TAG_INT)
                || record.getInt(NBT_VERSION) != CURRENT_FORMAT_VERSION) {
            return Optional.empty();
        }

        if (!record.contains(NBT_DOWN_X, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_DOWN_Y, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_DOWN_Z, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_STRENGTH, CompoundTag.TAG_DOUBLE)
                || !record.contains(
                NBT_ASSIGNMENT_REVISION,
                CompoundTag.TAG_LONG
        )
                || !record.contains(NBT_AUTHORITY, CompoundTag.TAG_INT)
                || !record.contains(NBT_FIELD_CONTINUITY, CompoundTag.TAG_BYTE)) {
            return Optional.empty();
        }

        return GravityEntityState.decodeStoredAssignment(
                record.getDouble(NBT_DOWN_X),
                record.getDouble(NBT_DOWN_Y),
                record.getDouble(NBT_DOWN_Z),
                record.getDouble(NBT_STRENGTH),
                record.getLong(NBT_ASSIGNMENT_REVISION),
                record.getInt(NBT_AUTHORITY),
                record.getBoolean(NBT_FIELD_CONTINUITY)
        );
    }

    /**
     * Explicit version-4 migration.
     *
     * <p>A version-4 record did not store FIELD continuity provenance. It is
     * migrated only where the legacy tuple proves the answer:</p>
     *
     * <ul>
     *   <li>{@code DIRECT} authority never owns FIELD continuity;</li>
     *   <li>a non-default {@code FIELD} state was FIELD-derived continuity, so
     *   it migrates with a continuity seed;</li>
     *   <li>a default {@code FIELD} state is ambiguous between "confirmed
     *   absent" and "present but numerically default", so it is classified as
     *   unsupported legacy instead of being guessed.</li>
     * </ul>
     */
    static DecodeResult decodeLegacyV4(CompoundTag record) {
        Objects.requireNonNull(record, "record");
        if (!record.contains(NBT_DOWN_X, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_DOWN_Y, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_DOWN_Z, CompoundTag.TAG_DOUBLE)
                || !record.contains(NBT_STRENGTH, CompoundTag.TAG_DOUBLE)
                || !record.contains(
                NBT_ASSIGNMENT_REVISION,
                CompoundTag.TAG_LONG
        )
                || !record.contains(NBT_AUTHORITY, CompoundTag.TAG_INT)) {
            return DecodeResult.of(DecodeStatus.MALFORMED);
        }

        Optional<GravityEntityState.StoredAssignment> decoded =
                GravityEntityState.decodeStoredAssignment(
                        record.getDouble(NBT_DOWN_X),
                        record.getDouble(NBT_DOWN_Y),
                        record.getDouble(NBT_DOWN_Z),
                        record.getDouble(NBT_STRENGTH),
                        record.getLong(NBT_ASSIGNMENT_REVISION),
                        record.getInt(NBT_AUTHORITY),
                        false
                );
        if (decoded.isEmpty()) {
            return DecodeResult.of(DecodeStatus.MALFORMED);
        }
        GravityEntityState.StoredAssignment assignment = decoded.get();
        if (assignment.authority() == GravityAuthorityMode.DIRECT) {
            return new DecodeResult(DecodeStatus.MIGRATED, decoded);
        }
        if (!assignment.state().isDefault()) {
            /*
             * The legacy runtime treated a non-default FIELD assignment as
             * FIELD-derived continuity. That inference is provable.
             */
            return new DecodeResult(
                    DecodeStatus.MIGRATED,
                    Optional.of(
                            GravityEntityState.storedAssignment(
                                    assignment.state(),
                                    assignment.authority(),
                                    assignment.revision(),
                                    GravityEntityState.FieldContinuity.SEED
                            )
                    )
            );
        }
        /*
         * A default FIELD record cannot distinguish confirmed absence from a
         * numerically default active contribution. Guessing either way would
         * corrupt durable provenance, so it stays diagnosable.
         */
        return DecodeResult.of(DecodeStatus.UNSUPPORTED_LEGACY);
    }
}
