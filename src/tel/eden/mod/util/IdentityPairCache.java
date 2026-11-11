package tel.eden.mod.util;

import java.util.Arrays;

/**
 * A bounded cache keyed by the <em>identity</em> of two objects, for per-frame lookups whose
 * keys are large immutable trees (chat components, item lore).
 *
 * <p>Hashing or comparing such a tree by value walks every node on every lookup. An
 * unchanged item stack hands out the same component instances every frame, so reference
 * identity answers "same as last frame" in constant time. A miss only means "not seen by
 * reference", so callers must fall back to a value-keyed lookup before doing real work.
 *
 * <p>A lookup creates no objects: the keys live in parallel arrays and are probed directly. This runs
 * for every item every frame, and a temporary key object per lookup depended on the JIT to remove it,
 * which it did not always do.
 *
 * <p>Not thread safe. Strong references are held, which is bounded by {@code limit}; the
 * cache is cleared when full rather than evicting, which is cheap and keeps the hot path
 * free of bookkeeping. Neither key may be null.
 */
public final class IdentityPairCache<A, B, V> {
	private final int limit;
	private final int mask;
	private final Object[] firsts;
	private final Object[] seconds;
	private final Object[] values;
	private int size;

	public IdentityPairCache(int limit) {
		this.limit = limit;
		// At most half full, so probing stays short.
		int capacity = Integer.highestOneBit(Math.max(2, limit) * 2 - 1) << 1;
		this.mask = capacity - 1;
		this.firsts = new Object[capacity];
		this.seconds = new Object[capacity];
		this.values = new Object[capacity];
	}

	@SuppressWarnings("unchecked")
	public V get(A first, B second) {
		for (int slot = slotFor(first, second); firsts[slot] != null; slot = (slot + 1) & mask) {
			if (firsts[slot] == first && seconds[slot] == second) {
				return (V) values[slot];
			}
		}
		return null;
	}

	public void put(A first, B second, V value) {
		if (first == null || second == null) {
			throw new NullPointerException("IdentityPairCache keys must not be null");
		}
		if (size >= limit) {
			Arrays.fill(firsts, null);
			Arrays.fill(seconds, null);
			Arrays.fill(values, null);
			size = 0;
		}
		int slot = slotFor(first, second);
		while (firsts[slot] != null) {
			if (firsts[slot] == first && seconds[slot] == second) {
				values[slot] = value;
				return;
			}
			slot = (slot + 1) & mask;
		}
		firsts[slot] = first;
		seconds[slot] = second;
		values[slot] = value;
		size++;
	}

	public int size() {
		return size;
	}

	private int slotFor(Object first, Object second) {
		int hash = 31 * System.identityHashCode(first) + System.identityHashCode(second);
		// Spread the bits so that identity hash codes, which are not uniform, do not bunch up in the low ones.
		return (hash ^ (hash >>> 16)) & mask;
	}
}
