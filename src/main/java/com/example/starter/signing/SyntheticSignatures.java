package com.example.starter.signing;

/**
 * 本地合成签名适配器：不依赖 CA 或外部签署平台。
 * 合法签名定义为可复现的确定公式：
 * <pre>signature = SHA-256("SYNTH-SIG|" + packageId + "|" + revision + "|" + signerId + "|" + docSha256)</pre>
 * 客户端与测试均可按此公式生成签名，服务端按同一公式校验。
 */
public final class SyntheticSignatures {

    private SyntheticSignatures() {
    }

    /**
     * 按合成公式生成签名（客户端侧使用）。
     */
    public static String sign(String packageId, int revision, String signerId, String docSha256) {
        return Hashing.sha256Hex("SYNTH-SIG|" + packageId + "|" + revision + "|" + signerId + "|" + docSha256);
    }

    /**
     * 校验签名是否符合合成公式（服务端本地适配器）。
     */
    public static boolean verify(String packageId, int revision, String signerId, String docSha256, String signature) {
        return signature != null && signature.equals(sign(packageId, revision, signerId, docSha256));
    }
}
