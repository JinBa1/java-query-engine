package com.github.jinba1.blazedb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QueryConfigTest {

    @Test
    public void defaultsEnableOptimizationAndHashJoin() {
        QueryConfig config = QueryConfig.defaults();
        assertTrue(config.useQueryOptimization());
        assertTrue(config.useHashJoin());
    }

    @Test
    public void customConfigCarriesFlags() {
        QueryConfig config = new QueryConfig(false, true);
        assertFalse(config.useQueryOptimization());
        assertTrue(config.useHashJoin());
    }
}
