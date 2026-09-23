package com.example.starter.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.config.RefreshTokenProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link TokenDeriver} 与 {@link RequestFingerprinter} 的纯单元测试。
 */
class TokenDeriverTest {

    private final RefreshTokenProperties properties =
            new RefreshTokenProperties("test-synthetic-signing-key", 3600);
    private final TokenHasher hasher = new TokenHasher();
    private final TokenDeriver deriver = new TokenDeriver(properties);
    private final RequestFingerprinter fingerprinter = new RequestFingerprinter(hasher);

    @Test
    void rootTokenIsRandomUrlSafeAndUnique() {
        String a = deriver.generateRootToken();
        String b = deriver.generateRootToken();
        assertThat(a).isNotEqualTo(b);
        // 32 字节编码为 43 个无填充 Base64URL 字符
        assertThat(a).hasSize(43);
        assertThat(a).matches("[A-Za-z0-9_-]+");
        Base64.getUrlDecoder().decode(a);
    }

    @Test
    void childTokenIsDeterministicForSameContext() {
        String first = deriver.deriveChild("family-1", "hash-parent", "req-1", "client-1");
        String second = deriver.deriveChild("family-1", "hash-parent", "req-1", "client-1");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void childTokenChangesWhenAnyContextPartChanges() {
        String base = deriver.deriveChild("family-1", "hash-parent", "req-1", "client-1");
        assertThat(deriver.deriveChild("family-2", "hash-parent", "req-1", "client-1"))
                .isNotEqualTo(base);
        assertThat(deriver.deriveChild("family-1", "hash-other", "req-1", "client-1"))
                .isNotEqualTo(base);
        assertThat(deriver.deriveChild("family-1", "hash-parent", "req-2", "client-1"))
                .isNotEqualTo(base);
        assertThat(deriver.deriveChild("family-1", "hash-parent", "req-1", "client-2"))
                .isNotEqualTo(base);
    }

    @Test
    void fingerprintIsSha256OfCanonicalString() {
        String expected = hasher.sha256("family-1|req-1|client-1|hash-parent");
        assertThat(fingerprinter.fingerprint("family-1", "req-1", "client-1", "hash-parent"))
                .isEqualTo(expected)
                .hasSize(64);
    }

    @Test
    void fingerprintChangesWhenAnyPartChanges() {
        String base = fingerprinter.fingerprint("family-1", "req-1", "client-1", "hash-parent");
        assertThat(fingerprinter.fingerprint("family-1", "req-2", "client-1", "hash-parent"))
                .isNotEqualTo(base);
        assertThat(fingerprinter.fingerprint("family-1", "req-1", "client-2", "hash-parent"))
                .isNotEqualTo(base);
        assertThat(fingerprinter.fingerprint("family-1", "req-1", "client-1", "hash-other"))
                .isNotEqualTo(base);
    }
}
