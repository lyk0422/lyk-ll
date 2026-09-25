package com.example.starter.reinsurance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 合约级摊回账户行对象；余额任何时刻不得为负（数据库 CHECK 强制）。
 */
public class ReinsuranceAccount {

    private Long id;
    private Long contractId;
    private BigDecimal balance;
    private Instant updatedAt;

    public ReinsuranceAccount() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getContractId() {
        return contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
