package tel.eden.mod.util;

import java.util.HashMap;
import java.util.Map;

/**
 * A bounded cache keyed by the <em>identity</em> of two objects, for per-frame lookups whose
 * keys are large immutable trees (chat components, item lore).
 *
 * <p>Hashing or comparing such a tree by value walks every node on every lookup. An
 * unchanged item stack hands out the same component instances every frame, so reference
 * identity answers "same as last frame" in constant time. A miss only means "not seen by
 * reference", so callers must fall back to a value-keyed lookup before doing real work.
 *
 * <p>Not thread safe. Strong references are held, which is bounded by {@code limit}; the
 * cache is cleared when full rather than evicting, which is cheap and keeps the hot path
 * free of bookkeeping.
 */
public final class IdentityPairCache<A, B, V> {
	private final int limit;
	private final Map<Key<A, B>, V> entries = new HashMap<>();

	public IdentityPairCache(int limit) {
		this.limit = limit;
	}

	public V get(A first, B second) {
		return entries.get(new Key<>(first, second));
	}

	public void put(A first, B second, V value) {
		if (entries.size() >= limit) {
			entries.clear();
		}
		entries.put(new Key<>(first, second), value);
	}

	public int size() {
		return entries.size();
	}

	private static final class Key<A, B> {
		private final A first;
		private final B second;
		private final int hash;

		private Key(A first, B second) {
			this.first = first;
			this.second = second;
			this.hash = 31 * System.identityHashCode(first) + System.identityHashCode(second);
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key<?, ?> key && key.first == first && key.second == second;
		}
	}
}
