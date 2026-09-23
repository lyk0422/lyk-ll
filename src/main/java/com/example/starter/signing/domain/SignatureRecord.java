package com.example.starter.signing.domain;

import java.time.Instant;

import com.example.starter.signing.model.SignatureStatus;

/**
 * 签名记录 {@code signature_record} 对应实体；被修订失效后仍保留。
 */
public class SignatureRecord {

    /** 签名记录 ID（UUID）。 */
    private String id;
    /** 所属签署包 ID。 */
    private String packageId;
    /** 签名针对的文档修订号。 */
    private int revision;
    /** 签署人所属步骤序号。 */
    private int stepOrder;
    /** 签署人标识。 */
    private String signer;
    /** 提交时携带的文档 SHA-256 摘要。 */
    private String documentHash;
    /** 合成签名值。 */
    private String signatureValue;
    /** 签名状态。 */
    private SignatureStatus status;
    /** 幂等请求键。 */
    private String requestId;
    /** 签名提交时间（UTC）。 */
    private Instant createdAt;
    /** 失效时间（UTC），仍有效为 null。 */
    private Instant invalidatedAt;
    /** 使该签名失效的修订号。 */
    private Integer invalidatedByRevision;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPackageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public int getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(int stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getSigner() {
        return signer;
    }

    public void setSigner(String signer) {
        this.signer = signer;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public void setDocumentHash(String documentHash) {
        this.documentHash = documentHash;
    }

    public String getSignatureValue() {
        return signatureValue;
    }

    public void setSignatureValue(String signatureValue) {
        this.signatureValue = signatureValue;
    }

    public SignatureStatus getStatus() {
        return status;
    }

    public void setStatus(SignatureStatus status) {
        this.status = status;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(Instant invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public Integer getInvalidatedByRevision() {
        return invalidatedByRevision;
    }

    public void setInvalidatedByRevision(Integer invalidatedByRevision) {
        this.invalidatedByRevision = invalidatedByRevision;
    }
}
