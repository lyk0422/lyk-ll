package com.example.starter.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 令牌散列与确定性派生的纯单元测试。
 */
class TokenCryptoTest {

    @Test
    void sha256MatchesKnownVector() {
        // NIST 已知向量：SHA-256("abc")
        assertThat(TokenCrypto.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void rootTokenIsRandomAndNeverPersistedFormat() {
        String t1 = TokenCrypto.newRootToken();
        String t2 = TokenCrypto.newRootToken();
        assertThat(t1).startsWith("rt.").hasSize(3 + 64);
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void childDerivationIsDeterministicForSameContext() {
        String first = TokenCrypto.deriveChildToken(
                "key", "req-1", "family-1", "parent-hash", "client-1", 1);
        String second = TokenCrypto.deriveChildToken(
                "key", "req-1", "family-1", "parent-hash", "client-1", 1);
        assertThat(first).isEqualTo(second).startsWith("rt.");
        // 散列不能等于明文。
        assertThat(TokenCrypto.sha256(first)).isNotEqualTo(first);
    }

    @Test
    void childDerivationChangesWhenAnyContextDiffers() {
        String base = TokenCrypto.deriveChildToken(
                "key", "req-1", "family-1", "parent-hash", "client-1", 1);
        assertThat(TokenCrypto.deriveChildToken("key", "req-2", "family-1", "parent-hash", "client-1", 1))
                .isNotEqualTo(base);
        assertThat(TokenCrypto.deriveChildToken("key", "req-1", "family-2", "parent-hash", "client-1", 1))
                .isNotEqualTo(base);
        assertThat(TokenCrypto.deriveChildToken("key", "req-1", "family-1", "other-hash", "client-1", 1))
                .isNotEqualTo(base);
        assertThat(TokenCrypto.deriveChildToken("key", "req-1", "family-1", "parent-hash", "client-2", 1))
                .isNotEqualTo(base);
        assertThat(TokenCrypto.deriveChildToken("key", "req-1", "family-1", "parent-hash", "client-1", 2))
                .isNotEqualTo(base);
        assertThat(TokenCrypto.deriveChildToken("other-key", "req-1", "family-1", "parent-hash", "client-1", 1))
                .isNotEqualTo(base);
    }
}
