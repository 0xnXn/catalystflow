package com.catalystflow.e2e.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that initialises and tears down TestFixtures exactly once
 * for the entire test run, regardless of how many test classes use it.
 *
 * Usage — annotate every E2E test class with:
 *   @ExtendWith(E2EExtension.class)
 *
 * How it works:
 *   - The first test class to call beforeAll() stores the fixtures in the root
 *     ExtensionContext store.
 *   - The CloseableResource wrapper ensures teardown() runs after all tests
 *     in the JVM have finished (JUnit 5 calls close() on root-scoped resources).
 *   - Subsequent test classes call beforeAll() again but the getOrComputeIfAbsent
 *     guard skips re-initialisation.
 */
public class E2EExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(E2EExtension.class);

    private static final String KEY = "fixtures";

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType().equals(ExtensionContext.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // Root store is shared across the entire test run; guarded by getOrComputeIfAbsent
        context.getRoot()
               .getStore(NS)
               .getOrComputeIfAbsent(KEY, k -> {
                   TestFixtures fixtures = new TestFixtures();
                   try {
                       fixtures.setup();
                   } catch (RuntimeException failure) {
                       try { fixtures.teardown(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                       throw failure;
                   }
                   return new FixturesResource(fixtures);
               }, FixturesResource.class);
    }

    /** Returns the shared TestFixtures instance from the store. */
    public static TestFixtures fixtures(ExtensionContext context) {
        return context.getRoot()
                      .getStore(NS)
                      .get(KEY, FixturesResource.class)
                      .fixtures();
    }

    // ── Wrapper that drives teardown when JUnit closes the root store ─────────

    record FixturesResource(TestFixtures fixtures)
            implements ExtensionContext.Store.CloseableResource {

        @Override
        public void close() {
            fixtures.teardown();
        }
    }
}
