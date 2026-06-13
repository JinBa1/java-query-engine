package com.github.jinba1.cuckoodb;

/**
 * A typed attribute value inside a Tuple. Sealed so every consumer
 * handles all types exhaustively (compiler-enforced).
 */
public sealed interface Value extends Comparable<Value> permits IntValue, StringValue {

    /** Human-readable type name used in error messages ("int", "string"). */
    String typeName();
}
