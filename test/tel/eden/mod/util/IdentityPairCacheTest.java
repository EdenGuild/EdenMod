package tel.eden.mod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class IdentityPairCacheTest {
	/** Stands in for a component tree: value-equal copies, and a hash that must not be used. */
	private static final class Tree {
		final String value;

		Tree(String value) {
			this.value = value;
		}

		@Override
		public boolean equals(Object other) {
			throw new AssertionError("identity lookup must not compare by value");
		}

		@Override
		public int hashCode() {
			throw new AssertionError("identity lookup must not hash by value");
		}
	}

	@Test
	void hitsBySameInstancesWithoutHashingOrComparingByValue() {
		IdentityPairCache<Tree, Tree, String> cache = new IdentityPairCache<>(8);
		Tree name = new Tree("a");
		Tree lore = new Tree("b");
		cache.put(name, lore, "decision");

		assertSame("decision", cache.get(name, lore));
	}

	@Test
	void valueEqualButDistinctInstancesMiss() {
		IdentityPairCache<String, String, String> cache = new IdentityPairCache<>(8);
		String name = new String("a");
		String lore = new String("b");
		cache.put(name, lore, "decision");

		assertNull(cache.get(new String("a"), new String("b")));
		assertNull(cache.get(name, new String("b")));
	}

	@Test
	void staysBoundedByClearingWhenFull() {
		IdentityPairCache<Object, Object, Integer> cache = new IdentityPairCache<>(4);
		Object lore = new Object();
		Object first = new Object();
		cache.put(first, lore, 0);
		for (int i = 1; i < 4; i++) {
			cache.put(new Object(), lore, i);
		}
		assertEquals(4, cache.size());

		Object overflow = new Object();
		cache.put(overflow, lore, 99);

		assertEquals(1, cache.size());
		assertNull(cache.get(first, lore));
		assertEquals(99, cache.get(overflow, lore));
	}

	@Test
	void overwritingTheSamePairReplacesItsValueWithoutGrowing() {
		IdentityPairCache<Object, Object, Integer> cache = new IdentityPairCache<>(8);
		Object name = new Object();
		Object lore = new Object();
		cache.put(name, lore, 1);
		cache.put(name, lore, 2);

		assertEquals(2, cache.get(name, lore));
		assertEquals(1, cache.size());
	}

	@Test
	void manyPairsSharingOneFirstKeyAreAllFound() {
		// Probing must keep distinct pairs apart even when one half of the key repeats and the table is busy.
		IdentityPairCache<Object, Object, Integer> cache = new IdentityPairCache<>(512);
		Object shared = new Object();
		Object[] seconds = new Object[400];
		for (int i = 0; i < seconds.length; i++) {
			seconds[i] = new Object();
			cache.put(shared, seconds[i], i);
		}
		for (int i = 0; i < seconds.length; i++) {
			assertEquals(i, cache.get(shared, seconds[i]));
		}
		assertNull(cache.get(shared, new Object()));
	}
}
