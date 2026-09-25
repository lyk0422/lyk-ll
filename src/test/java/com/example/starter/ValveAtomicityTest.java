package com.example.starter;

import com.example.starter.repository.ValveVersionRepository;
import com.example.starter.service.AdjustmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 开度版本原子性：开度写入与版本记录同事务，版本写入失败时开度不得已改。
 */
class ValveAtomicityTest extends IntegrationTestBase {

    @Autowired
    private AdjustmentService adjustmentService;

    @MockitoBean
    private ValveVersionRepository versionRepository;

    @Test
    void 版本写入失败时开度与任务整体回滚() {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        doThrow(new IllegalStateException("模拟版本写入失败"))
                .when(versionRepository)
                .insert(anyLong(), anyLong(), anyInt(), any(), any(), any());

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-atomic", 1L, new BigDecimal("5")))
                .isInstanceOf(IllegalStateException.class);

        // 事务回滚：开度未变、无版本、无任务记录（不得开度已改而无版本）
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(countOf("valve_opening_version")).isZero();
        assertThat(countOf("adjustment_task")).isZero();
    }
}
