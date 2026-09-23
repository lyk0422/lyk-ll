package com.example.starter.signing;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 签署流程 API 的请求/响应契约（合成数据）。
 */
public final class Contracts {

    private Contracts() {
    }

    /** 一个有序步骤：同一步内多个签署人为并行关系，须全部完成才开放下一步。 */
    public record Step(
            @NotEmpty @Size(max = 32) List<@NotBlank String> signers) {
    }

    /** 建包请求：packageId 缺省时由服务端生成 UUID。 */
    public record CreatePackageRequest(
            @Size(max = 36) String packageId,
            @NotBlank @Size(min = 64, max = 64) String docSha256,
            @NotNull Instant expiresAt,
            @NotEmpty @Size(max = 64) List<@Valid Step> steps) {
    }

    /** 提交签名请求：requestId 为幂等键，expectedVersion 为乐观并发控制。 */
    public record SubmitSignatureRequest(
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 128) String signerId,
            int revision,
            @NotBlank @Size(min = 64, max = 64) String docSha256,
            long expectedVersion,
            @NotBlank @Size(max = 128) String signature) {
    }

    /** 修订文档请求：产生新修订号并使旧修订全部签名失效。 */
    public record RevisePackageRequest(
            @NotBlank @Size(min = 64, max = 64) String docSha256,
            long expectedVersion) {
    }

    /** 取消流程请求。 */
    public record CancelPackageRequest(
            long expectedVersion) {
    }

    /** 签署人状态：signed 表示在当前修订下已提交有效签名。 */
    public record SignerState(String signerId, boolean signed) {
    }

    /** 步骤状态。 */
    public record StepState(int index, List<SignerState> signers) {
    }

    /** 签署包流程状态视图。 */
    public record PackageState(
            String packageId,
            String status,
            int currentRevision,
            String docSha256,
            int currentStep,
            int totalSteps,
            long version,
            Instant expiresAt,
            List<StepState> steps,
            boolean evidenceFrozen,
            String evidenceHash) {
    }

    /** 提交签名响应：replayed=true 表示命中幂等日志、返回首次结果。 */
    public record SignatureResult(
            String requestId,
            boolean replayed,
            PackageState packageState) {
    }

    /** 证据包：最终完成时原子冻结，重复查询摘要稳定。 */
    public record EvidencePackage(
            String packageId,
            int finalRevision,
            String docSha256,
            Instant completedAt,
            List<StepState> steps,
            List<EvidenceSignature> validSignatures,
            List<EvidenceSignature> invalidatedSignatures,
            List<EvidenceEvent> events,
            String evidenceHash) {
    }

    /** 证据包中的签名条目。 */
    public record EvidenceSignature(
            int revision,
            int stepIndex,
            String signerId,
            String docSha256,
            String signature,
            String status,
            Instant signedAt) {
    }

    /** 证据包中的事件条目（含哈希链前后哈希）。 */
    public record EvidenceEvent(
            int seq,
            String eventType,
            String payload,
            String prevHash,
            String eventHash) {
    }

    /** 统一错误响应体。 */
    public record ErrorBody(String code, String message) {
    }
}
