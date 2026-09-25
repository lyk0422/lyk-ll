package com.example.starter.wbb.service;

import com.example.starter.wbb.AbstractH2IntegrationTest;
import com.example.starter.wbb.config.SyntheticDataSeeder;
import com.example.starter.wbb.dto.CheckinRequest;
import com.example.starter.wbb.dto.CloseRequest;
import com.example.starter.wbb.dto.FuelChangeRequest;
import com.example.starter.wbb.dto.LmcRequest;
import com.example.starter.wbb.dto.LoadConfirmRequest;
import com.example.starter.wbb.dto.ManifestResponse;
import com.example.starter.wbb.dto.PayloadItemDto;
import com.example.starter.wbb.dto.PreplanRequest;
import com.example.starter.wbb.dto.ReviewRequest;
import com.example.starter.wbb.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 舱单主流程与失败分支的 H2 事务测试：
 * 预配→值机→装载确认→LMC→双岗复核→放行，覆盖超重、越界、冻结与终态。
 */
class ManifestServiceH2Test extends AbstractH2IntegrationTest {

    private static final String TYPE = SyntheticDataSeeder.AIRCRAFT_TYPE;
    private static final LocalDate DATE = LocalDate.of(2026, 9, 26);

    @org.springframework.beans.factory.annotation.Autowired
    private ManifestService service;

    private PayloadItemDto item(String category, String zone, int pieces, String weight, String arm) {
        return new PayloadItemDto(category, zone, pieces, new BigDecimal(weight), new BigDecimal(arm));
    }

    private PreplanRequest preplanReq(String requestId, String flightNo, String fuel,
                                      List<PayloadItemDto> items) {
        return new PreplanRequest(requestId, flightNo, DATE, TYPE, "B-9999",
                new BigDecimal(fuel), null, items);
    }

    private String key(String flightNo) {
        return ManifestService.flightKey(flightNo, DATE);
    }

    /** 合法基线：业载 10000kg@16.0m，ZFW=50000/CG=16.0，加油 5000kg@14.0m。 */
    private List<PayloadItemDto> baselineItems() {
        return List.of(
                item("PAX", "CABIN", 100, "8000", "16.0"),
                item("CARGO", "HOLD1", 20, "2000", "16.0"));
    }

    private ManifestResponse fullFlowToLoaded(String flight, String prefix) {
        service.preplan(preplanReq(prefix + "-pre", flight, "5000", baselineItems()));
        service.checkin(key(flight), new CheckinRequest(prefix + "-ci", baselineItems()));
        return service.loadConfirm(key(flight), new LoadConfirmRequest(prefix + "-load"));
    }

