package com.example.starter.wb.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重心包线判定：顶点、直边、斜边与边界容差。
 */
class EnvelopeCheckerTest {

    private static final List<EnvelopeChecker.EnvelopeVertex> POLYGON = List.of(
            new EnvelopeChecker.EnvelopeVertex(new BigDecimal("40000"), new BigDecimal("14")),
            new EnvelopeChecker.EnvelopeVertex(new BigDecimal("55000"), new BigDecimal("14")),
            new EnvelopeChecker.EnvelopeVertex(new BigDecimal("65000"), new BigDecimal("16")),
            new EnvelopeChecker.EnvelopeVertex(new BigDecimal("65000"), new BigDecimal("30")),
            new EnvelopeChecker.EnvelopeVertex(new BigDecimal("55000"), new BigDecimal("32")),
            new EnvelopeChecker.EnvelopeVertex(new BigDecimal("40000"), new BigDecimal("32")));

    @Test
    void vertexAndStraightEdgesAreOnBoundaryAndLegal() {
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("55000"), new BigDecimal("14.0000"))).isTrue();
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("40000"), new BigDecimal("32.0000"))).isTrue();
        // 垂直边上
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("40000"), new BigDecimal("20"))).isTrue();
        // 水平下边界直边
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("48000"), new BigDecimal("14"))).isTrue();
    }

    @Test
    void slopedEdgeBoundaryIsLegal() {
        // (55000,14)→(65000,16) 斜边：60000kg 时下边界恰为 15%MAC
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("60000"), new BigDecimal("15.0000"))).isTrue();
    }

    @Test
    void interiorPointIsLegal() {
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("53200"), new BigDecimal("20"))).isTrue();
    }

    @Test
    void justOutsideSlopedEdgeIsIllegal() {
        // 60000kg 时下边界 15，14.99 出界
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("60000"), new BigDecimal("14.9900"))).isFalse();
    }

    @Test
    void outsideWeightRangeIsIllegal() {
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("70000"), new BigDecimal("20"))).isFalse();
        assertThat(EnvelopeChecker.contains(POLYGON, new BigDecimal("30000"), new BigDecimal("20"))).isFalse();
    }
}
