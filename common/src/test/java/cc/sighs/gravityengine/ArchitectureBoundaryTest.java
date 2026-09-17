package cc.sighs.gravityengine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {
    private static final List<String>
            INTERNAL_API_PREFIXES = List.of(
                    "cc.sighs.gravityengine.math.",
                    "cc.sighs.gravityengine.gravity.",
                    "cc.sighs.gravityengine.attitude."
            );

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.joml",
            "net.minecraft",
            "net.neoforged",
            "net.minecraftforge",
            "net.fabricmc",
            "org.spongepowered"
    );

    /**
     * JOML is allowed only at explicit renderer, Minecraft-adapter, camera and
     * third-party ABI boundaries. Adding a file here is an architectural
     * decision, not an incidental import cleanup.
     */
    private static final Set<String> TARGET_JOML_BOUNDARY_SOURCES =
            Set.of(
                    "cc/sighs/gravityengine/client/"
                            + "ClientGravityFrameSampler.java",
                    "cc/sighs/gravityengine/client/"
                            + "GravityRenderTransforms.java",
                    "cc/sighs/gravityengine/mixin/CameraMixin.java",
                    "cc/sighs/gravityengine/gravity/integration/"
                            + "compat/sable/SableMovementAdapter.java",
                    "cc/sighs/gravityengine/gravity/integration/"
                            + "compat/sable/SableRigidCollisionProvider.java",
                    "cc/sighs/gravityengine/gravity/integration/"
                            + "compat/sable/"
                            + "SablePlayerCollisionCompatibility.java",
                    "cc/sighs/gravityengine/gravity/integration/compat/sable/SableGravityBridge.java",
                    "cc/sighs/gravityengine/mixin/compat/sable/SablePhysicsBaselineMixin.java",
                    "cc/sighs/gravityengine/mixin/compat/sable/"
                            + "SableSubLevelEntityCollisionMixin.java"
            );

    @Test
    void commonProductionSourcesHaveNoPlatformOrJomlImports() throws IOException {
        Path sourceRoot = commonSourceRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertTrue(violations.isEmpty(),
                "forbidden production imports:\n" + String.join("\n", violations));
    }

    @Test
    void targetProductionJomlImportsStayAtExplicitBoundaries()
            throws IOException {
        Path sourceRoot = targetSourceRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path path : files
                    .filter(file ->
                            file.toString().endsWith(".java"))
                    .toList()) {
                String relative = sourceRoot.relativize(path)
                        .toString()
                        .replace(
                                java.io.File.separatorChar,
                                '/'
                        );
                if (TARGET_JOML_BOUNDARY_SOURCES.contains(relative)) {
                    continue;
                }
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import org.joml")) {
                        violations.add(path + ": " + trimmed);
                    }
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                "JOML escaped its boundary allowlist:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void supportedTargetSnapshotsDoNotExposeMinecraftVectors()
            throws IOException {
        Path apiRoot = targetSourceRoot().resolve(
                Path.of(
                        "cc",
                        "sighs",
                        "gravityengine",
                        "api"
                )
        );
        for (String fileName : List.of(
                "EntityGravitySnapshot.java",
                "GravityFrameView.java"
        )) {
            String source = Files.readString(apiRoot.resolve(fileName));
            assertFalse(
                    source.contains("net.minecraft.world.phys.Vec3"),
                    fileName + " must expose canonical Vec3d values"
            );
        }
    }

    @Test
    void supportedApiDoesNotExposeInternalTypes()
            throws Exception {
        Path sourceRoot = commonSourceRoot();
        Path apiRoot = sourceRoot.resolve(
                Path.of(
                        "cc",
                        "sighs",
                        "gravityengine",
                        "api"
                )
        );

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(apiRoot)) {
            for (Path path : files
                    .filter(file ->
                            file.toString()
                                    .endsWith(".java"))
                    .toList()) {

                String relative =
                        sourceRoot.relativize(path)
                                .toString()
                                .replace(
                                        java.io.File.separatorChar,
                                        '.'
                                );

                String className =
                        relative.substring(
                                0,
                                relative.length()
                                        - ".java".length()
                        );

                if (className.endsWith(
                        ".package-info"
                ) || className.endsWith(
                        ".module-info"
                )) {
                    continue;
                }

                Class<?> type =
                        Class.forName(className);

                if (!Modifier.isPublic(
                        type.getModifiers()
                )) {
                    continue;
                }

                inspectApiType(
                        type,
                        violations
                );
            }
        }

        assertTrue(
                violations.isEmpty(),
                "supported API leaks internal types:\n"
                        + String.join(
                                "\n",
                                violations
                        )
        );
    }

    @Test
    void pathContactAbstractionIsNotKeptAsDeadCode()
            throws IOException {
        Path contactWitness = commonSourceRoot().resolve(
                Path.of(
                        "cc",
                        "sighs",
                        "gravityengine",
                        "gravity",
                        "collision",
                        "ContactWitness.java"
                )
        );

        assertFalse(
                Files.exists(contactWitness),
                "ContactWitness must not exist unless a real production "
                        + "path consumes it"
        );
    }

    @Test
    void commonMustNotDependOnSable()
            throws IOException {
        Path sourceRoot = commonSourceRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path path : files
                    .filter(file ->
                            file.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path)
                        .toLowerCase(Locale.ROOT);
                if (source.contains("dev.ryanhcode")) {
                    violations.add(path.toString());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                "common must not depend on optional compatibility mods:\n"
                        + String.join("\n", violations)
        );
    }

    /**
     * Generic common concepts must not be documented with one optional
     * compatibility mod's vocabulary. Target-side compatibility classes may
     * still use their owning mod's names.
     */
    @Test
    void commonProductionSourcesUseLoaderNeutralTerminology()
            throws IOException {
        Path sourceRoot = commonSourceRoot();
        List<String> violations = new ArrayList<>();
        Pattern optionalModVocabulary = Pattern.compile(
                "\\b(sable|sublevel|sub-level|create aeronautics|starminer)\\b"
        );

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path path : files
                    .filter(file ->
                            file.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path)
                        .toLowerCase(Locale.ROOT);
                if (optionalModVocabulary.matcher(source).find()) {
                    violations.add(path.toString());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                "common production sources must use neutral terminology:\n"
                        + String.join("\n", violations)
        );
    }

    /**
     * The common module is the Java 17 compatibility baseline. A release-17
     * compile emits class-file major version 61; a later JDK class file must
     * never be published as the shared kernel.
     */
    @Test
    void commonProductionClassFilesTargetJava17()
            throws IOException {
        String resource = "cc/sighs/gravityengine/gravity/collision/"
                + "CollisionScene.class";
        byte[] classFile;
        try (InputStream input = ArchitectureBoundaryTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "cannot read common production class " + resource
                );
            }
            classFile = input.readAllBytes();
        }

        assertTrue(
                classFile.length >= 8
                        && (classFile[0] & 0xFF) == 0xCA
                        && (classFile[1] & 0xFF) == 0xFE
                        && (classFile[2] & 0xFF) == 0xBA
                        && (classFile[3] & 0xFF) == 0xBE,
                "common production class must be a JVM class file"
        );

        int majorVersion = ((classFile[6] & 0xFF) << 8)
                | (classFile[7] & 0xFF);
        assertTrue(
                majorVersion <= 61,
                "common production class major version must be <= 61 "
                        + "(Java 17), was " + majorVersion
        );
    }

    private static void inspectApiType(
            Class<?> owner,
            List<String> violations
    ) {
        checkType(
                owner,
                "superclass",
                owner.getGenericSuperclass(),
                violations
        );

        for (Type type :
                owner.getGenericInterfaces()) {
            checkType(
                    owner,
                    "interface",
                    type,
                    violations
            );
        }

        for (Field field :
                owner.getDeclaredFields()) {
            if (!isApiMember(
                    field.getModifiers()
            )) {
                continue;
            }

            checkType(
                    owner,
                    "field "
                            + field.getName(),
                    field.getGenericType(),
                    violations
            );
        }

        for (Constructor<?> constructor :
                owner.getDeclaredConstructors()) {
            if (!isApiMember(
                    constructor.getModifiers()
            )) {
                continue;
            }

            for (Type parameter :
                    constructor
                            .getGenericParameterTypes()) {
                checkType(
                        owner,
                        "constructor parameter",
                        parameter,
                        violations
                );
            }

            for (Type thrown :
                    constructor
                            .getGenericExceptionTypes()) {
                checkType(
                        owner,
                        "constructor throws",
                        thrown,
                        violations
                );
            }
        }

        for (Method method :
                owner.getDeclaredMethods()) {
            if (!isApiMember(
                    method.getModifiers()
            )) {
                continue;
            }

            checkType(
                    owner,
                    "method "
                            + method.getName()
                            + " return",
                    method.getGenericReturnType(),
                    violations
            );

            for (Type parameter :
                    method
                            .getGenericParameterTypes()) {
                checkType(
                        owner,
                        "method "
                                + method.getName()
                                + " parameter",
                        parameter,
                        violations
                );
            }

            for (Type thrown :
                    method
                            .getGenericExceptionTypes()) {
                checkType(
                        owner,
                        "method "
                                + method.getName()
                                + " throws",
                        thrown,
                        violations
                );
            }
        }
    }

    private static boolean isApiMember(
            int modifiers
    ) {
        return Modifier.isPublic(modifiers)
                || Modifier.isProtected(modifiers);
    }

    private static void checkType(
            Class<?> owner,
            String location,
            Type type,
            List<String> violations
    ) {
        if (type == null) {
            return;
        }

        if (referencesInternalType(type)) {
            violations.add(
                    owner.getName()
                            + " "
                            + location
                            + ": "
                            + type.getTypeName()
            );
        }
    }

    private static boolean referencesInternalType(
            Type type
    ) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                return referencesInternalType(
                        clazz.getComponentType()
                );
            }

            String name = clazz.getName();
            return INTERNAL_API_PREFIXES.stream()
                    .anyMatch(name::startsWith);
        }

        if (type instanceof ParameterizedType
                parameterized) {
            if (referencesInternalType(
                    parameterized.getRawType()
            )) {
                return true;
            }

            for (Type argument :
                    parameterized
                            .getActualTypeArguments()) {
                if (referencesInternalType(
                        argument
                )) {
                    return true;
                }
            }

            return false;
        }

        if (type instanceof GenericArrayType array) {
            return referencesInternalType(
                    array.getGenericComponentType()
            );
        }

        if (type instanceof WildcardType wildcard) {
            for (Type bound :
                    wildcard.getUpperBounds()) {
                if (referencesInternalType(bound)) {
                    return true;
                }
            }

            for (Type bound :
                    wildcard.getLowerBounds()) {
                if (referencesInternalType(bound)) {
                    return true;
                }
            }

            return false;
        }

        if (type instanceof TypeVariable<?> variable) {
            for (Type bound :
                    variable.getBounds()) {
                if (referencesInternalType(bound)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void collectViolations(
            Path path,
            List<String> violations
    ) {
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                    if (trimmed.startsWith("import " + prefix)) {
                        violations.add(path + ": " + line.trim());
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path commonSourceRoot() {
        Path direct = Path.of("src", "main", "java");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromRoot = Path.of("common", "src", "main", "java");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        throw new IllegalStateException("cannot locate common/src/main/java");
    }

    private static Path targetSourceRoot() {
        Path direct = Path.of(
                "targets",
                "neoforge-1.21.1",
                "src",
                "main",
                "java"
        );
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromCommon = Path.of(
                "..",
                "targets",
                "neoforge-1.21.1",
                "src",
                "main",
                "java"
        );
        if (Files.isDirectory(fromCommon)) {
            return fromCommon;
        }
        throw new IllegalStateException(
                "cannot locate targets/neoforge-1.21.1/src/main/java"
        );
    }
}
