package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.model.ParamType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamTypeTest {

    @Test
    void coercesScalarsToCanonicalTypes() {
        assertThat(ParamType.INTEGER.coerce(42)).isEqualTo(42L);
        assertThat(ParamType.INTEGER.coerce("7")).isEqualTo(7L);
        assertThat(ParamType.DOUBLE.coerce(1)).isEqualTo(1.0);
        assertThat(ParamType.DOUBLE.coerce("1.25")).isEqualTo(1.25);
        assertThat(ParamType.BOOLEAN.coerce("true")).isEqualTo(true);
        assertThat(ParamType.STRING.coerce(123)).isEqualTo("123");
    }

    @Test
    void rejectsIncompatibleValuesAndFractionsForInteger() {
        assertThatThrownBy(() -> ParamType.INTEGER.coerce(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamType.BOOLEAN.coerce("yes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamType.DOUBLE.coerce(new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsExplicitNullAsNull() {
        assertThat(ParamType.STRING.coerce(null)).isNull();
    }

    @Test
    void matchesCanonicalBoxedTypes() {
        assertThat(ParamType.INTEGER.matches(1L)).isTrue();
        assertThat(ParamType.INTEGER.matches(1)).isFalse();
        assertThat(ParamType.DOUBLE.matches(1.0)).isTrue();
        assertThat(ParamType.BOOLEAN.matches(true)).isTrue();
        assertThat(ParamType.STRING.matches("x")).isTrue();
    }
}
