package com.github.jinba1.cuckoodb;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TupleTest {

    @Test
    public void tupleHoldsTypedValues() {
        Tuple t = new Tuple(List.of(new IntValue(1), new StringValue("alice")));
        assertEquals(new IntValue(1), t.getAttribute(0));
        assertEquals(new StringValue("alice"), t.getAttribute(1));
    }

    @Test
    public void toStringJoinsWithCommaSpace() {
        Tuple t = new Tuple(List.of(new IntValue(1), new StringValue("x")));
        assertEquals("1, x", t.toString());
    }

    @Test
    public void mixedTypeTuplesNotEqual() {
        Tuple a = new Tuple(List.of(new IntValue(7)));
        Tuple b = new Tuple(List.of(new StringValue("7")));
        assertNotEquals(a, b);
    }
}
