package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构化 TLV 规范编码单测：长度前缀保证合法文本含分隔符也无歧义，
 * 不同类型/不同集合切分不会碰撞，集合顺序敏感。
 */
class CanonicalTest {

    @Test
    @DisplayName("含冒号与分隔符文本不产生歧义：拼接边界由长度决定")
    void delimiterInsideTextIsUnambiguous() {
        String a = Canonical.list(List.of(Canonical.str("a:b"), Canonical.str("c")));
        String b = Canonical.list(List.of(Canonical.str("a"), Canonical.str("b:c")));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("文本、整数、布尔、时刻、日期类型标记互斥：字符串 \"1\" 不等于整数 1")
    void typeTagsPreventCrossTypeCollision() {
        assertThat(Canonical.str("1")).isNotEqualTo(Canonical.integer(1));
        assertThat(Canonical.str("true")).isNotEqualTo(Canonical.bool(true));
        assertThat(Canonical.integer(1))
                .isNotEqualTo(Canonical.instant(java.time.Instant.ofEpochMilli(1)));
    }

    @Test
    @DisplayName("对象字段以键值对计数包裹：字段数或键文本不同则规范串不同")
    void objectEncodingDistinguishesShape() {
        String two = Canonical.obj("a", Canonical.str("x"), "b", Canonical.str("y"));
        String one = Canonical.obj("ab", Canonical.str("xy"));
        assertThat(two).isNotEqualTo(one);
    }

    @Test
    @DisplayName("集合顺序敏感：不同排列规范串不同（业务侧须先确定性排序）")
    void listOrderIsSignificant() {
        String forward = Canonical.list(List.of(Canonical.str("a"), Canonical.str("b")));
        String reverse = Canonical.list(List.of(Canonical.str("b"), Canonical.str("a")));
        assertThat(forward).isNotEqualTo(reverse);
    }

    @Test
    @DisplayName("同构输入指纹确定且为 SHA-256 十六进制")
    void fingerprintStable() {
        String c1 = Canonical.obj("k", Canonical.str("v"));
        String c2 = Canonical.obj("k", Canonical.str("v"));
        assertThat(Canonical.sha256(c1)).isEqualTo(Canonical.sha256(c2));
        assertThat(Canonical.sha256(c1)).hasSize(64).matches("[0-9a-f]{64}");
    }
}
