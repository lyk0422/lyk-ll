package com.example.starter.signing.web;

import java.time.Instant;
import java.util.List;

/**
 * 签署包状态视图。
 *
 * @param packageId       包 ID
 * @param currentRevision 当前修订号
 * @param documentHash    当前文档摘要
 * @param expiresAt       UTC 到期时间
 * @param status          流程状态
 * @param frozenAt        冻结时间（UTC），未冻结为 null
 * @param evidenceDigest  证据包摘要，未冻结为 null
 * @param steps           各步骤及当前修订下的签署进度
 */
public record PackageStatusResponse(
        String packageId,
        int currentRevision,
        String documentHash,
        Instant expiresAt,
        String status,
        Instant frozenAt,
        String evidenceDigest,
        List<StepStatus> steps) {

    /**
     * 单个步骤的签署进度。
     *
     * @param stepOrder 步骤序号
     * @param open      当前是否开放（前序步骤全部完成）
     * @param signers   步骤内每个签署人的完成情况
     */
    public record StepStatus(
            int stepOrder,
            boolean open,
            List<SignerStatus> signers) {
    }

    /**
     * 签署人完成情况。
     *
     * @param signer    签署人
     * @param signed    当前修订下是否已完成有效签名
     */
    public record SignerStatus(String signer, boolean signed) {
    }
}
