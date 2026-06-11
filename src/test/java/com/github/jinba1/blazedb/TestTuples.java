package com.github.jinba1.blazedb;

import java.util.ArrayList;
import java.util.List;

/** Test helper: build typed value lists tersely. */
public final class TestTuples {
    private TestTuples() {}

    public static List<Value> ints(int... vs) {
        List<Value> out = new ArrayList<>();
        for (int v : vs) out.add(new IntValue(v));
        return out;
    }

    public static List<Value> of(Value... vs) {
        return new ArrayList<>(List.of(vs));
    }
}
