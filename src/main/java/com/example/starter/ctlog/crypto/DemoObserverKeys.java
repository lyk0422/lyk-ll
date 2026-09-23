package com.example.starter.ctlog.crypto;

/**
 * 内置合成演示密钥对（仅用于本地演示与测试，不对应任何真实身份）。
 *
 * <p>私钥以 PKCS#8、公钥以 32 字节原始编码的十六进制保存；
 * 数据与密钥均只在 JVM 生命周期内有效。</p>
 */
public final class DemoObserverKeys {

    /**
     * 演示观察者 Ed25519 公钥（32 字节原始编码，十六进制）。
     */
    public static final String PUBLIC_KEY_HEX =
            "7f5e4622e6e347b14f2dbecd66629c7dc2880bb8ed12c0c6c476550cb6e4d43c";

    /**
     * 演示观察者 Ed25519 私钥（PKCS#8 DER，十六进制），仅用于本地演示签名。
     */
    public static final String PRIVATE_KEY_PKCS8_HEX =
            "302e020100300506032b6570042204201301117d848152a5dd7afc6de49f0002b7e8e1d9108c65f1d1cb06c6972b5879";

    private DemoObserverKeys() {
    }
}
