package com.example.starter.cosmetic.support;

import com.example.starter.cosmetic.domain.ChildBandDossier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 包装标签文本摘要口径：结构化（无歧义）规范化后取 SHA-256。
 *
 * <p>儿童备案：全局摘要覆盖全部分龄档标识文本；每个年龄档另有独立标识摘要，
 * 印次登记须逐档一致，不能只拿一个全局标识代替。</p>
 *
 * <p>成人备案：摘要覆盖备案当前包装文字。</p>
 */
public final class LabelDigestSupport {

    private LabelDigestSupport() {
    }

    /** 儿童备案全局标识文本摘要（受理冻结口径，与备案 label_digest 一致）。 */
    public static String childGlobalDigest(List<ChildBandDossier> dossiers) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "CHILD_AGE_LABELS");
        fields.put("labels", (dossiers == null ? List.<ChildBandDossier>of() : dossiers).stream()
                .map(d -> {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("bandCode", d.bandCode());
                    entry.put("labelText", d.labelText());
                    return entry;
                }).toList());
        return FingerprintSupport.sha256(fields);
    }

    /** 单个年龄档的独立标识摘要。 */
    public static String bandDigest(String bandCode, String labelText) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "CHILD_BAND_LABEL");
        fields.put("bandCode", bandCode);
        fields.put("labelText", labelText);
        return FingerprintSupport.sha256(fields);
    }

    /** 成人备案标签文本摘要（覆盖备案当前包装文字）。 */
    public static String adultDigest(String packagingText) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", "ADULT_LABEL");
        fields.put("packagingText", packagingText);
        return FingerprintSupport.sha256(fields);
    }
}
