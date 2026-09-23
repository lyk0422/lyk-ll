package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 体积解析与格式化的精度边界单测（不依赖 Spring/数据库）。
 */
class VolumeTest {

    @Test
    void parsesUpToThreeDecimalMicrolitersAsNanoliters() {
        assertThat(Volume.parsePositiveNl("10.123", "v")).isEqualTo(10_123L);
        assertThat(Volume.parsePositiveNl("0.001", "v")).isEqualTo(1L);
        assertThat(Volume.parsePositiveNl("1", "v")).isEqualTo(1_000L);
        assertThat(Volume.parsePositiveNl(" 2.500 ", "v")).isEqualTo(2_500L);
    }

    @Test
    void rejectsMoreThanThreeDecimals() {
        assertThatThrownBy(() -> Volume.parsePositiveNl("1.0005", "v"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("三位小数");
    }

    @Test
    void rejectsNonPositiveOrMalformed() {
        assertThatThrownBy(() -> Volume.parsePositiveNl("0", "v"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Volume.parsePositiveNl("-1.2", "v"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Volume.parsePositiveNl("abc", "v"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Volume.parsePositiveNl("  ", "v"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void formatsNanolitersBackToCanonicalMicroliters() {
        assertThat(Volume.formatUl(10_123L)).isEqualTo("10.123");
        assertThat(Volume.formatUl(2_500L)).isEqualTo("2.5");
        assertThat(Volume.formatUl(1L)).isEqualTo("0.001");
        assertThat(Volume.formatUl(0L)).isEqualTo("0");
    }

    @Test
    void fingerprintChangesWhenAnyParameterChanges() {
        String first = Fingerprint.sha256("K", java.util.List.of("a", "1", "2"));
        String same = Fingerprint.sha256("K", java.util.List.of("a", "1", "2"));
        String changed = Fingerprint.sha256("K", java.util.List.of("a", "1", "3"));
        String otherKind = Fingerprint.sha256("J", java.util.List.of("a", "1", "2"));
        assertThat(first).isEqualTo(same);
        assertThat(first).isNotEqualTo(changed);
        assertThat(first).isNotEqualTo(otherKind);
    }
}
