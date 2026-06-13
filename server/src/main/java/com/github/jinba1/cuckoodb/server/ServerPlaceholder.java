package com.github.jinba1.cuckoodb.server;

import com.github.jinba1.cuckoodb.QueryResult;

/**
 * Placeholder for the cuckooDB server module.
 *
 * <p>The Spring Boot REST gateway is added in a later change. This class exists
 * only so that the {@code cuckoodb-server} module compiles against an engine type,
 * proving the {@code server -> engine} Maven dependency is wired correctly. A
 * broken reactor dependency would fail compilation here rather than passing silently.
 */
public final class ServerPlaceholder {

    private ServerPlaceholder() {
    }

    /**
     * References an engine type ({@link QueryResult}) so the engine dependency is
     * exercised at compile time, not merely declared in the POM.
     *
     * @return a short description naming the linked engine type
     */
    public static String describe() {
        return "cuckooDB server skeleton; REST gateway added later. Engine type linked: "
                + QueryResult.class.getSimpleName();
    }
}
