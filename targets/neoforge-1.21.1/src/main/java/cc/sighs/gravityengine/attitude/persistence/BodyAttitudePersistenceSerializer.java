package cc.sighs.gravityengine.attitude.persistence;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned NeoForge attachment serializer for the persistence seed.
 *
 * <p>Format 6 stores dynamic continuity as {@code L_world} only. Effective
 * inertia is profile/config-owned and is therefore rebound by the receiving
 * restore contract instead of being duplicated on disk. Format 5 is accepted
 * for migration; its legacy inertia field is validated and then discarded.</p>
 */
public final class BodyAttitudePersistenceSerializer
        implements IAttachmentSerializer<
                CompoundTag,
                BodyAttitudePersistenceSlot
        > {

    public static final BodyAttitudePersistenceSerializer INSTANCE =
            new BodyAttitudePersistenceSerializer();

    private static final int FORMAT_VERSION = 6;
    private static final int LEGACY_FORMAT_VERSION_WITH_INERTIA = 5;

    private static final String TAG_VERSION = "Version";

    private static final String TAG_QX = "BodyQX";
    private static final String TAG_QY = "BodyQY";
    private static final String TAG_QZ = "BodyQZ";
    private static final String TAG_QW = "BodyQW";

    private static final String TAG_CONTROLLER_X = "ControllerQX";
    private static final String TAG_CONTROLLER_Y = "ControllerQY";
    private static final String TAG_CONTROLLER_Z = "ControllerQZ";
    private static final String TAG_CONTROLLER_W = "ControllerQW";

    private static final String TAG_ANGULAR_DYNAMICS =
            "AngularDynamics";

    private static final String TAG_MOMENTUM_X =
            "AngularMomentumX";
    private static final String TAG_MOMENTUM_Y =
            "AngularMomentumY";
    private static final String TAG_MOMENTUM_Z =
            "AngularMomentumZ";

    private static final String TAG_LEGACY_INERTIA =
            "EffectiveAngularInertia";

    private BodyAttitudePersistenceSerializer() {}

    @Override
    public BodyAttitudePersistenceSlot read(
            IAttachmentHolder holder,
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        BodyAttitudePersistenceSlot slot =
                BodyAttitudePersistenceSlot.forHolder(holder);

        decode(tag).ifPresent(slot::stageLoadedSeed);
        return slot;
    }

    @Override
    public CompoundTag write(
            BodyAttitudePersistenceSlot slot,
            HolderLookup.Provider registries
    ) {
        return slot.snapshotForSave()
                .map(BodyAttitudePersistenceSerializer::encode)
                .orElseGet(CompoundTag::new);
    }

    static CompoundTag encode(
            BodyAttitudePersistentSeed seed
    ) {
        CompoundTag tag = new CompoundTag();

        tag.putInt(TAG_VERSION, FORMAT_VERSION);

        Quatd q = seed.worldFromBody();
        tag.putDouble(TAG_QX, q.x());
        tag.putDouble(TAG_QY, q.y());
        tag.putDouble(TAG_QZ, q.z());
        tag.putDouble(TAG_QW, q.w());

        Quatd controller = seed.worldFromController();
        tag.putDouble(TAG_CONTROLLER_X, controller.x());
        tag.putDouble(TAG_CONTROLLER_Y, controller.y());
        tag.putDouble(TAG_CONTROLLER_Z, controller.z());
        tag.putDouble(TAG_CONTROLLER_W, controller.w());

        seed.angularMomentumWorld().ifPresent(momentum -> {
            CompoundTag angular = new CompoundTag();

            angular.putDouble(
                    TAG_MOMENTUM_X,
                    momentum.x()
            );
            angular.putDouble(
                    TAG_MOMENTUM_Y,
                    momentum.y()
            );
            angular.putDouble(
                    TAG_MOMENTUM_Z,
                    momentum.z()
            );

            tag.put(
                    TAG_ANGULAR_DYNAMICS,
                    angular
            );
        });

        return tag;
    }

    static Optional<BodyAttitudePersistentSeed> decode(
            CompoundTag tag
    ) {
        if (!tag.contains(TAG_VERSION, Tag.TAG_INT)) {
            return Optional.empty();
        }

        int version = tag.getInt(TAG_VERSION);

        if (version != FORMAT_VERSION
                && version
                != LEGACY_FORMAT_VERSION_WITH_INERTIA) {
            return Optional.empty();
        }

        if (!hasDouble(tag, TAG_QX)
                || !hasDouble(tag, TAG_QY)
                || !hasDouble(tag, TAG_QZ)
                || !hasDouble(tag, TAG_QW)
                || !hasDouble(tag, TAG_CONTROLLER_X)
                || !hasDouble(tag, TAG_CONTROLLER_Y)
                || !hasDouble(tag, TAG_CONTROLLER_Z)
                || !hasDouble(tag, TAG_CONTROLLER_W)) {
            return Optional.empty();
        }

        Set<String> expectedRootKeys =
                new HashSet<>(
                        Set.of(
                                TAG_VERSION,
                                TAG_QX,
                                TAG_QY,
                                TAG_QZ,
                                TAG_QW,
                                TAG_CONTROLLER_X,
                                TAG_CONTROLLER_Y,
                                TAG_CONTROLLER_Z,
                                TAG_CONTROLLER_W
                        )
                );

        if (tag.contains(TAG_ANGULAR_DYNAMICS)) {
            expectedRootKeys.add(
                    TAG_ANGULAR_DYNAMICS
            );
        }

        if (!tag.getAllKeys().equals(expectedRootKeys)) {
            return Optional.empty();
        }

        try {
            Optional<Vec3d> momentum =
                    Optional.empty();

            if (tag.contains(TAG_ANGULAR_DYNAMICS)) {
                if (!tag.contains(
                        TAG_ANGULAR_DYNAMICS,
                        Tag.TAG_COMPOUND
                )) {
                    return Optional.empty();
                }

                CompoundTag angular =
                        tag.getCompound(
                                TAG_ANGULAR_DYNAMICS
                        );

                Set<String> expectedAngularKeys =
                        version == FORMAT_VERSION
                                ? Set.of(
                                TAG_MOMENTUM_X,
                                TAG_MOMENTUM_Y,
                                TAG_MOMENTUM_Z
                        )
                                : Set.of(
                                TAG_MOMENTUM_X,
                                TAG_MOMENTUM_Y,
                                TAG_MOMENTUM_Z,
                                TAG_LEGACY_INERTIA
                        );

                if (!angular.getAllKeys()
                        .equals(expectedAngularKeys)
                        || !hasDouble(
                        angular,
                        TAG_MOMENTUM_X
                )
                        || !hasDouble(
                        angular,
                        TAG_MOMENTUM_Y
                )
                        || !hasDouble(
                        angular,
                        TAG_MOMENTUM_Z
                )) {
                    return Optional.empty();
                }

                /*
                 * V5 migration: the legacy inertia field is validated but
                 * deliberately not restored as state authority. The receiving
                 * profile/config owns effective inertia.
                 */
                if (version
                        == LEGACY_FORMAT_VERSION_WITH_INERTIA) {
                    if (!hasDouble(
                            angular,
                            TAG_LEGACY_INERTIA
                    )) {
                        return Optional.empty();
                    }

                    double legacyInertia =
                            angular.getDouble(
                                    TAG_LEGACY_INERTIA
                            );

                    if (!Double.isFinite(legacyInertia)
                            || legacyInertia <= 0.0D) {
                        return Optional.empty();
                    }
                }

                momentum = Optional.of(
                        new Vec3d(
                                angular.getDouble(
                                        TAG_MOMENTUM_X
                                ),
                                angular.getDouble(
                                        TAG_MOMENTUM_Y
                                ),
                                angular.getDouble(
                                        TAG_MOMENTUM_Z
                                )
                        )
                );
            }

            return Optional.of(
                    new BodyAttitudePersistentSeed(
                            new Quatd(
                                    tag.getDouble(TAG_QX),
                                    tag.getDouble(TAG_QY),
                                    tag.getDouble(TAG_QZ),
                                    tag.getDouble(TAG_QW)
                            ),
                            new Quatd(
                                    tag.getDouble(
                                            TAG_CONTROLLER_X
                                    ),
                                    tag.getDouble(
                                            TAG_CONTROLLER_Y
                                    ),
                                    tag.getDouble(
                                            TAG_CONTROLLER_Z
                                    ),
                                    tag.getDouble(
                                            TAG_CONTROLLER_W
                                    )
                            ),
                            momentum
                    )
            );
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static boolean hasDouble(
            CompoundTag tag,
            String key
    ) {
        return tag.contains(
                key,
                Tag.TAG_DOUBLE
        );
    }
}
