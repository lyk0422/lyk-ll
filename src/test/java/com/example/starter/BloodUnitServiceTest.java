package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import com.example.starter.repository.ResultEventRepository;
import com.example.starter.service.BloodUnitService;
import com.example.starter.web.dto.InboundRequest;
import com.example.starter.web.dto.ResultCallbackRequest;
import com.example.starter.web.dto.UnitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 成分血状态机与检测结果回传测试（H2 真实库）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BloodUnitServiceTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 9, 30);

    @Autowired
    private BloodUnitService service;

    @Autowired
    private ResultEventRepository events;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM issue_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM result_event");
        jdbc.update("DELETE FROM blood_unit");
    }

    private void inbound(String barcode) {
        service.inbound(new InboundRequest(barcode, EXPIRY));
    }

    private UnitResponse callback(String barcode, TestType type, TestResultValue result) {
        return service.recordResult(barcode, new ResultCallbackRequest(type, result));
    }

    @Test
    void 入库后初始状态为待检() {
        UnitResponse response = service.inbound(new InboundRequest("BC-001", EXPIRY));

        assertThat(response.status()).isEqualTo(UnitStatus.PENDING_TEST);
        assertThat(response.initialResult()).isNull();
        assertThat(response.recheckResult()).isNull();
        assertThat(response.expiryDate()).isEqualTo(EXPIRY);
    }

    @Test
    void 重复条码入库返回冲突() {
        inbound("BC-002");

        assertThatThrownBy(() -> service.inbound(new InboundRequest("BC-002", EXPIRY)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("BC-002");
    }

    @Test
    void 未知条码回传返回不存在() {
        assertThatThrownBy(() -> callback("BC-404", TestType.INITIAL, TestResultValue.PASS))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 初检合格后另一项未回保持隔离() {
        inbound("BC-010");

        UnitResponse response = callback("BC-010", TestType.INITIAL, TestResultValue.PASS);

        assertThat(response.status()).isEqualTo(UnitStatus.QUARANTINED);
    }

    @Test
    void 乱序回传复检先到最终仍可放行() {
        inbound("BC-011");

        // 复检先回传：隔离
        UnitResponse afterRecheck = callback("BC-011", TestType.RECHECK, TestResultValue.PASS);
        assertThat(afterRecheck.status()).isEqualTo(UnitStatus.QUARANTINED);
        assertThat(afterRecheck.initialResult()).isNull();
        assertThat(afterRecheck.recheckResult()).isEqualTo(TestResultValue.PASS);

        // 初检后回传：双合格进入可放行
        UnitResponse afterInitial = callback("BC-011", TestType.INITIAL, TestResultValue.PASS);
        assertThat(afterInitial.status()).isEqualTo(UnitStatus.RELEASABLE);
    }

    @Test
    void 任一不合格直接报废() {
        inbound("BC-012");
        callback("BC-012", TestType.INITIAL, TestResultValue.PASS);

        UnitResponse response = callback("BC-012", TestType.RECHECK, TestResultValue.FAIL);

        assertThat(response.status()).isEqualTo(UnitStatus.DISCARDED);
    }

    @Test
    void 首项即不合格无需等待另一项直接报废() {
        inbound("BC-013");

        UnitResponse response = callback("BC-013", TestType.RECHECK, TestResultValue.FAIL);

        assertThat(response.status()).isEqualTo(UnitStatus.DISCARDED);
    }

    @Test
    void 报废终态后合格回传不复活只记迟到事件() {
        inbound("BC-014");
        callback("BC-014", TestType.INITIAL, TestResultValue.FAIL);

        UnitResponse late = callback("BC-014", TestType.RECHECK, TestResultValue.PASS);

        assertThat(late.status()).isEqualTo(UnitStatus.DISCARDED);
        assertThat(late.recheckResult()).isNull();
        assertThat(events.countLateByBarcode("BC-014")).isEqualTo(1);
    }

    @Test
    void 终态后不合格回传同样只记迟到事件() {
        inbound("BC-015");
        callback("BC-015", TestType.INITIAL, TestResultValue.PASS);
        callback("BC-015", TestType.RECHECK, TestResultValue.PASS);
        // 可放行状态下直接标记为已放行由放行流程负责，这里用报废构造终态
        inbound("BC-016");
        callback("BC-016", TestType.INITIAL, TestResultValue.FAIL);

        UnitResponse late = callback("BC-016", TestType.RECHECK, TestResultValue.FAIL);

        assertThat(late.status()).isEqualTo(UnitStatus.DISCARDED);
        assertThat(events.countLateByBarcode("BC-016")).isEqualTo(1);
    }
}
