package com.example.starter.pension.bank;

/**
 * 托管银行出款网关。本地实现不依赖任何外部托管行，仅按指令确定性返回出款结果。
 */
public interface BankGateway {

    /**
     * 发起一笔成员账户出款。
     *
     * @param planNo      计划号
     * @param seqNo       期次序号
     * @param amountCents 出款金额，单位：分
     * @param forceFail   是否模拟出款失败
     * @return true 出款成功；false 出款失败（期次可换 requestId 重试）
     */
    boolean payout(String planNo, int seqNo, long amountCents, boolean forceFail);
}
