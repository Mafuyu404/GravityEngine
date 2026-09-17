package cc.sighs.gravityengine.network;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/** Bounded evidence of server-published bodies, never another live body owner.
 * Time is server-local expiry bookkeeping, not a client/server clock mapping. */
public final class MovementBodyHistory<T> {
    private record Entry<T>(T body, long supersededAt) {}
    private final LinkedHashMap<Long, Entry<T>> entries = new LinkedHashMap<>();
    private final int capacity;
    private final long graceTicks;
    private long latest = -1;
    private long acknowledged = -1;

    public MovementBodyHistory(int capacity, long graceTicks) {
        if (capacity < 2 || graceTicks < 1) throw new IllegalArgumentException("invalid history bounds");
        this.capacity = capacity;
        this.graceTicks = graceTicks;
    }

    public void publish(long epoch, T body, long now) {
        Objects.requireNonNull(body, "body");
        if (epoch < 0 || epoch < latest) throw new IllegalArgumentException("body epoch regressed");
        if (epoch == latest) {
            if (!entries.get(epoch).body().equals(body))
                throw new IllegalStateException("conflicting body at epoch " + epoch);
            return;
        }
        if (latest >= 0) {
            var previous = entries.get(latest);
            entries.put(latest, new Entry<>(previous.body(), now));
        }
        latest = epoch;
        entries.put(epoch, new Entry<>(body, Long.MAX_VALUE));
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
        entries.entrySet().removeIf(e -> e.getKey() != latest && now - e.getValue().supersededAt() > graceTicks);
    }

    /** Ordered movement acknowledges use of this epoch. Older versions then retire.
     * Repeating a current epoch is ordinary movement, not a duplicate packet. */
    public Optional<T> accept(long epoch, long now) {
        var entry = entries.get(epoch);
        if (entry == null || epoch < acknowledged
                || epoch != latest && now - entry.supersededAt() > graceTicks) return Optional.empty();
        acknowledged = epoch;
        entries.entrySet().removeIf(e -> e.getKey() < acknowledged);
        return Optional.of(entry.body());
    }

    public int size() { return entries.size(); }
}
