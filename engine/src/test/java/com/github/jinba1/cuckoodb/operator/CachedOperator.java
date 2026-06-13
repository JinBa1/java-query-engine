package com.github.jinba1.cuckoodb.operator;

import com.github.jinba1.cuckoodb.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * Test/benchmark utility: drains a source operator once at construction and replays its
 * tuples from memory. Lets join benchmarks measure the algorithm without CSV-parsing noise.
 */
public class CachedOperator extends Operator {

    private final List<Tuple> tuples = new ArrayList<>();
    private int position = 0;

    public CachedOperator(Operator source) {
        super(source.getContext());
        source.ensureSchemaRegistered();
        this.intermediateSchemaId = source.propagateSchemaId();
        this.schemaRegistered = true;
        Tuple tuple;
        while ((tuple = source.getNextTuple()) != null) {
            tuples.add(tuple);
        }
    }

    @Override
    public Tuple getNextTuple() {
        if (position >= tuples.size()) {
            return null;
        }
        countTuple();
        return tuples.get(position++);
    }

    @Override
    public void reset() {
        position = 0;
    }

    @Override
    public String propagateSchemaId() {
        return intermediateSchemaId;
    }

    @Override
    protected void registerSchema() {
        // passthrough: the source operator owns the schema
    }

    @Override
    public String describe() {
        return "Cached[" + tuples.size() + " tuples]";
    }
}
