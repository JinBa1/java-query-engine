package com.github.jinba1.blazedb;

/** An integer attribute value. */
public record IntValue(int v) implements Value {

    @Override
    public int compareTo(Value other) {
        if (other instanceof IntValue o) {
            return Integer.compare(v, o.v);
        }
        throw new QueryExecutionException(ErrorCode.TYPE_MISMATCH,
                "Type mismatch: cannot compare int value '" + v + "' with "
                        + other.typeName() + " value '" + other + "'");
    }

    @Override
    public String typeName() {
        return "int";
    }

    @Override
    public String toString() {
        return String.valueOf(v);
    }
}
