package cc.sighs.gravityengine.gravity.persistence;

import cc.sighs.gravityengine.GravityEngineAttachments;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * Target-side orchestration for the durable gravity attachment.
 *
 * <p>It owns exactly two things: creating the attachment when durable state
 * first needs to exist, and the bounded one-way import of the pre-attachment
 * root NBT entry. It is never a second writer: after migration, saves are
 * produced only by {@link GravityPersistenceSerializer}.</p>
 */
public final class GravityPersistence {
    private static final Logger LOGGER = LogUtils.getLogger();

    private GravityPersistence() {}

    /**
     * Materializes the durable attachment on an entity.
     *
     * <p>NeoForge serializes only attachment instances present on the holder,
     * so this must be called before the next save of a durable non-empty
     * gravity state. The slot itself snapshots live state at write time.</p>
     */
    public static GravityPersistenceSlot materialize(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return entity.getData(GravityEngineAttachments.GRAVITY_PERSISTENCE);
    }

    @Nullable
    static CompoundTag encodeForSave(GravityPersistenceSlot slot) {
        Objects.requireNonNull(slot, "slot");
        Optional<cc.sighs.gravityengine.gravity.model.GravityEntityState.StoredAssignment>
                live = slot.snapshotForSave();
        if (live.isPresent()) {
            return GravityPersistenceCodec.encode(live.get()).orElse(null);
        }
        /*
         * An uninterpretable record is preserved verbatim until a later
         * authoritative assignment supersedes it. Malformed/unsupported data
         * must never be silently rewritten as default gravity.
         */
        return slot.preservedUndecodableRecord();
    }

    /**
     * Bounded one-way import of the legacy root entity NBT entry.
     *
     * <p>Precedence: a present new attachment record always wins, even when it
     * is malformed. The legacy tag is only read when no attachment record
     * exists, and it is never written back.</p>
     */
    public static void importLegacyIfAbsent(
            Entity entity,
            CompoundTag entityTag
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(entityTag, "entityTag");
        if (entity.getExistingDataOrNull(
                GravityEngineAttachments.GRAVITY_PERSISTENCE) != null) {
            return;
        }
        GravityPersistenceCodec.DecodeResult decoded =
                GravityPersistenceCodec.decodeLegacyEntityTag(entityTag);
        diagnose(entity, decoded);
        if (decoded.assignment().isEmpty()) {
            /*
             * Absent, malformed or unsupported legacy data stays read-only and
             * diagnosable; it never becomes a second persistence owner.
             */
            /*
             * Neither a new attachment record nor a supported legacy record
             * exists: normal default bootstrap. The entity object was loaded
             * from NBT, so all derived runtime state must be discarded.
             */
            cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess
                    .cast(entity)
                    .gravityengine$gravityComponent()
                    .state()
                    .resetForLoad();
            return;
        }
        GravityPersistenceSlot slot = materialize(entity);
        slot.applyDecoded(decoded, null);
    }

    static void diagnose(
            IAttachmentHolder holder,
            GravityPersistenceCodec.DecodeResult decoded
    ) {
        switch (decoded.status()) {
            case ABSENT, CURRENT, MIGRATED -> {
                /* Valid or intentionally empty. */
            }
            case MALFORMED, UNSUPPORTED_LEGACY, UNSUPPORTED_FUTURE -> {
                String owner = holder instanceof Entity entity
                        ? entity.getUUID().toString()
                        : String.valueOf(holder);
                LOGGER.warn(
                        "GravityEngine persistence record for entity {} was "
                                + "rejected as {} and was not applied",
                        owner,
                        decoded.status()
                );
            }
        }
    }
}
