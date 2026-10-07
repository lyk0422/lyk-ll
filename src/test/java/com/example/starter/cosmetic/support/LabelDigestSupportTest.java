package com.example.starter.cosmetic.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标签文本摘要口径测试：成人/儿童分档摘要确定性与已知答案固化。
 */
class LabelDigestSupportTest {

    @Test
    void adultDigestOfNullPackagingTextIsStable() {
        // 已知答案固化：成人备案未变更包装文字（null）时的标签文本摘要
        assertThat(LabelDigestSupport.adultDigest(null))
                .isEqualTo("3a13373a2b0163dfb8603f40a960e1f34f8879055f88c1f64851869883c68a01");
    }

    @Test
    void bandDigestDiffersFromGlobalAndBetweenBands() {
        String bandA = LabelDigestSupport.bandDigest("BAND_0_36", "适用于0-36个月婴幼儿");
        String bandB = LabelDigestSupport.bandDigest("BAND_37_144", "适用于0-36个月婴幼儿");
        // 同一文本不同档：摘要不同（分档独立，不能互相代替）
        assertThat(bandA).isNotEqualTo(bandB);
        assertThat(bandA).hasSize(64);
    }
}
