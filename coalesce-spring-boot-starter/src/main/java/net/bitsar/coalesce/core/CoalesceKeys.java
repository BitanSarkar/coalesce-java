package net.bitsar.coalesce.core;

/**
 * Namespacing rules for the Redis keys behind one coalesced call.
 *
 * <p>Lives in {@code core} rather than on the Redisson coordinator so that key derivation
 * stays independent of any one {@link net.bitsar.coalesce.coordinator.CoalesceCoordinator}
 * implementation — a coordinator backed by something other than Redisson still has to agree
 * on the exact same strings, or two pods on different implementations would not coalesce
 * with each other.
 */
public final class CoalesceKeys {

    /** Prefixed onto every key this library creates, so {@code coalesce:*} matches them all. */
    public static final String KEY_PREFIX = "coalesce:";

    private CoalesceKeys() {
    }

    /**
     * Turns {@code coalesce:{raw}} plus a role into {@code coalesce:<role>:{raw}}.
     *
     * <p>The role infix goes BEFORE the {@code {...}} hash tag on purpose: the lock, the
     * bucket and the topic need three distinct Redis keys (a bucket write to the lock's key
     * clobbers its hash and every later lock call fails with WRONGTYPE), while still hashing
     * to one Redis Cluster slot so no operation is cross-slot.
     *
     * @param key  a key from {@link net.bitsar.coalesce.aspect.CoalesceKeyResolver}
     * @param role {@code state}, {@code lock} or {@code notify}
     * @return the role-specific Redis key
     */
    public static String discriminate(String key, String role) {
        int tagStart = key.indexOf('{');
        if (tagStart < 0) {
            return key + ":" + role;
        }
        return key.substring(0, tagStart) + role + ":" + key.substring(tagStart);
    }
}