    @Test
    void happyFlowPreplanCheckinLoadReviewClose() {
        ManifestResponse loaded = fullFlowToLoaded("CA1001", "flow1");
        assertThat(loaded.status()).isEqualTo("LOADED");
        assertThat(loaded.payloadLimitOk()).isTrue();
        assertThat(loaded.envelopeOk()).isTrue();
        assertThat(loaded.zeroFuelWeightKg()).isEqualByComparingTo("50000.00");
        assertThat(loaded.takeoffWeightKg()).isEqualByComparingTo("55000.00");

        ManifestResponse wbo = service.review(key("CA1001"),
                new ReviewRequest("flow1-rwbo", "WBO", "zhang.san"));
        assertThat(wbo.status()).isEqualTo("LOADED");
        assertThat(wbo.wboSigned()).isTrue();
        assertThat(wbo.dspSigned()).isFalse();
        assertThat(wbo.frozen()).isFalse();

        // 单人签字不生效：不能关闭。
        assertThatThrownBy(() -> service.close(key("CA1001"), new CloseRequest("flow1-close-early")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("双岗复核");

        ManifestResponse reviewed = service.review(key("CA1001"),
                new ReviewRequest("flow1-rdsp", "DSP", "li.si"));
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(reviewed.frozen()).isTrue();

        ManifestResponse closed = service.close(key("CA1001"), new CloseRequest("flow1-close"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isNotNull();
    }

    @Test
    void payloadExactlyAtMaxZeroFuelWeightIsLegal() {
        // 业载恰为 20000kg（MZFW-OEW），力臂 16.0，ZFW 恰为 60000；零油即可，TOW=ZFW。
        List<PayloadItemDto> exact = List.of(item("CARGO", "HOLD1", 100, "20000", "16.0"));
        ManifestResponse m = service.preplan(preplanReq("exact-pre", "CA1002", "0", exact));
        assertThat(m.zeroFuelWeightKg()).isEqualByComparingTo("60000.00");
        assertThat(m.payloadLimitOk()).isTrue();
    }

    @Test
    void payloadOverMaxZeroFuelWeightIsRejectedAndRolledBack() {
        List<PayloadItemDto> over = List.of(item("CARGO", "HOLD1", 100, "20000.01", "16.0"));
        assertThatThrownBy(() -> service.preplan(preplanReq("over-pre", "CA1003", "0", over)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("最大无油重量");
        // 事务回滚：无有效舱单、无版本残留。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM manifest WHERE flight_key = ?", Integer.class, key("CA1003")))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_active_manifest WHERE flight_key = ?",
                Integer.class, key("CA1003"))).isZero();
    }

    @Test
    void cgExactlyOnEnvelopeBoundaryIsLegalAndOneStepOutRejected() {
        // ZFW=60000，前限恰 15.6：payload 力矩 = 60000*15.6 - 640000 = 296000（业载 20000@14.8）。
        List<PayloadItemDto> boundary = List.of(item("CARGO", "HOLD1", 100, "20000", "14.8"));
        ManifestResponse m = service.preplan(preplanReq("bound-pre", "CA1004", "0", boundary));
        assertThat(m.zfwCgArm()).isEqualByComparingTo("15.600000");
        assertThat(m.envelopeOk()).isTrue();

        // 业载力臂再前移 0.001m，重心越界：14.799 → 力矩 295980，CG=15.599666…
        List<PayloadItemDto> outside = List.of(item("CARGO", "HOLD1", 100, "20000", "14.799"));
        assertThatThrownBy(() -> service.preplan(preplanReq("bound-out", "CA1005", "0", outside)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重心");
    }

    @Test
    void lmcExactlyAtLimitStaysInVersion() {
        fullFlowToLoaded("CA1006", "lim");
        // 恰达 +5 人 / +500kg：仍在 v1 原地修正。
        ManifestResponse r = service.lmc(key("CA1006"),
                new LmcRequest("lim-lmc5", List.of(item("PAX", "CABIN", 5, "500", "16.0"))));
        assertThat(r.versionNo()).isEqualTo(1);
        assertThat(r.lmcDeltaPax()).isEqualTo(5);
        assertThat(r.lmcDeltaKg()).isEqualByComparingTo("500.00");
        assertThat(r.payloadKg()).isEqualByComparingTo("10500.00");
        // 明细可复算：10500kg、力矩 168000。
        assertThat(r.items()).hasSize(3);
    }

    @Test
    void lmcOverLimitReprintsNewVersionAndSupersedesOld() {
        ManifestResponse v1 = fullFlowToLoaded("CA1007", "rep");
        // 先恰达限额。
        service.lmc(key("CA1007"),
                new LmcRequest("rep-lmc5", List.of(item("PAX", "CABIN", 5, "500", "16.0"))));
        // 再 +1kg 即超 500kg 限额：重打 v2。
        ManifestResponse v2 = service.lmc(key("CA1007"),
                new LmcRequest("rep-lmc-over", List.of(item("BAGGAGE", "HOLD1", 1, "1", "16.0"))));
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.parentId()).isEqualTo(v1.id());
        assertThat(v2.lmcDeltaPax()).isZero();
        assertThat(v2.lmcDeltaKg()).isEqualByComparingTo("0.00");
        assertThat(v2.payloadKg()).isEqualByComparingTo("10501.00");

        // 有效指针指向 v2，v1 为 SUPERSEDED；版本链两版可复算。
        assertThat(service.getActive(key("CA1007")).id()).isEqualTo(v2.id());
        List<ManifestResponse> chain = service.versions(key("CA1007"));
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(chain.get(1).status()).isEqualTo("LOADED");
    }

    @Test
    void fuelChangeRecalculatesCgInSameUpdate() {
        fullFlowToLoaded("CA1008", "fuel");
        ManifestResponse before = service.getActive(key("CA1008"));
        ManifestResponse after = service.changeFuel(key("CA1008"),
                new FuelChangeRequest("fuel-8000", new BigDecimal("8000"), null));
        // 无油重量/重心不变，起飞重量与重心联动重算：
        // TOW=58000，力矩=800000+8000*14=912000，CG=15.724138。
        assertThat(after.zeroFuelWeightKg()).isEqualByComparingTo("50000.00");
        assertThat(after.zfwCgArm()).isEqualByComparingTo(before.zfwCgArm());
        assertThat(after.fuelKg()).isEqualByComparingTo("8000.00");
        assertThat(after.takeoffWeightKg()).isEqualByComparingTo("58000.00");
        assertThat(after.towCgArm()).isEqualByComparingTo("15.724138");
        assertThat(after.towCgArm()).isNotEqualByComparingTo(before.towCgArm());
    }

    @Test
    void fuelChangePushingCgOutOfEnvelopeRollsBack() {
        fullFlowToLoaded("CA1009", "fuelbad");
        ManifestResponse before = service.getActive(key("CA1009"));
        // 25000kg@14m 前油使 TOW=75000 时 CG≈15.333，越前限 15.7。
        assertThatThrownBy(() -> service.changeFuel(key("CA1009"),
                new FuelChangeRequest("fuel-bad", new BigDecimal("25000"), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("重心");
        // 同事务回滚：油量与重心都维持原值，杜绝改油不改重心。
        ManifestResponse unchanged = service.getActive(key("CA1009"));
        assertThat(unchanged.fuelKg()).isEqualByComparingTo(before.fuelKg());
        assertThat(unchanged.towCgArm()).isEqualByComparingTo(before.towCgArm());
    }

    @Test
    void sameReviewerCannotSignBothRoles() {
        fullFlowToLoaded("CA1010", "dual");
        service.review(key("CA1010"), new ReviewRequest("dual-r1", "WBO", "same.user"));
        assertThatThrownBy(() -> service.review(key("CA1010"),
                new ReviewRequest("dual-r2", "DSP", "same.user")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("不同人员");
        assertThat(service.getActive(key("CA1010")).frozen()).isFalse();
    }

    @Test
    void frozenManifestRejectsPayloadAndFuelChanges() {
        ManifestResponse reviewed = reviewBoth("CA1011", "frz");
        assertThat(reviewed.frozen()).isTrue();
        assertThatThrownBy(() -> service.changeFuel(key("CA1011"),
                new FuelChangeRequest("frz-fuel", new BigDecimal("6000"), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("冻结");
        assertThatThrownBy(() -> service.lmc(key("CA1011"),
                new LmcRequest("frz-lmc", List.of(item("PAX", "CABIN", 1, "80", "16")))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void closedTerminalStateRecordsLateChangesAndRepeatsCloseIdempotently() {
        reviewBothAndClose("CA1012", "term");
        ManifestResponse closed = service.getActive(key("CA1012"));

        // 关闭后迟到值机：只记迟到事件并告警，舱单不变，接口返回当前（关闭）舱单。
        ManifestResponse lateCi = service.checkin(key("CA1012"),
                new CheckinRequest("term-lateci", List.of(item("PAX", "CABIN", 1, "80", "16"))));
        assertThat(lateCi.status()).isEqualTo("CLOSED");
        assertThat(lateCi.payloadKg()).isEqualByComparingTo("10000.00");
        // 关闭后迟到 LMC 同样只记事件。
        service.lmc(key("CA1012"),
                new LmcRequest("term-latelmc", List.of(item("PAX", "CABIN", 2, "160", "16"))));
        assertThat(service.lateEvents(key("CA1012"))).hasSize(2)
                .allSatisfy(e -> assertThat(e.alarmed()).isTrue());

        // 重复关闭：无 requestId 也幂等成功。
        ManifestResponse repeat = service.close(key("CA1012"), new CloseRequest(null));
        assertThat(repeat.status()).isEqualTo("CLOSED");
        assertThat(repeat.closedAt()).isEqualTo(closed.closedAt());
    }

    @Test
    void replaySameRequestIdReturnsFirstResultWithoutNewVersion() {
        fullFlowToLoaded("CA1013", "rep1");
        LmcRequest req = new LmcRequest("rep1-lmc", List.of(item("PAX", "CABIN", 3, "300", "16")));
        ManifestResponse first = service.lmc(key("CA1013"), req);
        ManifestResponse replay = service.lmc(key("CA1013"), req);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.lmcDeltaPax()).isEqualTo(3);
        // 明细只落一份增量（3 行基线 + 1 行增量）。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payload_item WHERE manifest_id = ?",
                Integer.class, first.id())).isEqualTo(3);
        assertThat(service.versions(key("CA1013"))).hasSize(1);
    }

    private ManifestResponse reviewBoth(String flight, String prefix) {
        fullFlowToLoaded(flight, prefix);
        service.review(key(flight), new ReviewRequest(prefix + "-rwbo", "WBO", "zhang.san"));
        return service.review(key(flight), new ReviewRequest(prefix + "-rdsp", "DSP", "li.si"));
    }

    private void reviewBothAndClose(String flight, String prefix) {
        reviewBoth(flight, prefix);
        service.close(key(flight), new CloseRequest(prefix + "-close"));
    }
}
