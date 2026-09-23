package com.example.starter.signing.web;

import java.time.Instant;
import java.util.List;

/**
 * 证据包查询视图；内容为冻结时的规范 JSON 快照，重复查询摘要稳定。
 *
 * @param packageId      包 ID
 * @param digest         证据包 SHA-256 摘要
 * @param frozenAt       冻结时间（UTC）
 * @param documentHash   冻结时当前文档摘要
 * @param revision       冻结时修订号
 * @param steps          步骤及并行签署人
 * @param validSignatures 有效签名
 * @param invalidatedHistory 失效签名历史
 * @param events         按序事件链
 */
public record EvidenceResponse(
        String packageId,
        String digest,
        Instant frozenAt,
        String documentHash,
        int revision,
        List<StepView> steps,
        List<SignatureView> validSignatures,
        List<SignatureView> invalidatedHistory,
        List<EventView> events) {

    /**
     * 步骤视图。
     *
     * @param stepOrder 步骤序号
     * @param signers   并行签署人
     */
    public record StepView(int stepOrder, List<String> signers) {
    }

    /**
     * 签名视图（有效或失效历史）。
     *
     * @param revision     签名针对的修订号
     * @param stepOrder    步骤序号
     * @param signer       签署人
     * @param documentHash 提交时文档摘要
     * @param signature    合成签名值
     * @param signedAt     签名时间（UTC）
     * @param invalidatedByRevision 使签名失效的修订号，有效签名为 null
     */
    public record SignatureView(
            int revision,
            int stepOrder,
            String signer,
            String documentHash,
            String signature,
            Instant signedAt,
            Integer invalidatedByRevision) {
    }

    /**
     * 事件链视图。
     *
     * @param seq          包内序号
     * @param type         事件类型
     * @param revision     事件时修订号
     * @param previousHash 前一事件哈希
     * @param eventHash    本事件哈希
     * @param detail       事件规范 JSON
     * @param at           事件时间（UTC）
     */
    public record EventView(
            int seq,
            String type,
            int revision,
            String previousHash,
            String eventHash,
            String detail,
            Instant at) {
    }
}
