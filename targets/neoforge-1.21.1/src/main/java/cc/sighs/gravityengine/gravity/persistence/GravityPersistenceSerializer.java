package cc.sighs.gravityengine.gravity.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.Nullable;

/**
 * The single current-writer serializer for durable gravity state.
 *
 * <p>Its payload is the same versioned record the pre-attachment implementation
 * stored under the legacy root NBT entry, so the migration is structural rather
 * than semantic. It is deliberately not a synced attachment: the durable record
 * is not the live assignment/application wire contract.</p>
 */
public final class GravityPersistenceSerializer
        implements IAttachmentSerializer<CompoundTag, GravityPersistenceSlot> {

    public static final GravityPersistenceSerializer INSTANCE =
            new GravityPersistenceSerializer();

    private GravityPersistenceSerializer() {}

    @Override
    public GravityPersistenceSlot read(
            IAttachmentHolder holder,
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        GravityPersistenceSlot slot =
                GravityPersistenceSlot.forHolder(holder);
        GravityPersistenceCodec.DecodeResult decoded =
                GravityPersistenceCodec.decodeAttachmentRecord(tag);
        slot.applyDecoded(decoded, tag);
        GravityPersistence.diagnose(holder, decoded);
        return slot;
    }

    @Override
    public @Nullable CompoundTag write(
            GravityPersistenceSlot slot,
            HolderLookup.Provider registries
    ) {
        return GravityPersistence.encodeForSave(slot);
    }
}
