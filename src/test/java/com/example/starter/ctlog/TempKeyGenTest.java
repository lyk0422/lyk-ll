package com.example.starter.ctlog;

import com.example.starter.ctlog.config.CtLogProperties;
import com.example.starter.ctlog.crypto.DemoObserverKeys;
import com.example.starter.ctlog.crypto.JcaEd25519;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置合成演示密钥对与 Ed25519 验签实现的一致性测试。
 */
class TempKeyGenTest {

    @Test
    void demoKeyPairSignsAndVerifies() {
        JcaEd25519 ed25519 = new JcaEd25519(new CtLogProperties(""));
        assertEquals("DEFAULT", ed25519.source());
        assertEquals(DemoObserverKeys.PUBLIC_KEY_HEX, HexFormat.of().formatHex(ed25519.getPublicKeyBytes()));

        byte[] message = "CTLOG1\nobserver-1\n5\nabcdef\n1700000000000".getBytes();
        String signatureHex = TestSigning.signWithDemoKey(message);
        assertTrue(ed25519.verify(message, HexFormat.of().parseHex(signatureHex)));

        // 篡改消息或签名均判负。
        assertFalse(ed25519.verify("tampered".getBytes(), HexFormat.of().parseHex(signatureHex)));
        byte[] badSig = HexFormat.of().parseHex(signatureHex);
        badSig[0] ^= 0x01;
        assertFalse(ed25519.verify(message, badSig));
    }

    @Test
    void configuredPublicKeyIsUsed() {
        JcaEd25519 ed25519 = new JcaEd25519(new CtLogProperties(DemoObserverKeys.PUBLIC_KEY_HEX));
        assertEquals("CONFIGURED", ed25519.source());
        byte[] message = "hello".getBytes();
        assertTrue(ed25519.verify(message, HexFormat.of().parseHex(TestSigning.signWithDemoKey(message))));
    }
}
