package com.example.starter.lc.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.lc.web.dto.DiscrepancyInput;
import com.example.starter.lc.web.dto.DocItem;
import com.example.starter.lc.web.dto.WaiverItem;

/**
 * 幂等指纹计算：指纹覆盖 requestId 与全部业务字段；不符点按单据类型（次级按代码/描述）
 * 排序规范化，因此同一集合不同提交顺序视为同参，而内容差异必然改变指纹。
 */
@Service
public class FingerprintService {

    /** 交单/改单重交指纹。 */
    public String present(String requestId, String lcNo, String presentationNo,
            String supersedesPresentationNo, String drawAmount, List<DocItem> docs) {
        List<String> docParts = docs.stream()
                .sorted((a, b) -> {
                    int c = a.docType().compareTo(b.docType());
                    if (c != 0) {
                        return c;
                    }
                    c = a.docNo().compareTo(b.docNo());
                    return c != 0 ? c : String.valueOf(a.description()).compareTo(String.valueOf(b.description()));
                })
                .map(d -> join(d.docType(), d.docNo(), nz(d.description())))
                .toList();
        return sha256(join("PRESENT", requestId, lcNo, presentationNo, nz(supersedesPresentationNo),
                drawAmount, String.join("|", docParts)));
    }

    /** 拒付通知指纹：不符点排序规范化。 */
    public String notice(String requestId, String presentationNo, List<DiscrepancyInput> discrepancies) {
        List<String> parts = discrepancies.stream()
                .sorted((a, b) -> {
                    int c = a.docType().compareTo(b.docType());
                    if (c != 0) {
                        return c;
                    }
                    c = a.code().compareTo(b.code());
                    return c != 0 ? c : nz(a.description()).compareTo(nz(b.description()));
                })
                .map(d -> join(d.docType(), d.code(), nz(d.description())))
                .toList();
        return sha256(join("NOTICE", requestId, presentationNo, String.join("|", parts)));
    }

    /** 放弃声明指纹：放弃条目排序规范化（去重由服务层校验）。 */
    public String waiver(String requestId, String presentationNo, List<WaiverItem> items) {
        List<String> parts = items.stream()
                .sorted((a, b) -> {
                    int c = a.docType().compareTo(b.docType());
                    return c != 0 ? c : a.code().compareTo(b.code());
                })
                .map(d -> join(d.docType(), d.code()))
                .toList();
        return sha256(join("WAIVER", requestId, presentationNo, String.join("|", parts)));
    }

    /** 仅以 requestId 与批次编号定位的操作指纹（付款/退单）。 */
    public String simple(String operation, String requestId, String presentationNo) {
        return sha256(join(operation, requestId, presentationNo));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String join(String... parts) {
        return String.join("", parts);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
