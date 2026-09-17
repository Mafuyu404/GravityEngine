package cc.sighs.gravityengine.gravity.persistence;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static cc.sighs.gravityengine.gravity.persistence.GravityPersistenceCodec.DecodeStatus.ABSENT;
import static cc.sighs.gravityengine.gravity.persistence.GravityPersistenceCodec.DecodeStatus.CURRENT;
import static cc.sighs.gravityengine.gravity.persistence.GravityPersistenceCodec.DecodeStatus.MALFORMED;
import static cc.sighs.gravityengine.gravity.persistence.GravityPersistenceCodec.DecodeStatus.UNSUPPORTED_FUTURE;
import static cc.sighs.gravityengine.gravity.persistence.GravityPersistenceCodec.DecodeStatus.UNSUPPORTED_LEGACY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravityPersistenceCodecTest {
    @Test
    void currentRevisionLimitsRejectUnusableInputAndRoundTripTheLastValidValue() {
        var record = currentRecord();
        for (long revision : new long[]{-1, Long.MAX_VALUE}) {
            record.putLong("AssignmentRev", revision);
            var result = GravityPersistenceCodec.decodeAttachmentRecord(record);
            assertEquals(MALFORMED, result.status());
            assertTrue(result.assignment().isEmpty());
        }
        record.putLong("AssignmentRev", GravityEntityState.MAX_DURABLE_ASSIGNMENT_REVISION);
        var decoded = GravityPersistenceCodec.decodeAttachmentRecord(record);
        assertEquals(CURRENT, decoded.status());
        var encoded = GravityPersistenceCodec.encode(decoded.assignment().orElseThrow()).orElseThrow();
        assertEquals(record, encoded);
    }

    private static CompoundTag currentRecord() {
        var record = new CompoundTag();
        record.putInt(
                "FormatVersion",
                GravityPersistenceCodec.CURRENT_FORMAT_VERSION);
        record.putDouble("DownX", 1);
        record.putDouble("DownY", 0);
        record.putDouble("DownZ", 0);
        record.putDouble("Strength", .08);
        record.putLong("AssignmentRev", 19);
        record.putInt("Authority", GravityAuthorityMode.FIELD.networkId());
        record.putBoolean("FieldContinuity", true);
        return record;
    }

    @Test
    void absentAndCurrentAreDistinctFromRejectedData() {
        assertEquals(
                ABSENT,
                GravityPersistenceCodec
                        .decodeAttachmentRecord(new CompoundTag())
                        .status());

        var valid =
                GravityPersistenceCodec.decodeAttachmentRecord(currentRecord());
        assertEquals(CURRENT, valid.status());
        assertEquals(19L, valid.assignment().orElseThrow().revision());
        assertEquals(
                GravityEntityState.FieldContinuity.SEED,
                valid.assignment().orElseThrow().fieldContinuity()
        );
    }

    @Test
    void malformedRecordsRemainDiagnosable() {
        var corrupt = currentRecord();
        corrupt.putDouble("DownX", Double.NaN);
        assertEquals(
                MALFORMED,
                GravityPersistenceCodec.decodeAttachmentRecord(corrupt).status());

        corrupt = currentRecord();
        corrupt.remove("Authority");
        assertEquals(
                MALFORMED,
                GravityPersistenceCodec.decodeAttachmentRecord(corrupt).status());

        corrupt = currentRecord();
        corrupt.remove("FieldContinuity");
        assertEquals(
                MALFORMED,
                GravityPersistenceCodec.decodeAttachmentRecord(corrupt).status());

        corrupt = currentRecord();
        corrupt.remove("FormatVersion");
        assertEquals(
                MALFORMED,
                GravityPersistenceCodec.decodeAttachmentRecord(corrupt).status());
    }

    @Test
    void unsupportedVersionsAreNeverGuessed() {
        for (int version : new int[]{1, 2, 3, 4}) {
            var record = currentRecord();
            record.putInt("FormatVersion", version);
            assertEquals(
                    UNSUPPORTED_LEGACY,
                    GravityPersistenceCodec.decodeAttachmentRecord(record).status());
        }
        for (int version : new int[]{6, Integer.MAX_VALUE}) {
            var record = currentRecord();
            record.putInt("FormatVersion", version);
            assertEquals(
                    UNSUPPORTED_FUTURE,
                    GravityPersistenceCodec.decodeAttachmentRecord(record).status());
        }
    }

    @Test
    void defaultValuedPresentFieldRoundTripsWithContinuity() {
        var state = new GravityEntityState();
        assertTrue(state.setAssigned(
                GravityState.DEFAULT,
                GravityAuthorityMode.FIELD,
                true));

        var assignment = new GravityEntityState.StoredAssignment(
                state.assignedState(),
                state.assignedAuthority(),
                state.assignmentRevision(),
                state.durableFieldContinuity());
        var encoded = GravityPersistenceCodec.encode(assignment).orElseThrow();
        assertTrue(
                encoded.getBoolean("FieldContinuity"),
                "numeric default gravity must still persist FIELD provenance");

        var decoded = GravityPersistenceCodec.decodeAttachmentRecord(encoded);
        assertEquals(CURRENT, decoded.status());
        var reloaded = new GravityEntityState();
        reloaded.restoreDurableAssignment(decoded.assignment().orElseThrow());
        assertEquals(GravityState.DEFAULT, reloaded.assignedState());
        assertEquals(
                cc.sighs.gravityengine.api.FieldPresence.UNKNOWN,
                reloaded.fieldPresence());
        assertFalse(
                reloaded.durableFieldContinuity()
                        == GravityEntityState.FieldContinuity.NONE);
    }

    @Test
    void ordinaryAbsentDefaultFieldWritesNoRecord() {
        var state = new GravityEntityState();
        var assignment = new GravityEntityState.StoredAssignment(
                state.assignedState(),
                state.assignedAuthority(),
                state.assignmentRevision(),
                state.durableFieldContinuity());

        assertTrue(GravityPersistenceCodec.encode(assignment).isEmpty());
    }
}
