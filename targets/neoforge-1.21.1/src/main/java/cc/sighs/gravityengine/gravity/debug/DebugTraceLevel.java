package cc.sighs.gravityengine.gravity.debug;

/** Internal domain levels. Legacy boolean properties select OFF or FULL. */
public enum DebugTraceLevel {
    OFF, ANOMALY, MUTATION, FULL;

    public boolean enabled() { return this != OFF; }
}
