package com.example.starter.ros;

import com.example.starter.ros.support.DependencyExpression;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖表达式求值器单元测试。
 */
class DependencyExpressionTest {

    @Test
    void comparesNumbersAndBooleans() {
        Map<String, Object> snap = Map.of("frame_rate", 60L, "enabled", Boolean.TRUE);
        assertTrue(DependencyExpression.compile("frame_rate > 0 && frame_rate <= 120").evaluate(snap));
        assertFalse(DependencyExpression.compile("frame_rate > 120").evaluate(snap));
        assertTrue(DependencyExpression.compile("enabled == true").evaluate(snap));
        assertTrue(DependencyExpression.compile("!false").evaluate(Map.of()));
    }

    @Test
    void supportsParenthesesAndOr() {
        Map<String, Object> snap = Map.of("a", 1L, "b", 2L);
        assertTrue(DependencyExpression.compile("(a == 1 || a == 2) && b == 2").evaluate(snap));
        assertFalse(DependencyExpression.compile("a == 2 || b == 3").evaluate(snap));
    }

    @Test
    void comparesStrings() {
        Map<String, Object> snap = Map.of("mode", "fast");
        assertTrue(DependencyExpression.compile("mode == 'fast'").evaluate(snap));
        assertFalse(DependencyExpression.compile("mode != 'fast'").evaluate(snap));
    }

    @Test
    void crossParameterDependency() {
        Map<String, Object> snap = Map.of("height", 480L, "width", 640L);
        assertTrue(DependencyExpression.compile("height <= width").evaluate(snap));
        assertFalse(DependencyExpression.compile("height > width").evaluate(snap));
    }

    @Test
    void absentReferenceFailsClosed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DependencyExpression.compile("missing == 1").evaluate(Map.of()));
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    void malformedExpressionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DependencyExpression.compile("a >").evaluate(Map.of("a", 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> DependencyExpression.compile("a == 1 extra").evaluate(Map.of("a", 1L)));
    }
}
