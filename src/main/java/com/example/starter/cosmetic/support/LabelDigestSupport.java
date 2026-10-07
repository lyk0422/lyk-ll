package com.example.starter.cosmetic.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 包装标签文本摘要口径：与备案受理冻结内容绑定的结构化 SHA-256 摘要。
 *
 * <p>成人印次：摘要覆盖备案编号、修订号、功效宣称与包装文字；
 * 儿童印次：逐档摘要覆盖备案编号、修订号、分龄档编码与该档适用年龄标识文本，
 * 印次级摘要为逐档摘要集合（键按字典序规范化）的结构化摘要，
 * 不能只拿一个全局标识代替逐档核对。</p>
 */
public final class LabelDigestSupport {

    private LabelDigestSupport() {
    }

    /** 成人印次标签文本摘要。 */
    public static String adultLabelDigest(String filingNo, int versionNo,
                                          List<String> claims, String packagingText) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "ADULT_LABEL");
        fields.put("filingNo", filingNo);
        fields.put("versionNo", versionNo);
        fields.put("claims", claims == null ? List.of() : claims);
        fields.put("packagingText", packagingText);
        return FingerprintSupport.sha256(fields);
    }

    /** 儿童单分龄档标识文本摘要。 */
    public static String childBandDigest(String filingNo, int versionNo,
                                         String bandCode, String labelText) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "CHILD_BAND_LABEL");
        fields.put("filingNo", filingNo);
        fields.put("versionNo", versionNo);
        fields.put("bandCode", bandCode);
        fields.put("labelText", labelText);
        return FingerprintSupport.sha256(fields);
    }

    /** 儿童印次级摘要：逐档摘要集合（分龄档编码-&gt;该档摘要）的结构化摘要。 */
    public static String childDigestSet(Map<String, String> bandDigests) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "CHILD_BAND_DIGEST_SET");
        fields.put("bands", bandDigests);
        return FingerprintSupport.sha256(fields);
    }
}
