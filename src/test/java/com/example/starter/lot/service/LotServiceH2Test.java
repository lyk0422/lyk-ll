package com.example.starter.lot.service;

import com.example.starter.lot.api.ChildOutput;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.GenealogyView;
import com.example.starter.lot.api.ImpactView;
import com.example.starter.lot.api.ParentInput;
import com.example.starter.lot.api.QuarantineRequest;
import com.example.starter.lot.api.QuarantineResponse;
import com.example.starter.lot.api.TransformRequest;
import com.example.starter.lot.api.TransformResponse;
import com.example.starter.lot.config.LotConfig;
import com.example.starter.lot.model.LotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批次谱系服务的 H2（MODE=MySQL）事务与并发测试：真实数据库约束、条件更新、
 * 事务回滚与多线程提交顺序均不使用 mock。
 */
@SpringBootTest
class LotServiceH2Test {

    /** 可控固定时钟，所有业务时间断言基于该时刻。 */
    static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 9, 23, 10, 0, 0);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW.atZone(LotConfig.BUSINESS_ZONE).toInstant(),
                    LotConfig.BUSINESS_ZONE);
        }
    }

    @Autowired
    private LotService service;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void cleanTables() {
        // 场景间清空全部业务表，避免顺序依赖；互斥锁占位行保留。
        jdbc.update("DELETE FROM lot_quarantine");
        jdbc.update("DELETE FROM quarantine_event");
        jdbc.update("DELETE FROM lot_ledger");
        jdbc.update("DELETE FROM lot_link");
        jdbc.update("DELETE FROM transform_io");
        jdbc.update("DELETE FROM transform_record");
        jdbc.update("DELETE FROM lot");
    }

    private String unique(String prefix) {
        return prefix + "_" + seq.incrementAndGet();
    }

    private CreateLotRequest lotReq(String id, String unit, String qty) {
        return new CreateLotRequest(id, unit, new BigDecimal(qty));
    }

    private ParentInput parent(String id, long version, String qty) {
        return new ParentInput(id, version, new BigDecimal(qty));
    }

    private ChildOutput child(String id, String qty) {
        return new ChildOutput(id, new BigDecimal(qty));
    }

    private TransformRequest transform(String requestId, List<ParentInput> parents,
                                       List<ChildOutput> children, String loss, String unit) {
        return new TransformRequest(requestId, parents, children, new BigDecimal(loss), unit);
    }

    // ---------- 建批次与精度 ----------

    @Test
    @DisplayName("建批次：三位小数精度保留，初始余额等于建档量")
    void createLotKeepsThreeDecimalScale() {
        String id = unique("P");
        var view = service.createLot(lotReq(id, "KG", "10.005"));

        assertThat(view.availableQty()).isEqualByComparingTo("10.005");
        assertThat(view.availableQty().scale()).isEqualTo(3);
        assertThat(view.status()).isEqualTo(LotStatus.AVAILABLE);
        assertThat(view.version()).isZero();
        assertThat(view.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(view.activeQuarantineSources()).isEmpty();
    }

    @Test
    @DisplayName("建批次：重复 ID 返回 409")
    void duplicateLotIdConflicts() {
        String id = unique("P");
        service.createLot(lotReq(id, "KG", "1"));
        assertThatThrownBy(() -> service.createLot(lotReq(id, "KG", "2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("建批次：四位小数与非正数量在服务层被拒绝（400），不静默舍入")
    void invalidPrecisionOrNonPositiveRejectedAtService() {
        assertThatThrownBy(() -> service.createLot(lotReq(unique("P"), "KG", "1.0001")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("三位小数");
        assertThatThrownBy(() -> service.createLot(lotReq(unique("P"), "KG", "0.000")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("为正");
    }

    @Test
    @DisplayName("转换：四位小数消费量被拒绝（400）")
    void transformWithFourDecimalQuantityRejected() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10"));
        assertThatThrownBy(() -> service.transform(transform("REQ-PREC",
                List.of(parent(p, 0, "1.0001")), List.of(child(unique("C"), "1.000")),
                "0", "KG")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("三位小数");
    }

    // ---------- 损耗边界 ----------

    @Test
    @DisplayName("损耗边界：差额恰好等于允许损耗时成功")
    void lossExactlyAtLimitSucceeds() {
        String p = unique("P");
        String c = unique("C");
        service.createLot(lotReq(p, "KG", "10.000"));

        TransformResponse resp = service.transform(transform("REQ-LOSS-OK",
                List.of(parent(p, 0, "10.000")), List.of(child(c, "9.000")),
                "1.000", "KG"));

        assertThat(resp.totalConsumed()).isEqualByComparingTo("10.000");
        assertThat(resp.totalProduced()).isEqualByComparingTo("9.000");
        assertThat(resp.loss()).isEqualByComparingTo("1.000");
        assertThat(resp.replayed()).isFalse();
    }

    @Test
    @DisplayName("损耗边界：差额超出允许损耗整笔拒绝（400）")
    void lossExceedsLimitRejected() {
        String p = unique("P");
        String c = unique("C");
        service.createLot(lotReq(p, "KG", "10.000"));

        assertThatThrownBy(() -> service.transform(transform("REQ-LOSS-BAD",
                List.of(parent(p, 0, "10.000")), List.of(child(c, "9.000")),
                "0.999", "KG")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("损耗");

        // 整笔回滚：父批次未消费、子批次未创建。
        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("10.000");
        assertThatThrownBy(() -> service.getLot(c)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("损耗边界：总产出超过总消费拒绝（400）")
    void producedExceedsConsumedRejected() {
        String p = unique("P");
        String c = unique("C");
        service.createLot(lotReq(p, "KG", "10.000"));

        assertThatThrownBy(() -> service.transform(transform("REQ-OUT",
                List.of(parent(p, 0, "10.000")), List.of(child(c, "10.001")),
                "0.000", "KG")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("总产出");
        assertThatThrownBy(() -> service.getLot(c)).isInstanceOf(NotFoundException.class);
    }

    // ---------- 部分消费与超用回滚 ----------

    @Test
    @DisplayName("部分消费：可多次消费至余额为零，版本逐次递增")
    void partialConsumptionAcrossTransforms() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        service.transform(transform("REQ-P1",
                List.of(parent(p, 0, "4.000")), List.of(child(unique("C"), "3.500")),
                "0.500", "KG"));
        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("6.000");

        service.transform(transform("REQ-P2",
                List.of(parent(p, 1, "6.000")), List.of(child(unique("C"), "6.000")),
                "0.000", "KG"));

        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("0.000");
        assertThat(service.getLot(p).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("超用回滚：单笔消费超过可用余额返回 409 且数据不变")
    void overConsumptionRollsBack() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "5.000"));

        assertThatThrownBy(() -> service.transform(transform("REQ-OVER",
                List.of(parent(p, 0, "6.000")), List.of(child(unique("C"), "6.000")),
                "0.000", "KG")))
                .isInstanceOf(ConflictException.class);

        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("5.000");
        assertThat(service.getLot(p).version()).isZero();
    }

    @Test
    @DisplayName("超用回滚：多父转换中一个余额不足，全部父批次回滚、子批次不存在")
    void insufficientOneParentRollsBackWholeTransform() {
        String p1 = unique("P");
        String p2 = unique("P");
        service.createLot(lotReq(p1, "KG", "5.000"));
        service.createLot(lotReq(p2, "KG", "5.000"));
        String c = unique("C");

        assertThatThrownBy(() -> service.transform(transform("REQ-MULTI-OVER",
                List.of(parent(p1, 0, "5.000"), parent(p2, 0, "6.000")),
                List.of(child(c, "10.000")), "1.000", "KG")))
                .isInstanceOf(ConflictException.class);

        assertThat(service.balance(p1).availableQty()).isEqualByComparingTo("5.000");
        assertThat(service.balance(p2).availableQty()).isEqualByComparingTo("5.000");
        assertThatThrownBy(() -> service.getLot(c)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("expectedVersion 过期返回 409")
    void staleExpectedVersionConflicts() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        service.transform(transform("REQ-V1",
                List.of(parent(p, 0, "1.000")), List.of(child(unique("C"), "1.000")),
                "0.000", "KG"));

        assertThatThrownBy(() -> service.transform(transform("REQ-V2",
                List.of(parent(p, 0, "1.000")), List.of(child(unique("C"), "1.000")),
                "0.000", "KG")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本");
    }

    // ---------- 单位、子批次冲突、成环 ----------

    @Test
    @DisplayName("单位不一致整笔拒绝（400）")
    void unitMismatchRejected() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        assertThatThrownBy(() -> service.transform(transform("REQ-UNIT",
                List.of(parent(p, 0, "1.000")), List.of(child(unique("C"), "1.000")),
                "0.000", "L")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("单位");
    }

    @Test
    @DisplayName("子批次 ID 与现有批次冲突返回 409 并整笔回滚")
    void childIdConflictRollsBack() {
        String p = unique("P");
        String existing = unique("C");
        service.createLot(lotReq(p, "KG", "10.000"));
        service.createLot(lotReq(existing, "KG", "1.000"));

        assertThatThrownBy(() -> service.transform(transform("REQ-CHILD-DUP",
                List.of(parent(p, 0, "2.000")), List.of(child(existing, "2.000")),
                "0.000", "KG")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已存在");

        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("10.000");
    }

    // ---------- 请求指纹幂等 ----------

    @Test
    @DisplayName("请求指纹：同键同完整参数重放首次结果")
    void sameRequestSameParamsReplaysFirstResult() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        TransformRequest req = transform("REQ-IDEM",
                List.of(parent(p, 0, "3.000")), List.of(child(unique("C"), "2.500")),
                "0.500", "KG");
        TransformResponse first = service.transform(req);
        TransformResponse second = service.transform(req);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.totalConsumed()).isEqualTo(first.totalConsumed());
        assertThat(second.childLotIds()).isEqualTo(first.childLotIds());
        // 重放不重复消费。
        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("7.000");
        Integer ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_ledger WHERE lot_id = ? AND change_type = 'CONSUME'",
                Integer.class, p);
        assertThat(ledgerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("请求指纹：同键改数量返回 409 且不产生副作用")
    void sameRequestDifferentQuantityConflicts() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        service.transform(transform("REQ-FP1",
                List.of(parent(p, 0, "3.000")), List.of(child(unique("C"), "2.500")),
                "0.500", "KG"));

        assertThatThrownBy(() -> service.transform(transform("REQ-FP1",
                List.of(parent(p, 1, "3.500")), List.of(child(unique("C"), "3.500")),
                "0.000", "KG")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");
        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("7.000");
    }

    @Test
    @DisplayName("请求指纹：同键仅改允许损耗也返回 409")
    void sameRequestDifferentLossConflicts() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        service.transform(transform("REQ-FP2",
                List.of(parent(p, 0, "3.000")), List.of(child(unique("C"), "2.500")),
                "0.500", "KG"));

        assertThatThrownBy(() -> service.transform(transform("REQ-FP2",
                List.of(parent(p, 1, "3.000")), List.of(child(unique("C"), "2.500")),
                "0.600", "KG")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("请求指纹：同键改 expectedVersion 返回 409")
    void sameRequestDifferentVersionConflicts() {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        service.transform(transform("REQ-FP3",
                List.of(parent(p, 0, "3.000")), List.of(child(unique("C"), "2.500")),
                "0.500", "KG"));

        assertThatThrownBy(() -> service.transform(transform("REQ-FP3",
                List.of(parent(p, 1, "3.000")), List.of(child(unique("C"), "2.500")),
                "0.500", "KG")))
                .isInstanceOf(ConflictException.class);
    }

    // ---------- 谱系查询 ----------

    @Test
    @DisplayName("谱系：多父合并再拆分，传递性祖先与后代完整")
    void genealogyAcrossMergeAndSplit() {
        String a = unique("A");
        String b = unique("B");
        String c = unique("C");
        String d = unique("D");
        String e = unique("E");
        String f = unique("F");
        service.createLot(lotReq(a, "KG", "10"));
        service.createLot(lotReq(b, "KG", "10"));
        service.createLot(lotReq(d, "KG", "10"));

        // A+B -> C（合并）
        service.transform(transform("REQ-G1",
                List.of(parent(a, 0, "4"), parent(b, 0, "4")),
                List.of(child(c, "8")), "0", "KG"));
        // C+D -> E,F（再拆分）
        service.transform(transform("REQ-G2",
                List.of(parent(c, 0, "8"), parent(d, 0, "2")),
                List.of(child(e, "5"), child(f, "5")), "0", "KG"));

        GenealogyView viewE = service.genealogy(e);
        assertThat(viewE.ancestors()).containsExactlyInAnyOrder(a, b, c, d);
        GenealogyView viewA = service.genealogy(a);
        assertThat(viewA.descendants()).containsExactlyInAnyOrder(c, e, f);
        GenealogyView viewC = service.genealogy(c);
        assertThat(viewC.descendants()).containsExactlyInAnyOrder(e, f);
        assertThat(service.genealogy(a).ancestors()).isEmpty();
    }

    // ---------- 多来源隔离与解除次序 ----------

    @Test
    @DisplayName("多来源隔离：SET 传播全部后代并记录来源集合")
    void quarantineSetPropagatesToAllDescendants() {
        String a = unique("A");
        String b = unique("B");
        String c = unique("C");
        service.createLot(lotReq(a, "KG", "10"));
        service.createLot(lotReq(b, "KG", "10"));
        service.transform(transform("REQ-Q0",
                List.of(parent(a, 0, "4"), parent(b, 0, "4")),
                List.of(child(c, "8")), "0", "KG"));

        QuarantineResponse resp = service.quarantine(new QuarantineRequest(a, "SET", "抽检异常"));

        assertThat(resp.lotStatus()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(resp.affectedLotIds()).containsExactlyInAnyOrder(a, c);
        assertThat(service.getLot(a).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(c).activeQuarantineSources()).containsExactly(a);
        assertThat(service.getLot(b).status()).isEqualTo(LotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("多来源隔离：解除一个来源不得提前放行，全部解除才 AVAILABLE")
    void releaseOrderKeepsLotQuarantinedUntilAllSourcesCleared() {
        String a = unique("A");
        String b = unique("B");
        String c = unique("C");
        String d = unique("D");
        service.createLot(lotReq(a, "KG", "10"));
        service.createLot(lotReq(b, "KG", "10"));
        service.transform(transform("REQ-Q1",
                List.of(parent(a, 0, "4"), parent(b, 0, "4")),
                List.of(child(c, "8")), "0", "KG"));
        service.transform(transform("REQ-Q2",
                List.of(parent(c, 0, "8")),
                List.of(child(d, "8")), "0", "KG"));

        // 两个祖先来源先后隔离，C、D 同时携带两个来源。
        service.quarantine(new QuarantineRequest(a, "SET", null));
        service.quarantine(new QuarantineRequest(b, "SET", null));
        assertThat(service.getLot(c).activeQuarantineSources()).containsExactlyInAnyOrder(a, b);
        assertThat(service.getLot(d).activeQuarantineSources()).containsExactlyInAnyOrder(a, b);

        // 只解除 A：C、D 仍因 B 隔离。
        service.quarantine(new QuarantineRequest(a, "RELEASE", null));
        assertThat(service.getLot(a).status()).isEqualTo(LotStatus.AVAILABLE);
        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(d).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(c).activeQuarantineSources()).containsExactly(b);

        // 解除 B 后全部恢复。
        service.quarantine(new QuarantineRequest(b, "RELEASE", null));
        assertThat(service.getLot(b).status()).isEqualTo(LotStatus.AVAILABLE);
        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.AVAILABLE);
        assertThat(service.getLot(d).status()).isEqualTo(LotStatus.AVAILABLE);
        assertThat(service.getLot(d).activeQuarantineSources()).isEmpty();
    }

    @Test
    @DisplayName("隔离幂等：重复 SET 不重复记录来源、不产生额外事件之外的状态翻转")
    void repeatedSetIsIdempotent() {
        String a = unique("A");
        service.createLot(lotReq(a, "KG", "10"));
        service.quarantine(new QuarantineRequest(a, "SET", null));
        service.quarantine(new QuarantineRequest(a, "SET", null));

        Integer sourceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_quarantine WHERE lot_id = ? AND source_lot_id = ?",
                Integer.class, a, a);
        assertThat(sourceCount).isEqualTo(1);
    }

    @Test
    @DisplayName("影响面查询：返回携带该来源的全部批次及各自来源集合")
    void impactQueryListsCarriedLots() {
        String a = unique("A");
        String b = unique("B");
        String c = unique("C");
        service.createLot(lotReq(a, "KG", "10"));
        service.createLot(lotReq(b, "KG", "10"));
        service.transform(transform("REQ-QI",
                List.of(parent(a, 0, "4"), parent(b, 0, "4")),
                List.of(child(c, "8")), "0", "KG"));
        service.quarantine(new QuarantineRequest(a, "SET", null));
        service.quarantine(new QuarantineRequest(b, "SET", null));

        ImpactView impact = service.impact(a);
        assertThat(impact.quarantined()).isTrue();
        assertThat(impact.affectedLots()).extracting(ImpactView.AffectedLot::lotId)
                .containsExactlyInAnyOrder(a, c);
        ImpactView.AffectedLot cImpact = impact.affectedLots().stream()
                .filter(x -> x.lotId().equals(c)).findFirst().orElseThrow();
        assertThat(cImpact.activeSources()).containsExactlyInAnyOrder(a, b);
    }

    // ---------- 隔离与转换的提交顺序 ----------

    @Test
    @DisplayName("顺序一：隔离先提交，之后转换的新子批次出生即隔离并携带来源")
    void quarantineBeforeTransformAffectsNewChildren() {
        String p = unique("P");
        String c = unique("C");
        service.createLot(lotReq(p, "KG", "10"));
        service.quarantine(new QuarantineRequest(p, "SET", null));

        service.transform(transform("REQ-QBT",
                List.of(parent(p, 0, "4")), List.of(child(c, "4")), "0", "KG"));

        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(c).activeQuarantineSources()).containsExactly(p);
    }

    @Test
    @DisplayName("出生继承：两个父批次各自隔离后合并，子批次出生即携带两个来源的并集")
    void childBornWithUnionOfParentSources() {
        String a = unique("A");
        String b = unique("B");
        String c = unique("C");
        service.createLot(lotReq(a, "KG", "10"));
        service.createLot(lotReq(b, "KG", "10"));
        service.quarantine(new QuarantineRequest(a, "SET", null));
        service.quarantine(new QuarantineRequest(b, "SET", null));

        service.transform(transform("REQ-UNION-BIRTH",
                List.of(parent(a, 0, "4"), parent(b, 0, "4")),
                List.of(child(c, "8")), "0", "KG"));

        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(c).activeQuarantineSources())
                .containsExactlyInAnyOrder(a, b);

        // 只解除 a，c 仍因 b 隔离；全部解除才放行。
        service.quarantine(new QuarantineRequest(a, "RELEASE", null));
        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.QUARANTINED);
        service.quarantine(new QuarantineRequest(b, "RELEASE", null));
        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("顺序二：转换先提交，之后隔离传播必须包含已产出的子批次")
    void transformBeforeQuarantinePropagatesToExistingChildren() {
        String p = unique("P");
        String c = unique("C");
        service.createLot(lotReq(p, "KG", "10"));
        service.transform(transform("REQ-QTB",
                List.of(parent(p, 0, "4")), List.of(child(c, "4")), "0", "KG"));
        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.AVAILABLE);

        service.quarantine(new QuarantineRequest(p, "SET", null));

        assertThat(service.getLot(c).status()).isEqualTo(LotStatus.QUARANTINED);
        assertThat(service.getLot(c).activeQuarantineSources()).containsExactly(p);
    }

    @Test
    @DisplayName("转换竞态：两笔转换并发消费同一父批次超用，恰好一笔成功一笔 409，余额与台账一致")
    void concurrentTransformsCannotOverConsume() throws Exception {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        String c1 = unique("C");
        String c2 = unique("C");

        TransformRequest req1 = transform("REQ-RACE-1",
                List.of(parent(p, 0, "6.000")), List.of(child(c1, "6.000")), "0", "KG");
        TransformRequest req2 = transform("REQ-RACE-2",
                List.of(parent(p, 0, "6.000")), List.of(child(c2, "6.000")), "0", "KG");

        runConcurrently(
                () -> service.transform(req1),
                () -> service.transform(req2));

        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("4.000");
        assertThat(service.getLot(p).version()).isEqualTo(1);
        boolean c1Exists = getLot0(c1) != null;
        boolean c2Exists = getLot0(c2) != null;
        assertThat(c1Exists).as("恰好一个子批次被创建").isNotEqualTo(c2Exists);
        Integer consumeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lot_ledger WHERE lot_id = ? AND change_type = 'CONSUME'",
                Integer.class, p);
        assertThat(consumeRows).isEqualTo(1);
    }

    @Test
    @DisplayName("转换与隔离真实并发：无论谁先提交，最终子批次必隔离且携带来源、消费台账准确")
    void concurrentTransformAndQuarantineReachConsistentState() throws Exception {
        // 重复多轮以覆盖两种提交顺序；每轮使用全新批次。
        for (int round = 0; round < 5; round++) {
            String pp = unique("P");
            String cc = unique("C");
            service.createLot(lotReq(pp, "KG", "10.000"));
            TransformRequest r = transform("REQ-RACE-Q-" + round,
                    List.of(parent(pp, 0, "5.000")), List.of(child(cc, "5.000")), "0", "KG");

            runConcurrently(
                    () -> service.transform(r),
                    () -> service.quarantine(new QuarantineRequest(pp, "SET", null)));

            assertThat(service.getLot(pp).status())
                    .as("第 %d 轮父批次隔离", round).isEqualTo(LotStatus.QUARANTINED);
            assertThat(service.getLot(cc).status())
                    .as("第 %d 轮子批次无论提交顺序均隔离", round)
                    .isEqualTo(LotStatus.QUARANTINED);
            assertThat(service.getLot(cc).activeQuarantineSources())
                    .as("第 %d 轮子批次携带父批次来源", round).containsExactly(pp);
            assertThat(service.balance(pp).availableQty())
                    .as("第 %d 轮消费台账准确", round).isEqualByComparingTo("5.000");
        }
    }

    // ---------- 辅助 ----------

    @Test
    @DisplayName("幂等并发：同键同参并发提交，恰好一次真实执行、另一次重放，消费只发生一次")
    void concurrentSameRequestExecutesOnce() throws Exception {
        String p = unique("P");
        service.createLot(lotReq(p, "KG", "10.000"));
        TransformRequest req = transform("REQ-IDEM-RACE",
                List.of(parent(p, 0, "3.000")), List.of(child(unique("C"), "3.000")),
                "0", "KG");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<TransformResponse> responses = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        responses.add(service.transform(req));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(responses).hasSize(2);
        assertThat(responses.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(responses.stream().filter(TransformResponse::replayed).count()).isEqualTo(1);
        assertThat(service.balance(p).availableQty()).isEqualByComparingTo("7.000");
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transform_record WHERE request_id = ?",
                Integer.class, "REQ-IDEM-RACE");
        assertThat(records).isEqualTo(1);
    }

    /** getLot 的不抛异常版本，用于并发后判断子批次是否存在。 */
    private com.example.starter.lot.api.LotView getLot0(String id) {
        try {
            return service.getLot(id);
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * 两个业务操作真实并发执行（独立线程、各自事务），统计成功与业务冲突次数。
     */
    private void runConcurrently(Runnable op1, Runnable op2) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> executeSynced(op1, ready, start, success, conflict)),
                    pool.submit(() -> executeSynced(op2, ready, start, success, conflict)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(success.get() + conflict.get()).isEqualTo(2);
    }

    private void executeSynced(Runnable op, CountDownLatch ready, CountDownLatch start,
                               AtomicInteger success, AtomicInteger conflict) {
        try {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            op.run();
            success.incrementAndGet();
        } catch (ConflictException | BadRequestException e) {
            conflict.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
