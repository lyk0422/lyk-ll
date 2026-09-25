package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.StarterApplication;
import com.example.starter.domain.ConflictException;
import com.example.starter.support.ControllableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.dto.ConfirmRequest;
import com.example.starter.web.dto.DisputeRequest;
import com.example.starter.web.dto.NominationRequest;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.StationRequest;
import com.example.starter.web.dto.VoucherResponse;
import com.example.starter.web.dto.VoucherSubmitRequest;

/**
 * H2 事务集成测试基类：每个用例前清空数据，避免顺序依赖。
 */
@SpringBootTest(classes = StarterApplication.class)
@Import(TestClockConfig.class)
abstract class AbstractH2IntegrationTest {

    protected static final String STATION = "ST-001";
    protected static final LocalDate GAS_DAY = LocalDate.of(2026, 9, 10);

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected GasCustodyService service;
    @Autowired
    protected ControllableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM reading_difference");
        jdbc.update("DELETE FROM dispute");
        jdbc.update("DELETE FROM voucher");
        jdbc.update("DELETE FROM meter_reading");
        jdbc.update("DELETE FROM daily_nomination");
        jdbc.update("DELETE FROM station");
        clock.setInstant(Instant.parse("2026-09-11T01:00:00Z"));
    }

    /** 创建合成交接站。 */
    protected void createStation() {
        service.createStation(new StationRequest(STATION, "一号交接站", "长庆采气厂", "城市燃气公司"));
    }

    /** 登记日指定：10000 MJ，违约金 0.5 元/MJ。 */
    protected void nominate(String energy) {
        service.saveNomination(new NominationRequest(STATION, GAS_DAY, new BigDecimal(energy),
                new BigDecimal("0.5")));
    }

    /** 上报一条读数。 */
    protected void reading(String requestKeyIgnored, String time, String index, String hv) {
        service.uploadReading(new ReadingRequest(STATION, "METER-A", Instant.parse(time),
                new BigDecimal(index), new BigDecimal(hv)));
    }

    /** 上报道量日两条读数：00:00 表底 1000，23:00 表底 1200，热值 38/40。 */
    protected void seedDayReadings() {
        service.uploadReading(new ReadingRequest(STATION, "METER-A",
                Instant.parse("2026-09-10T23:00:00Z"), new BigDecimal("1200.000"),
                new BigDecimal("40.0000")));
        // 乱序先传 23:00 再传 00:00，验证按表底时刻归位
        service.uploadReading(new ReadingRequest(STATION, "METER-A",
                Instant.parse("2026-09-10T00:00:00Z"), new BigDecimal("1000.000"),
                new BigDecimal("38.0000")));
    }

    /** 生成凭证。 */
    protected VoucherResponse submit(String requestId) {
        return service.submitVoucher(new VoucherSubmitRequest(requestId, STATION, GAS_DAY))
                .body();
    }

    /** 双方先后确认使凭证生效（在固定时钟 2026-09-11T01:00Z 生效，争议截止 2026-09-18）。 */
    protected VoucherResponse makeEffective(String voucherNo, String reqA, String reqB) {
        service.confirm(new ConfirmRequest(reqA, voucherNo, "A", "张三"));
        return service.confirm(new ConfirmRequest(reqB, voucherNo, "B", "李四")).body();
    }

    /** 组装：建站→读数→凭证→双方确认，返回已生效凭证。 */
    protected VoucherResponse effectiveVoucher(String submitReq, String confirmReqA,
            String confirmReqB) {
        createStation();
        seedDayReadings();
        VoucherResponse voucher = submit(submitReq);
        return makeEffective(voucher.voucherNo(), confirmReqA, confirmReqB);
    }

    /** 查表计数。 */
    protected int count(String sql, Object... args) {
        Integer c = jdbc.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    /** 直接按凭证号查状态。 */
    protected String statusOf(String voucherNo) {
        return jdbc.queryForObject("SELECT status FROM voucher WHERE voucher_no = ?",
                String.class, voucherNo);
    }

    /** 查该气量日凭证号列表。 */
    protected List<String> voucherNos() {
        return jdbc.queryForList(
                "SELECT voucher_no FROM voucher ORDER BY created_at, id", String.class);
    }
}
