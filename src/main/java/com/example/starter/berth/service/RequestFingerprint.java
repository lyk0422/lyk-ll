package com.example.starter.berth.service;

import com.example.starter.berth.dto.CreateWindowRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：以 ASCII 单元分隔符（U+001F）拼接 requestId 与全部业务字段后取 SHA-256，
 * 消除字段拼接歧义（如 "ab"+"c" 与 "a"+"bc" 拼接结果不同）。
 */
public final class RequestFingerprint {

    /** 字段分隔符：ASCII Unit Separator（U+001F），不会出现在常规业务字段中。 */
    private static final String SEP = Character.toString((char) 0x1F);

    private RequestFingerprint() {
    }

    /**
     * 计算申请指纹，覆盖 requestId 与全部业务字段。
     *
     * @param request 创建申请请求
     * @return SHA-256 十六进制字符串
     */
    public static String of(CreateWindowRequest request) {
        return ofFields(
                request.requestId(),
                request.vesselName(),
                request.berthCode(),
                request.startAt().toString(),
                request.endAt().toString(),
                request.draftMeters().stripTrailingZeros().toPlainString(),
                Integer.toString(request.pilotsRequired()),
                request.supersedesId() == null ? "" : request.supersedesId().toString());
    }

    /**
     * 计算字段序列的指纹。
     *
     * @param fields 参与指纹的字段，按固定顺序
     * @return SHA-256 十六进制字符串
     */
    public static String ofFields(String... fields) {
        MessageDigest digest = sha256();
        String joined = String.join(SEP, fields);
        return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
