package com.example.starter.business;

import com.example.starter.domain.ValveAction;
import com.example.starter.repo.ValveVersionRepository;
import com.example.starter.service.BalanceService;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 开度版本原子性测试：阀门开度更新与版本记录必须同事务提交；
 * 中途故障时整批回滚，不得出现“开度已改而无版本”。
 */
class OpeningVersionAtomicityIntegrationTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-A";
    private static final List<String> UNITS = List.of("ST-A-U01", "ST-A-U02", "ST-A-U03");

    @Autowired
    private BalanceService balanceService;

    @MockitoSpyBean
    private ValveVersionRepository versionRepository;

    @Test
    void failureDuringSecondStep_rollsBackValveOpeningAndVersions() {
        createStation(STATION, 3);

        // 第一个版本正常写入，第二个版本写入时抛错：整事务必须回滚
        Mockito.doNothing().doThrow(new RuntimeException("模拟版本写入故障"))
                .when(versionRepository)
                .append(anyLong(), any(Integer.class), any(), any(), any(),
                        any(), anyLong(), any(), any());

        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("atomic-adj-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "3"),
                Requests.step(UNITS.get(1), ValveAction.CLOSE, "4"))))
                .hasMessageContaining("模拟版本写入故障");

        // 两个阀门开度都保持初始值，版本与台账均为 0
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("50.000");
        assertThat(valveOpening(STATION, UNITS.get(1))).isEqualByComparingTo("50.000");
        assertThat(count("valve_opening_version")).isZero();
        assertThat(count("adjustment_record")).isZero();
        assertThat(count("adjustment_task")).isZero();
        // 失败后幂等受理记录已释放，可修正后重新下达
        assertThat(count("idempotent_request")).isZero();

        Mockito.reset(versionRepository);
        balanceService.adjust(Requests.adjust("atomic-adj-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "3")));
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("53.000");
        assertThat(count("valve_opening_version")).isEqualTo(1);
    }
}
