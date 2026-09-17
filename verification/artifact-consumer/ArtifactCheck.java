import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.math.Vec3d;

/** Loads the distributed API and checks every public generic signature without Sable. */
public final class ArtifactCheck {
    private static void signature(Type type) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) { signature(c.componentType()); return; }
            String name = c.getName();
            if (name.startsWith("cc.sighs.gravityengine.") && !name.startsWith("cc.sighs.gravityengine.api."))
                throw new AssertionError("Internal API signature: " + name);
            if (name.startsWith("org.joml.") || name.startsWith("dev.ryanhcode."))
                throw new AssertionError("Optional/mutable API signature: " + name);
        } else if (type instanceof ParameterizedType p) {
            signature(p.getRawType());
            for (Type t : p.getActualTypeArguments()) signature(t);
        } else if (type instanceof GenericArrayType a) signature(a.getGenericComponentType());
        else if (type instanceof WildcardType w) {
            for (Type t : w.getUpperBounds()) signature(t);
            for (Type t : w.getLowerBounds()) signature(t);
        }
        // Type-variable bounds are checked on declarations below (avoid recursive E extends Enum<E>).
    }
    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]).toRealPath();
        Path loaded = Path.of(GravityEngineApi.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!artifact.equals(loaded)) throw new AssertionError("API loaded from sources: " + loaded);
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            if (entry.toLowerCase(Locale.ROOT).contains("sable")) throw new AssertionError("Optional dependency on artifact classpath: " + entry);
        int count = 0;
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (String required : List.of("LICENSE", "META-INF/neoforge.mods.toml", "gravityengine.mixins.json",
                    "cc/sighs/gravityengine/api/math/Vec3d.class")) {
                if (jar.getEntry(required) == null) throw new AssertionError("Missing artifact entry: " + required);
            }
            String metadata = new String(jar.getInputStream(jar.getEntry("META-INF/neoforge.mods.toml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (metadata.contains("${") || !metadata.contains("gravityengine") || !metadata.contains("optional"))
                throw new AssertionError("Invalid expanded metadata");
            for (var entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (!name.startsWith("cc/sighs/gravityengine/api/") || !name.endsWith(".class") || name.endsWith("package-info.class")) continue;
                Class<?> c = Class.forName(name.substring(0, name.length()-6).replace('/', '.'), false, ArtifactCheck.class.getClassLoader());
                if (!Modifier.isPublic(c.getModifiers())) continue;
                count++;
                if (!Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(artifact))
                    throw new AssertionError("Public type loaded outside artifact: " + c);
                if (c.getGenericSuperclass() != null) signature(c.getGenericSuperclass());
                for (Type t : c.getGenericInterfaces()) signature(t);
                for (var v : c.getTypeParameters()) for (Type t : v.getBounds()) signature(t);
                for (Method m : c.getMethods()) {
                    signature(m.getGenericReturnType());
                    for (Type t : m.getGenericParameterTypes()) signature(t);
                    for (Type t : m.getGenericExceptionTypes()) signature(t);
                    for (var v : m.getTypeParameters()) for (Type t : v.getBounds()) signature(t);
                }
                for (var ctor : c.getConstructors()) {
                    for (Type t : ctor.getGenericParameterTypes()) signature(t);
                    for (Type t : ctor.getGenericExceptionTypes()) signature(t);
                }
                for (Field f : c.getFields()) signature(f.getGenericType());
            }
        }
        var value = new EntityGravitySnapshot(GravityAuthority.FIELD, new Vec3d(0,-1,0), .08,
                new Vec3d(0,-1,0), .08, FieldPresence.UNKNOWN, 0, Optional.empty(),
                new Vec3d(0,-1,0), .08, GravityObservationSource.ASSIGNMENT_FALLBACK);
        if (!com.example.examplemod.gravity.ExternalConsumerFixture.copySnapshot(value).equals(value))
            throw new AssertionError("Independent consumer snapshot copy");
        System.out.println("API_ARTIFACT_CHECKS_PASSED publicTypes=" + count + " jar=" + artifact);
    }
}
