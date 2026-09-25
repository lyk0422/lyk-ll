package com.example.starter.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：覆盖 requestId 与全部业务字段，SHA-256 摘要。
 * 同键同参指纹一致返回首次结果，同键异参指纹不同返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算业务指纹。
     *
     * @param parts requestId 及全部业务字段，按接口约定顺序拼接
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String of(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(String.valueOf(part)).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
