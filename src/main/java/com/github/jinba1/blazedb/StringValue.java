package com.github.jinba1.blazedb;

/** A string attribute value. CSV quoting is the writer's job, not this class's. */
public record StringValue(String v) implements Value {

    @Override
    public int compareTo(Value other) {
        if (other instanceof StringValue o) {
            return v.compareTo(o.v);
        }
        throw new QueryExecutionException(ErrorCode.TYPE_MISMATCH,
                "Type mismatch: cannot compare string value '" + v + "' with "
                        + other.typeName() + " value '" + other + "'");
    }

    @Override
    public String typeName() {
        return "string";
    }

    @Override
    public String toString() {
        return v;
    }
}
