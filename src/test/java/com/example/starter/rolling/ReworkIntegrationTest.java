package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.PhysicalRollRegisterRequest;
import com.example.starter.rolling.api.dto.PhysicalRollView;
import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollLineageView;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.api.dto.RollView;
import com.example.starter.rolling.api.dto.ReworkApplicationRequest;
import com.example.starter.rolling.api.dto.ReworkApplicationView;
import com.example.starter.rolling.api.dto.ReworkInspectionRequest;
import com.example.starter.rolling.service.BusinessException;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.RollLifeService;
import com.example.starter.rolling.service.ReworkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 到期轧辊返修放行 H2 事务/并发测试：物理辊登记、返修申请前置与参数边界、
 * 两名检验员放行/终止、乐观版本、并发双建只产生一代、迟到旧实绩、幂等、两代返修上限。
 */
class ReworkIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private ReworkService reworkService;

    @Autowired
    private RollLifeService rollLifeService;

    /** 上机、报满恰达 10 公里上限（禁排）并日冻结，返回冻结后台账。 */
    private RollView prepareExpiredFrozenRoll(String rollNo) {
        rollLifeService.mount(new RollMountRequest("mount-" + rollNo, rollNo,
                new BigDecimal("10"), T0));
        rollLifeService.reportActual(new RollActualRequest(
                "km1-" + rollNo, rollNo, new BigDecimal("6"), T0.plusHours(1)));
        rollLifeService.reportActual(new RollActualRequest(
                "km2-" + rollNo, rollNo, new BigDecimal("4"), T0.plusHours(2)));
        RollView banned = rollLifeService.get(rollNo);
        assertThat(banned.status()).isEqualTo("BANNED");
        return rollLifeService.freeze(rollNo, new RollFreezeRequest("fz-" + rollNo));
    }

    private PhysicalRollView register(String requestId, String physicalNo, String rollNo) {
        return reworkService.registerPhysicalRoll(
                new PhysicalRollRegisterRequest(requestId, physicalNo, rollNo));
    }

    private ReworkApplicationRequest reworkRequest(String requestId, String physicalNo,
                                                   String oldRollNo, String newRollNo,
                                                   String diameter, String newLimit,
                                                   LocalDateTime mountedAt) {
        return new ReworkApplicationRequest(requestId, physicalNo, oldRollNo, newRollNo,
                new BigDecimal(diameter), new BigDecimal(newLimit), "CERT-" + requestId, mountedAt);
    }

    private ReworkApplicationView apply(String requestId, String physicalNo, String oldRollNo,
                                        String newRollNo, String diameter, String newLimit) {
        return reworkService.apply(reworkRequest(requestId, physicalNo, oldRollNo, newRollNo,
                diameter, newLimit, T0.plusDays(1)));
    }

    private ReworkApplicationView inspect(long applicationId, String requestId, String inspector,
                                          boolean qualified, int expectedVersion) {
        return reworkService.inspect(applicationId, new ReworkInspectionRequest(
                requestId, inspector, qualified, expectedVersion));
    }

    /** 两名不同检验员均合格放行，返回放行视图。 */
    private ReworkApplicationView releaseByTwoInspectors(ReworkApplicationView application,
                                                         String first, String second) {
        inspect(application.id(), "in1-" + application.id(), first, true, 1);
        return inspect(application.id(), "in2-" + application.id(), second, true, 2);
    }

    @Test
    void 物理辊首代绑定已有辊号且重复登记拒绝() {
        prepareExpiredFrozenRoll("R-REG");

        PhysicalRollView view = register("reg-1", "P-REG", "R-REG");
        assertThat(view.physicalNo()).isEqualTo("P-REG");
        assertThat(view.headRollNo()).isEqualTo("R-REG");
        assertThat(view.headGenerationNo()).isEqualTo(1);
        assertThat(view.reworkCount()).isZero();
        assertThat(view.version()).isEqualTo(1);

        // 物理辊编号重复。
        assertThatThrownBy(() -> register("reg-2", "P-REG", "R-REG"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已登记");
        // 辊号重复绑定其他物理辊。
        assertThatThrownBy(() -> register("reg-3", "P-OTHER", "R-REG"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已绑定物理辊");
        // 绑定未上机的辊号。
        assertThatThrownBy(() -> register("reg-4", "P-NONE", "R-MISSING"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未在寿命台账登记");
    }

    @Test
    void 双检合格原子建立下一代且新辊里程从零起沿用旧入口() {
        prepareExpiredFrozenRoll("R1-MAIN");
        register("reg-main", "P-MAIN", "R1-MAIN");

        ReworkApplicationView application = apply(
                "rw-main", "P-MAIN", "R1-MAIN", "R2-MAIN", "510.00", "8");
        assertThat(application.status()).isEqualTo("INSPECTING");
        assertThat(application.version()).isEqualTo(1);

        ReworkApplicationView afterFirst = inspect(application.id(), "rw-in1", "QA-A", true, 1);
        assertThat(afterFirst.status()).isEqualTo("INSPECTING");
        assertThat(afterFirst.firstInspector()).isEqualTo("QA-A");
        assertThat(afterFirst.firstQualified()).isTrue();
        assertThat(afterFirst.version()).isEqualTo(2);

        ReworkApplicationView released = inspect(application.id(), "rw-in2", "QA-B", true, 2);
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.secondInspector()).isEqualTo("QA-B");
        assertThat(released.releasedAt()).isNotNull();
        assertThat(released.newGenerationNo()).isEqualTo(2);
        assertThat(released.version()).isEqualTo(3);

        // 新代独立台账：累计里程从 0 起，使用新上限，旧辊号台账原封不动。
        RollView newRoll = rollLifeService.get("R2-MAIN");
        assertThat(newRoll.status()).isEqualTo("IN_SERVICE");
        assertThat(newRoll.totalKm()).isEqualByComparingTo("0");
        assertThat(newRoll.cycleLimitKm()).isEqualByComparingTo("8");
        assertThat(newRoll.coilsSinceMount()).isZero();
        assertThat(newRoll.frozenAt()).isNull();

        RollView oldRoll = rollLifeService.get("R1-MAIN");
        assertThat(oldRoll.status()).isEqualTo("BANNED");
        assertThat(oldRoll.totalKm()).isEqualByComparingTo("10");
        assertThat(oldRoll.cycleLimitKm()).isEqualByComparingTo("10");
        assertThat(oldRoll.frozenAt()).isNotNull();

        // 代次链与各代独立台账同时展示。
        RollLineageView lineage = reworkService.lineage("P-MAIN");
        assertThat(lineage.physical().headRollNo()).isEqualTo("R2-MAIN");
        assertThat(lineage.physical().reworkCount()).isEqualTo(1);
        assertThat(lineage.generations()).hasSize(2);
        assertThat(lineage.generations().get(0).rollNo()).isEqualTo("R1-MAIN");
        assertThat(lineage.generations().get(0).generationNo()).isEqualTo(1);
        assertThat(lineage.generations().get(0).diameterMm()).isNull();
        assertThat(lineage.generations().get(0).successorApplicationId()).isEqualTo(application.id());
        assertThat(lineage.generations().get(1).rollNo()).isEqualTo("R2-MAIN");
        assertThat(lineage.generations().get(1).diameterMm()).isEqualByComparingTo("510.00");
        assertThat(lineage.ledgers()).hasSize(2);
        assertThat(lineage.ledgers().get(0).rollNo()).isEqualTo("R1-MAIN");
        assertThat(lineage.ledgers().get(0).totalKm()).isEqualByComparingTo("10");
        assertThat(lineage.ledgers().get(1).rollNo()).isEqualTo("R2-MAIN");
        assertThat(lineage.ledgers().get(1).totalKm()).isEqualByComparingTo("0");

        // 新辊继续走旧入口：前 5 卷烫辊首用，里程独立累加。
        LocalDateTime mountedAt = T0.plusDays(1);
        for (int i = 1; i <= 6; i++) {
            RollActualView view = rollLifeService.reportActual(new RollActualRequest(
                    "new-km-" + i, "R2-MAIN", new BigDecimal("1"), mountedAt.plusHours(i)));
            assertThat(view.firstUse()).as("新代第 %d 卷首用标记", i).isEqualTo(i <= 5);
        }
        assertThat(rollLifeService.get("R2-MAIN").totalKm()).isEqualByComparingTo("6");

        // 新代到期同样禁排。
        RollActualView atLimit = rollLifeService.reportActual(new RollActualRequest(
                "new-km-7", "R2-MAIN", new BigDecimal("2"), mountedAt.plusHours(7)));
        assertThat(atLimit.banned()).isTrue();
        assertThat(rollLifeService.get("R2-MAIN").status()).isEqualTo("BANNED");
        // 旧代结论不受新代使用影响。
        assertThat(rollLifeService.get("R1-MAIN").totalKm()).isEqualByComparingTo("10");
    }

    @Test
    void 磨后直径边界校验() {
        prepareExpiredFrozenRoll("R1-D");
        register("reg-d", "P-D", "R1-D");

        // 少于 500 毫米拒绝。
        assertThatThrownBy(() -> apply("rw-d1", "P-D", "R1-D", "R2-D1", "499.99", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("少于 500");
        // 超过两位小数拒绝。
        assertThatThrownBy(() -> apply("rw-d2", "P-D", "R1-D", "R2-D2", "500.123", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("两位小数");
        // 恰 500.00 毫米允许放行。
        ReworkApplicationView application = apply("rw-d3", "P-D", "R1-D", "R2-D3", "500.00", "8");
        ReworkApplicationView released = releaseByTwoInspectors(application, "QA-A", "QA-B");
        assertThat(released.status()).isEqualTo("RELEASED");
        RollLineageView lineage = reworkService.lineage("P-D");
        assertThat(lineage.generations().get(1).diameterMm()).isEqualByComparingTo("500.00");
    }

    @Test
    void 新上限不得超过上一代且直径不得比上一代大() {
        // 第一次返修：直径 510、新上限 8。
        prepareExpiredFrozenRoll("R1-L");
        register("reg-l", "P-L", "R1-L");
        ReworkApplicationView first = apply("rw-l1", "P-L", "R1-L", "R2-L", "510.00", "8");
        releaseByTwoInspectors(first, "QA-A", "QA-B");

        // 新代跑满 8 公里禁排并冻结，准备第二次返修。
        LocalDateTime mountedAt = T0.plusDays(1);
        rollLifeService.reportActual(new RollActualRequest(
                "r2-km", "R2-L", new BigDecimal("8"), mountedAt.plusHours(1)));
        rollLifeService.freeze("R2-L", new RollFreezeRequest("fz-R2-L"));

        // 新上限超过上一代上限 8 拒绝。
        assertThatThrownBy(() -> apply("rw-l2-badlimit", "P-L", "R2-L", "R3-L0",
                "505.00", "8.001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得超过上一代上限");
        // 磨后直径比上一代 510 大拒绝。
        assertThatThrownBy(() -> apply("rw-l2-baddia", "P-L", "R2-L", "R3-L1",
                "510.01", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得比上一代直径");
        // 直径与上代相等、上限相等允许。
        ReworkApplicationView second = apply("rw-l2-ok", "P-L", "R2-L", "R3-L", "510.00", "8");
        ReworkApplicationView released = releaseByTwoInspectors(second, "QA-C", "QA-D");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.newGenerationNo()).isEqualTo(3);
        assertThat(reworkService.lineage("P-L").physical().reworkCount()).isEqualTo(2);
    }

    @Test
    void 新辊号占用与跨物理辊引用拒绝() {
        prepareExpiredFrozenRoll("R1-X");
        prepareExpiredFrozenRoll("R1-Y");
        register("reg-x", "P-X", "R1-X");
        register("reg-y", "P-Y", "R1-Y");

        // 新辊号已被寿命台账占用。
        assertThatThrownBy(() -> apply("rw-x1", "P-X", "R1-X", "R1-Y", "505.00", "8"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已被占用");

        // 跨物理辊引用：旧辊号属于 P-Y。
        assertThatThrownBy(() -> apply("rw-x2", "P-X", "R1-Y", "R2-X", "505.00", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("跨物理辊引用拒绝");
    }

    @Test
    void 未到寿命上限或未冻结不得申请返修() {
        // 在役未到限、未冻结。
        rollLifeService.mount(new RollMountRequest("mount-R1-U", "R1-U",
                new BigDecimal("10"), T0));
        rollLifeService.reportActual(new RollActualRequest(
                "u-km", "R1-U", new BigDecimal("6"), T0.plusHours(1)));
        register("reg-u", "P-U", "R1-U");
        assertThatThrownBy(() -> apply("rw-u1", "P-U", "R1-U", "R2-U", "505.00", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未达到寿命上限");

        // 到限禁排但未冻结。
        prepareExpiredFrozenRollHelperWithoutFreeze();
    }

    /** 到限禁排但未冻结的独立场景。 */
    private void prepareExpiredFrozenRollHelperWithoutFreeze() {
        rollLifeService.mount(new RollMountRequest("mount-R1-NF", "R1-NF",
                new BigDecimal("10"), T0));
        rollLifeService.reportActual(new RollActualRequest(
                "nf-km1", "R1-NF", new BigDecimal("10"), T0.plusHours(1)));
        register("reg-nf", "P-NF", "R1-NF");
        assertThat(rollLifeService.get("R1-NF").status()).isEqualTo("BANNED");
        assertThatThrownBy(() -> apply("rw-nf1", "P-NF", "R1-NF", "R2-NF", "505.00", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未日冻结");
    }

    @Test
    void 同一检验员不得两次确认() {
        prepareExpiredFrozenRoll("R1-I");
        register("reg-i", "P-I", "R1-I");
        ReworkApplicationView application = apply("rw-i", "P-I", "R1-I", "R2-I", "505.00", "8");
        inspect(application.id(), "i-1", "QA-SAME", true, 1);

        assertThatThrownBy(() -> inspect(application.id(), "i-2", "QA-SAME", true, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("两名检验员必须不同");
        // 被拒绝的确认不改变状态与版本。
        ReworkApplicationView unchanged = reworkService.getApplication(application.id());
        assertThat(unchanged.status()).isEqualTo("INSPECTING");
        assertThat(unchanged.version()).isEqualTo(2);
        assertThat(unchanged.secondInspector()).isNull();
    }

    @Test
    void 第一检不合格即终止不建新辊且可另提新申请() {
        prepareExpiredFrozenRoll("R1-T1");
        register("reg-t1", "P-T1", "R1-T1");
        ReworkApplicationView rejected = apply(
                "rw-t1-bad", "P-T1", "R1-T1", "R2-T1-BAD", "505.00", "8");
        ReworkApplicationView afterBad = inspect(rejected.id(), "t1-in1", "QA-A", false, 1);
        assertThat(afterBad.status()).isEqualTo("TERMINATED");
        assertThat(afterBad.terminatedAt()).isNotNull();
        assertThat(afterBad.firstQualified()).isFalse();
        assertThat(afterBad.version()).isEqualTo(2);

        // 终止不建新辊：无新代登记、无新台账。
        assertThat(rollExists("R2-T1-BAD")).isFalse();
        assertThat(reworkService.lineage("P-T1").generations()).hasSize(1);

        // 已终止申请不得继续检验。
        assertThatThrownBy(() -> inspect(rejected.id(), "t1-in2", "QA-B", true, 2))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已终止");

        // 终止后可另提新申请并成功放行。
        ReworkApplicationView retry = apply(
                "rw-t1-ok", "P-T1", "R1-T1", "R2-T1", "505.00", "8");
        ReworkApplicationView released = releaseByTwoInspectors(retry, "QA-C", "QA-D");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(rollLifeService.get("R2-T1").status()).isEqualTo("IN_SERVICE");
        assertThat(reworkService.lineage("P-T1").physical().reworkCount()).isEqualTo(1);
    }

    @Test
    void 第二检不合格终止不建新辊() {
        prepareExpiredFrozenRoll("R1-T2");
        register("reg-t2", "P-T2", "R1-T2");
        ReworkApplicationView application = apply(
                "rw-t2", "P-T2", "R1-T2", "R2-T2", "505.00", "8");
        inspect(application.id(), "t2-in1", "QA-A", true, 1);
        ReworkApplicationView afterBad = inspect(application.id(), "t2-in2", "QA-B", false, 2);
        assertThat(afterBad.status()).isEqualTo("TERMINATED");
        assertThat(afterBad.secondQualified()).isFalse();
        assertThat(afterBad.terminatedAt()).isNotNull();
        assertThat(rollExists("R2-T2")).isFalse();
        assertThat(reworkService.lineage("P-T2").generations()).hasSize(1);
    }

    @Test
    void 乐观锁旧版本返回409成功后版本递增() {
        prepareExpiredFrozenRoll("R1-V");
        register("reg-v", "P-V", "R1-V");
        ReworkApplicationView application = apply("rw-v", "P-V", "R1-V", "R2-V", "505.00", "8");

        // 第一次确认带旧版本 0 拒绝。
        assertThatThrownBy(() -> inspect(application.id(), "v-bad1", "QA-A", true, 0))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expectedVersion");
        // 首次确认成功，版本 1→2。
        inspect(application.id(), "v-in1", "QA-A", true, 1);
        // 第二次确认仍带旧版本 1 拒绝。
        assertThatThrownBy(() -> inspect(application.id(), "v-bad2", "QA-B", true, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("聚合版本已变化");
        // 状态未被错误版本推进。
        assertThat(reworkService.getApplication(application.id()).secondInspector()).isNull();
        // 正确版本放行成功，版本 2→3。
        ReworkApplicationView released = inspect(application.id(), "v-in2", "QA-B", true, 2);
        assertThat(released.version()).isEqualTo(3);
        assertThat(released.status()).isEqualTo("RELEASED");
    }

    @Test
    void 并发双建放行只产生一代() throws Exception {
        prepareExpiredFrozenRoll("R1-C");
        register("reg-c", "P-C", "R1-C");

        // 并发提交两笔返修申请（不同 requestId/新辊号），均可进入检验中。
        List<ReworkApplicationView> applications = applyConcurrent(
                reworkRequest("rw-c1", "P-C", "R1-C", "R2-C1", "505.00", "8", T0.plusDays(1)),
                reworkRequest("rw-c2", "P-C", "R1-C", "R2-C2", "506.00", "8", T0.plusDays(1)));
        assertThat(applications).hasSize(2);
        assertThat(applications).allMatch(a -> a.status().equals("INSPECTING"));

        // 各自完成第一名检验员确认。
        inspect(applications.get(0).id(), "c-in1a", "QA-A", true, 1);
        inspect(applications.get(1).id(), "c-in1b", "QA-C", true, 1);

        // 并发第二检验员放行：数据库唯一裁决，只允许一个成功后继。
        List<ReleaseOutcome> outcomes = releaseConcurrent(
                applications.get(0).id(), "QA-B",
                applications.get(1).id(), "QA-D");

        long successCount = outcomes.stream().filter(o -> o.view != null).count();
        long conflictCount = outcomes.stream().filter(o -> o.conflict).count();
        assertThat(successCount).as("恰好一笔放行成功").isEqualTo(1);
        assertThat(conflictCount).as("另一笔并发双建 409").isEqualTo(1);

        // 只产生一代：代次链仍为 2 代，新辊号恰好建立一个。
        RollLineageView lineage = reworkService.lineage("P-C");
        assertThat(lineage.generations()).hasSize(2);
        assertThat(lineage.physical().headGenerationNo()).isEqualTo(2);
        assertThat(lineage.physical().reworkCount()).isEqualTo(1);
        int newRollCount = jdbc.queryForObject("""
                SELECT COUNT(1) FROM roll_life WHERE roll_no IN ('R2-C1','R2-C2')
                """, Integer.class);
        assertThat(newRollCount).isEqualTo(1);

        // 失败申请回滚后仍是检验中，重试仍因成功后继已存在被 409，不再产生新一代。
        long failedId = outcomes.get(0).view == null
                ? applications.get(0).id() : applications.get(1).id();
        ReworkApplicationView failedRow = reworkService.getApplication(failedId);
        assertThat(failedRow.status()).isEqualTo("INSPECTING");
        assertThatThrownBy(() -> inspect(failedId, "c-retry", "QA-E", true, 2))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("成功后继");
        assertThat(reworkService.lineage("P-C").generations()).hasSize(2);

        // 成功申请重复检验 409。
        long successId = outcomes.get(0).view != null
                ? applications.get(0).id() : applications.get(1).id();
        assertThatThrownBy(() -> inspect(successId, "c-again", "QA-F", true, 3))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已放行");
    }

    @Test
    void 放行后迟到旧实绩只落旧代证据不影响新代() {
        prepareExpiredFrozenRoll("R1-LATE");
        register("reg-late", "P-LATE", "R1-LATE");
        ReworkApplicationView application = apply(
                "rw-late", "P-LATE", "R1-LATE", "R2-LATE", "505.00", "8");
        releaseByTwoInspectors(application, "QA-A", "QA-B");

        // 向旧代辊号回报：旧代已冻结，只登记迟到事件，不改旧代里程与禁排结论。
        RollActualView late = rollLifeService.reportActual(new RollActualRequest(
                "late-old", "R1-LATE", new BigDecimal("5"), T0.plusHours(9)));
        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isFalse();
        assertThat(late.totalKm()).isEqualByComparingTo("10");
        assertThat(rollLifeService.get("R1-LATE").totalKm()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = 'R1-LATE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT rolling_km FROM roll_late_event WHERE roll_no = 'R1-LATE'",
                BigDecimal.class)).isEqualByComparingTo("5");

        // 新代里程与实绩不受迟到旧实绩影响。
        assertThat(rollLifeService.get("R2-LATE").totalKm()).isEqualByComparingTo("0");
        RollActualView newActual = rollLifeService.reportActual(new RollActualRequest(
                "new-ok", "R2-LATE", new BigDecimal("3"), T0.plusDays(1).plusHours(1)));
        assertThat(newActual.accepted()).isTrue();
        assertThat(newActual.totalKm()).isEqualByComparingTo("3");
        assertThat(rollLifeService.get("R1-LATE").totalKm()).isEqualByComparingTo("10");
    }

    @Test
    void 申请与检验幂等同键同参重放异参409失败不占键() {
        prepareExpiredFrozenRoll("R1-IDEM");
        register("reg-idem", "P-IDEM", "R1-IDEM");

        // 失败的申请不占幂等键：同 requestId 改正参数后成功。
        assertThatThrownBy(() -> apply("rw-idem", "P-IDEM", "R1-IDEM", "R2-IDEM", "499.00", "8"))
                .isInstanceOf(BusinessException.class);
        ReworkApplicationRequest valid = reworkRequest(
                "rw-idem", "P-IDEM", "R1-IDEM", "R2-IDEM", "505.00", "8", T0.plusDays(1));
        ReworkApplicationView first = reworkService.apply(valid);
        ReworkApplicationView replay = reworkService.apply(valid);
        assertThat(replay).isEqualTo(first);

        // 同键异参 409 且不新增申请。
        assertThatThrownBy(() -> reworkService.apply(reworkRequest(
                "rw-idem", "P-IDEM", "R1-IDEM", "R2-OTHER", "505.00", "8", T0.plusDays(1))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("rw-idem");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM rework_application WHERE physical_no = 'P-IDEM'",
                Integer.class)).isEqualTo(1);

        // 检验同键同参重放首次响应。
        ReworkInspectionRequest inspectReq =
                new ReworkInspectionRequest("idem-in1", "QA-A", true, 1);
        ReworkApplicationView inFirst = reworkService.inspect(first.id(), inspectReq);
        ReworkApplicationView inReplay = reworkService.inspect(first.id(), inspectReq);
        assertThat(inReplay).isEqualTo(inFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM rework_application WHERE id = ?",
                Integer.class, first.id())).isEqualTo(1);

        // 检验同键异参 409。
        assertThatThrownBy(() -> reworkService.inspect(first.id(),
                new ReworkInspectionRequest("idem-in1", "QA-B", true, 1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 同一物理辊累计最多返修两次且成功后继不能再建() {
        prepareExpiredFrozenRoll("R1-M");
        register("reg-m", "P-M", "R1-M");

        // 第一次返修。
        ReworkApplicationView rw1 = apply("rw-m1", "P-M", "R1-M", "R2-M", "510.00", "8");
        releaseByTwoInspectors(rw1, "QA-A", "QA-B");

        // 已放行申请不能再检验。
        assertThatThrownBy(() -> inspect(rw1.id(), "m-again", "QA-C", true, 3))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已放行");
        // 已产生成功后继的旧代不能再申请。
        assertThatThrownBy(() -> apply("rw-m1-dup", "P-M", "R1-M", "R2-M-DUP", "505.00", "8"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("成功后继不能再建");

        // 第二次返修：新代跑满冻结后再放行。
        rollLifeService.reportActual(new RollActualRequest(
                "r2m-km", "R2-M", new BigDecimal("8"), T0.plusDays(1).plusHours(1)));
        rollLifeService.freeze("R2-M", new RollFreezeRequest("fz-R2-M"));
        ReworkApplicationView rw2 = apply("rw-m2", "P-M", "R2-M", "R3-M", "505.00", "8");
        releaseByTwoInspectors(rw2, "QA-C", "QA-D");
        assertThat(reworkService.lineage("P-M").physical().reworkCount()).isEqualTo(2);

        // 第三代到限后第三次返修拒绝。
        rollLifeService.reportActual(new RollActualRequest(
                "r3m-km", "R3-M", new BigDecimal("8"), T0.plusDays(2).plusHours(1)));
        rollLifeService.freeze("R3-M", new RollFreezeRequest("fz-R3-M"));
        assertThatThrownBy(() -> apply("rw-m3", "P-M", "R3-M", "R4-M", "500.00", "8"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多返修 2 次");
        assertThat(reworkService.lineage("P-M").generations()).hasSize(3);
    }

    // ---------------- 并发辅助 ----------------

    private boolean rollExists(String rollNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_life WHERE roll_no = ?", Integer.class, rollNo);
        return count != null && count == 1;
    }

    private record ReleaseOutcome(ReworkApplicationView view, boolean conflict, String error) {
    }

    private List<ReworkApplicationView> applyConcurrent(ReworkApplicationRequest req1,
                                                        ReworkApplicationRequest req2)
            throws Exception {
        return runApplicationsConcurrent(req1, req2);
    }

    @SuppressWarnings("unchecked")
    private List<ReworkApplicationView> runApplicationsConcurrent(ReworkApplicationRequest req1,
                                                                  ReworkApplicationRequest req2)
            throws Exception {
        int n = 2;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ReworkApplicationView>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                return reworkService.apply(req1);
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                return reworkService.apply(req2);
            }));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ReworkApplicationView> results = new ArrayList<>();
            for (Future<ReworkApplicationView> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ReleaseOutcome> releaseConcurrent(long id1, String inspector1,
                                                   long id2, String inspector2) throws Exception {
        int n = 2;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<ReleaseOutcome>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                try {
                    return new ReleaseOutcome(
                            inspect(id1, "c-in2a", inspector1, true, 2), false, null);
                } catch (ConflictException e) {
                    return new ReleaseOutcome(null, true, e.getMessage());
                } catch (Exception e) {
                    return new ReleaseOutcome(null, false,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                try {
                    return new ReleaseOutcome(
                            inspect(id2, "c-in2b", inspector2, true, 2), false, null);
                } catch (ConflictException e) {
                    return new ReleaseOutcome(null, true, e.getMessage());
                } catch (Exception e) {
                    return new ReleaseOutcome(null, false,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ReleaseOutcome> outcomes = new ArrayList<>();
            for (Future<ReleaseOutcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
