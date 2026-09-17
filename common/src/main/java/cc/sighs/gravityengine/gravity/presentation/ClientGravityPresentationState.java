package cc.sighs.gravityengine.gravity.presentation;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.ScalarMath;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;

/** Client-owned visual orientation timeline. No physical state or position history.
 * Writes belong to client tick/packet admission; render samples never advance it. */
public final class ClientGravityPresentationState {
    private GravityFrame previous;
    private GravityFrame current;
    private double startTime;
    private double duration = 1;

    public void retarget(GravityFrame target, double time, double interval) {
        java.util.Objects.requireNonNull(target, "target");
        if (!Double.isFinite(time) || !Double.isFinite(interval) || interval <= 0) {
            throw new IllegalArgumentException("invalid presentation interval");
        }
        if (current == null) {
            previous = current = target;
            startTime = time;
            return;
        }
        if (current.orientation().equals(target.orientation()) && current.strength() == target.strength()) return;
        // A packet may arrive between ticks. A subsequent tick observation must
        // not retarget from an earlier point on that packet's visual timeline.
        time = Math.max(time, startTime);
        previous = sampleAt(time);
        current = target;
        startTime = time;
        duration = interval;
    }

    public GravityFrame sampleAt(double time) {
        if (current == null) return GravityFrame.DEFAULT;
        double progress = ScalarMath.clamp((time - startTime) / duration, 0, 1);
        if (progress == 0) return previous;
        if (progress == 1 || previous == current) return current;
        var rotation = BodyOrientation3d.quaternion(previous.orientation())
                .slerp(BodyOrientation3d.quaternion(current.orientation()), progress).normalized();
        return new GravityFrame(current.samplePoint(), BodyOrientation3d.frame(rotation),
                previous.strength() + (current.strength() - previous.strength()) * progress);
    }

    public boolean transitioning(double time) { return current != null && time < startTime + duration && previous != current; }
    public GravityFrame previous() { return previous; }
    public GravityFrame current() { return current; }
}
