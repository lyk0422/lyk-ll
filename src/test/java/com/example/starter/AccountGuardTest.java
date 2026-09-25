package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.example.starter.domain.Models.Treaty;
import com.example.starter.error.ApiException;
import com.example.starter.repo.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 摊回账户余额非负守卫：应用层原子守卫与数据库 CHECK 约束双重验证，失败时事务回滚。
 */
class AccountGuardTest extends BaseIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 余额不足时冲销被拒绝且事务回滚() {
        Treaty treaty = newTreaty("T-1", "0.8", "100000.00");
        OffsetDateTime now = OffsetDateTime.now(clock);

        ApiException e = assertThrows(ApiException.class, () -> transactionTemplate.execute(s -> {
            accountRepository.applyDelta(treaty.id(), new BigDecimal("-1.00"), now);
            return null;
        }));

        assertEquals("NEGATIVE_BALANCE", e.code());
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf("T-1")));
    }

    @Test
    void 数据库CHECK约束拒绝负余额() {
        Treaty treaty = newTreaty("T-2", "0.8", "100000.00");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE recovery_account SET balance = -0.01 WHERE treaty_id = ?", treaty.id()));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf("T-2")));
    }

    @Test
    void 预提冲销序列全程余额非负() {
        newTreaty("T-3", "1.0", "100000.00");
        newPolicy("T-3", "P-3", "0.00");
        claimService.register("rq-1", "P-3", "C-3a", new BigDecimal("100.00"));
        claimService.register("rq-2", "P-3", "C-3b", new BigDecimal("50.00"));
        claimService.close("rq-3", "C-3a", new BigDecimal("80.00"));
        claimService.close("rq-4", "C-3b", new BigDecimal("60.00"));

        // 预提 150，冲销 -20 与 +10，最终余额 140；每条分录余额均非负
        assertEquals(0, new BigDecimal("140.00").compareTo(balanceOf("T-3")));
        assertTrue(treatyService.getAccount("T-3").entries().stream()
                .allMatch(en -> en.balanceAfter().signum() >= 0));
    }
}
