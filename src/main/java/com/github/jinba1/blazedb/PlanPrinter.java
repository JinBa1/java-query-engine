package com.github.jinba1.blazedb;

import com.github.jinba1.blazedb.operator.JoinOperator;
import com.github.jinba1.blazedb.operator.Operator;

/**
 * Renders an operator tree as an indented, human-readable plan: one operator per line
 * (via {@link Operator#describe()}), two spaces per depth, a join's outer child first.
 * Used by EXPLAIN.
 */
public final class PlanPrinter {

    private PlanPrinter() {
    }

    /** Returns the indented plan tree, terminated by a newline. */
    public static String print(Operator root) {
        StringBuilder sb = new StringBuilder();
        append(root, 0, sb);
        return sb.toString();
    }

    private static void append(Operator op, int depth, StringBuilder sb) {
        if (op == null) {
            return;
        }
        sb.append("  ".repeat(depth)).append(op.describe()).append('\n');
        if (op instanceof JoinOperator join) {
            append(join.getOuterChild(), depth + 1, sb);
            append(join.getChild(), depth + 1, sb);
        } else if (op.hasChild()) {
            append(op.getChild(), depth + 1, sb);
        }
    }
}
