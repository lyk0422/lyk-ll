package com.example.starter.pension.bank;

import org.springframework.stereotype.Component;

/**
 * 本地（进程内）托管行模拟实现：无外部银行、无网络 IO。
 * forceFail=true 时确定性返回失败，用于失败重试与事务回滚测试；其余返回成功。
 */
@Component
public class LocalBankGateway implements BankGateway {

    @Override
    public boolean payout(String planNo, int seqNo, long amountCents, boolean forceFail) {
        if (forceFail) {
            return false;
        }
        return true;
    }
}
