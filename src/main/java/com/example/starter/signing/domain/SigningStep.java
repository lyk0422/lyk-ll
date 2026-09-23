package com.example.starter.signing.domain;

/**
 * 签署步骤定义 {@code signing_step} 对应实体；同一步骤多行表示多个并行签署人。
 */
public class SigningStep {

    /** 所属签署包 ID。 */
    private String packageId;
    /** 步骤序号，从 1 开始。 */
    private int stepOrder;
    /** 该步骤的并行签署人标识。 */
    private String signer;

    public SigningStep() {
    }

    public SigningStep(String packageId, int stepOrder, String signer) {
        this.packageId = packageId;
        this.stepOrder = stepOrder;
        this.signer = signer;
    }

    public String getPackageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
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
}
