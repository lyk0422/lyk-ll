package com.example.starter.forwarder.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetentionPolicyTest {

    @Test
    void parsesAllPolicyForms() {
        assertEquals(RetentionPolicy.Kind.DROP, RetentionPolicy.parse("drop").kind());
        assertEquals(RetentionPolicy.Kind.LATEST, RetentionPolicy.parse("LATEST").kind());
        assertEquals(RetentionPolicy.Kind.FIFO, RetentionPolicy.parse("fifo").kind());
        RetentionPolicy bounded = RetentionPolicy.parse("BOUNDED(5)");
        assertEquals(RetentionPolicy.Kind.BOUNDED, bounded.kind());
        assertEquals(5, bounded.bound());
    }

    @Test
    void rejectsInvalidBoundedAndUnknownPolicy() {
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.parse("BOUNDED(0)"));
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.parse("BOUNDED(x)"));
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.parse("UNKNOWN"));
    }
}
