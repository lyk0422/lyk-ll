package com.example.starter.ctlog;

import com.example.starter.ctlog.crypto.DemoObserverKeys;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;

/**
 * 测试工具：用内置合成演示私钥对观察者上报消息签名，以及构造合成 DER 数据。
 */
public final class TestSigning {

    private TestSigning() {
    }

    /**
     * 用演示私钥签名并返回十六进制签名。
     */
    public static String signWithDemoKey(byte[] message) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(
                    HexFormat.of().parseHex(DemoObserverKeys.PRIVATE_KEY_PKCS8_HEX)));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message);
            return HexFormat.of().formatHex(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 生成确定性的合成 DER 字节（仅测试用）。
     */
    public static byte[] syntheticDer(int seed) {
        byte[] der = new byte[16];
        for (int i = 0; i < der.length; i++) {
            der[i] = (byte) (seed * 31 + i);
        }
        return der;
    }
}
