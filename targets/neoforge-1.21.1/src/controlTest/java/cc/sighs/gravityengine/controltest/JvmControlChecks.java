package cc.sighs.gravityengine.controltest;

/** Fast executable controls. World, packet-Mixin and tick checks remain in the server suite. */
public final class JvmControlChecks {
    private JvmControlChecks() {}

    public static void main(String[] args) {
        ApiBoundaryChecks.runJvm();
        GeometryAuthorityChecks.runJvm();
        MoveInteropScopeChecks.run();
        System.out.println("JVM_CONTROL_CHECKS_PASSED");
    }
}
