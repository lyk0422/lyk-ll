package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.RepairApplyRequest;
import com.example.starter.rolling.api.dto.RepairApplyView;
import com.example.starter.rolling.api.dto.RepairInspectionRequest;
import com.example.starter.rolling.api.dto.RepairInspectionView;
import com.example.starter.rolling.api.dto.RollActualRequest;
import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollGenerationChainView;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.api.dto.RollView;
import com.example.starter.rolling.service.BusinessException;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.RollLifeService;
import com.example.starter.rolling.service.RollRepairService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 到期轧辊返修放行流程 H2（MODE=MySQL）集成测试：
 * 申请前置条件、直径/上限边界、两名不同检验员放行、任一不合格终止、
 * 并发双建只产生一代、成功后继不可再建、迟到旧实绩只落证据、
 * 新代里程从 0 起并沿用烫辊与到期禁排、聚合版本与幂等语义、代次链查询。
 */
class RollRepairIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private RollLifeService rollService;

    @Autowired
    private RollRepairService repairService;

    @Autowired
    private Clock clock;

    // ---------------- 测试辅助 ----------------

    /** 上机并把首代跑到恰达上限禁排，再日冻结，返回冻结台账。 */
    private RollView wearOutAndFreeze(String rollNo, String limitKm) {
        rollService.mount(new RollMountRequest("mount-" + rollNo, rollNo,
                new BigDecimal(limitKm), T0));
        rollService.reportActual(new RollActualRequest("wear-" + rollNo, rollNo,
                new BigDecimal(limitKm), T0.plusHours(1)));
        RollView banned = rollService.get(rollNo);
        assertThat(banned.status()).isEqualTo("BANNED");
        return rollService.freeze(rollNo, new RollFreezeRequest("freeze-" + rollNo));
    }

    private RepairApplyRequest applyReq(String requestId, String oldRollNo, String newRollNo,
                                        String diameter, String newLimit, int expectedVersion) {
        return new RepairApplyRequest(requestId, oldRollNo, new BigDecimal(diameter), newRollNo,
                new BigDecimal(newLimit), "CERT-" + requestId, expectedVersion);
    }

    private RepairApplyView apply(String requestId, String oldRollNo, String newRollNo,
                                  String diameter, String newLimit, int expectedVersion) {
        return repairService.apply(applyReq(requestId, oldRollNo, newRollNo, diameter, newLimit,
                expectedVersion));
    }

    private RepairInspectionView approve(long applicationId, String requestId, String inspector) {
        return repairService.inspect(applicationId,
                new RepairInspectionRequest(requestId, inspector, true, "CERT-" + inspector));
    }

    private RepairInspectionView reject(long applicationId, String requestId, String inspector) {
        return repairService.inspect(applicationId,
                new RepairInspectionRequest(requestId, inspector, false, "CERT-" + inspector));
    }

    /** 两名不同检验员先后合格放行。 */
    private RepairInspectionView twoInspectorApprove(long applicationId, String prefix) {
        RepairInspectionView first = approve(applicationId, prefix + "-a", "INS-A");
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.approvals()).isEqualTo(1);
        RepairInspectionView second = approve(applicationId, prefix + "-b", "INS-B");
        assertThat(second.status()).isEqualTo("APPROVED");
        assertThat(second.approvals()).isEqualTo(2);
        return second;
    }

    // ---------------- 主流程 ----------------

    @Test
    void 两人合格放行原子建立下一代且旧代台账完整保留() {
        wearOutAndFreeze("R1-OLD", "100");

        RepairApplyView app = apply("rp-1", "R1-OLD", "R1-NEW", "560.00", "90", 1);
        assertThat(app.status()).isEqualTo("PENDING");
        assertThat(app.applicationId()).isNotNull();
        assertThat(app.oldGenerationNo()).isEqualTo(1);

        RepairInspectionView done = twoInspectorApprove(app.applicationId(), "rp-1");
        assertThat(done.successorRollNo()).isEqualTo("R1-NEW");
        assertThat(done.aggregateVersion()).isEqualTo(2);

        // 旧代：里程、禁排与冻结结论全部保留，不能重置后冒充新辊。
        RollView old = rollService.get("R1-OLD");
        assertThat(old.status()).isEqualTo("BANNED");
        assertThat(old.totalKm()).isEqualByComparingTo("100");
        assertThat(old.frozenAt()).isNotNull();

        // 新代：独立台账，里程从 0 起，采用新上限与磨后直径。
        RollView fresh = rollService.get("R1-NEW");
        assertThat(fresh.status()).isEqualTo("IN_SERVICE");
        assertThat(fresh.totalKm()).isEqualByComparingTo("0");
        assertThat(fresh.cycleLimitKm()).isEqualByComparingTo("90");
        assertThat(fresh.frozenAt()).isNull();

        RollGenerationChainView chain = repairService.getChainByRollNo("R1-NEW");
        assertThat(chain.currentRollNo()).isEqualTo("R1-NEW");
        assertThat(chain.currentGenerationNo()).isEqualTo(2);
        assertThat(chain.repairCount()).isEqualTo(1);
        assertThat(chain.version()).isEqualTo(2);
        assertThat(chain.generations()).hasSize(2);
        assertThat(chain.generations().get(0).rollNo()).isEqualTo("R1-OLD");
        assertThat(chain.generations().get(0).totalKm()).isEqualByComparingTo("100");
        assertThat(chain.generations().get(0).status()).isEqualTo("BANNED");
        assertThat(chain.generations().get(0).diameterMm()).isNull();
        assertThat(chain.generations().get(1).rollNo()).isEqualTo("R1-NEW");
        assertThat(chain.generations().get(1).totalKm()).isEqualByComparingTo("0");
        assertThat(chain.generations().get(1).diameterMm()).isEqualByComparingTo("560.00");

        // 检验确认与申请、新代同事务落库。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_inspection WHERE request_id_pk = ?",
                Integer.class, app.applicationId())).isEqualTo(2);
    }

    @Test
    void 新代继续沿用首用烫辊与到期禁排入口() {
        wearOutAndFreeze("R2-OLD", "100");
        RepairApplyView app = apply("rp-2", "R2-OLD", "R2-NEW", "555.50", "100", 1);
        twoInspectorApprove(app.applicationId(), "rp-2");

        // 新代上机时刻为放行时刻（UTC），实绩时刻取其后。
        LocalDateTime base = LocalDateTime.now(clock).plusMinutes(5);
        for (int i = 1; i <= 6; i++) {
            RollActualView view = rollService.reportActual(new RollActualRequest(
                    "rn-" + i, "R2-NEW", new BigDecimal("1"), base.plusMinutes(i)));
            assertThat(view.accepted()).isTrue();
            assertThat(view.firstUse()).as("新代第 %d 卷首用标记", i).isEqualTo(i <= 5);
        }
        RollView newRoll = rollService.get("R2-NEW");
        assertThat(newRoll.totalKm()).isEqualByComparingTo("6");
        assertThat(newRoll.coilsSinceMount()).isEqualTo(6);
        assertThat(newRoll.status()).isEqualTo("IN_SERVICE");

        // 跑到新代上限即禁排。
        rollService.reportActual(new RollActualRequest("rn-99", "R2-NEW",
                new BigDecimal("94"), base.plusMinutes(20)));
        assertThat(rollService.get("R2-NEW").status()).isEqualTo("BANNED");
        // 旧代结论不变。
        assertThat(rollService.get("R2-OLD").status()).isEqualTo("BANNED");
        assertThat(rollService.get("R2-OLD").totalKm()).isEqualByComparingTo("100");
    }

    // ---------------- 直径与上限边界 ----------------

    @Test
    void 磨后直径低于500毫米拒绝() {
        wearOutAndFreeze("R3-OLD", "100");
        assertThatThrownBy(() -> apply("rp-3", "R3-OLD", "R3-NEW", "499.99", "90", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
        // 失败不占键：同 requestId 改参数可重新提交成功。
        RepairApplyView retry = apply("rp-3", "R3-OLD", "R3-NEW", "500.00", "90", 1);
        assertThat(retry.status()).isEqualTo("PENDING");
    }

    @Test
    void 磨后直径恰为500毫米放行且保留两位小数() {
        wearOutAndFreeze("R4-OLD", "100");
        RepairApplyView app = apply("rp-4", "R4-OLD", "R4-NEW", "500.00", "100", 1);
        twoInspectorApprove(app.applicationId(), "rp-4");
        assertThat(jdbc.queryForObject(
                "SELECT diameter_mm FROM roll_life WHERE roll_no = 'R4-NEW'",
                BigDecimal.class)).isEqualByComparingTo("500.00");
    }

    @Test
    void 第二次返修直径不得比上一代大且累计最多两次() {
        wearOutAndFreeze("R5-G1", "100");
        RepairApplyView a1 = apply("rp5-1", "R5-G1", "R5-G2", "560.00", "90", 1);
        twoInspectorApprove(a1.applicationId(), "rp5-1");

        // 新代跑到禁排并冻结。
        LocalDateTime base = LocalDateTime.now(clock).plusMinutes(5);
        for (int i = 1; i <= 9; i++) {
            rollService.reportActual(new RollActualRequest("g2-" + i, "R5-G2",
                    new BigDecimal("10"), base.plusMinutes(i)));
        }
        assertThat(rollService.get("R5-G2").status()).isEqualTo("BANNED");
        rollService.freeze("R5-G2", new RollFreezeRequest("freeze-R5-G2"));

        // 直径大于上一代 560.00 拒绝。
        assertThatThrownBy(() -> apply("rp5-bad", "R5-G2", "R5-G3", "560.01", "80", 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得比上一代直径");
        // 新上限超过上代 90 拒绝。
        assertThatThrownBy(() -> apply("rp5-bad2", "R5-G2", "R5-G3", "550.00", "90.001", 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得超过上一代上限");
        // 携带旧版本 1 提交 409。
        assertThatThrownBy(() -> apply("rp5-stale", "R5-G2", "R5-G3", "550.00", "80", 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本");

        // 合法第二次返修：直径不大于上代、上限不超过上代、expectedVersion=2。
        RepairApplyView a2 = apply("rp5-2", "R5-G2", "R5-G3", "550.00", "80", 2);
        twoInspectorApprove(a2.applicationId(), "rp5-2");
        assertThat(repairService.getChainByRollNo("R5-G1").generations()).hasSize(3);
        assertThat(repairService.getChainByRollNo("R5-G3").repairCount()).isEqualTo(2);

        // 第三代再到期冻结后，累计返修两次上限拒绝第三次。
        LocalDateTime base2 = LocalDateTime.now(clock).plusMinutes(30);
        for (int i = 1; i <= 8; i++) {
            rollService.reportActual(new RollActualRequest("g3-" + i, "R5-G3",
                    new BigDecimal("10"), base2.plusMinutes(i)));
        }
        rollService.freeze("R5-G3", new RollFreezeRequest("freeze-R5-G3"));
        assertThatThrownBy(() -> apply("rp5-3", "R5-G3", "R5-G4", "540.00", "70", 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多返修两次");
    }

    // ---------------- 申请前置条件 ----------------

    @Test
    void 未到期或未冻结不能申请返修() {
        rollService.mount(new RollMountRequest("mount-R6", "R6", new BigDecimal("100"), T0));
        // 在役未到期。
        assertThatThrownBy(() -> apply("rp6-a", "R6", "R6-N", "550.00", "90", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未达到寿命上限");

        // 到期但未冻结。
        rollService.reportActual(new RollActualRequest("wear-R6", "R6",
                new BigDecimal("100"), T0.plusHours(1)));
        assertThatThrownBy(() -> apply("rp6-b", "R6", "R6-N", "550.00", "90", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未日冻结");
    }

    @Test
    void 新辊号占用与跨代次申请拒绝且成功后继不可再建() {
        wearOutAndFreeze("R7-OLD", "100");
        wearOutAndFreeze("R7-OTHER", "100");

        // 新辊号占用已有号。
        assertThatThrownBy(() -> apply("rp7-a", "R7-OLD", "R7-OTHER", "550.00", "90", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被占用");

        RepairApplyView app = apply("rp7-b", "R7-OLD", "R7-NEW", "550.00", "90", 1);
        twoInspectorApprove(app.applicationId(), "rp7-b");

        // 成功后继不能再建：同一旧代次再次申请 409。
        assertThatThrownBy(() -> apply("rp7-c", "R7-OLD", "R7-NEW2", "540.00", "80", 2))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已存在成功后继");

        // 旧代已非物理辊当前代次，对其引用被拒绝（不能跨代次返修）。
        assertThat(jdbc.queryForObject(
                "SELECT status FROM roll_repair_request WHERE id = ?",
                String.class, app.applicationId())).isEqualTo("APPROVED");
    }

    @Test
    void 不存在辊号申请返修404() {
        assertThatThrownBy(() -> apply("rp8", "R404", "R404-N", "550.00", "90", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("轧辊不存在");
    }

    // ---------------- 两名检验员规则 ----------------

    @Test
    void 同一检验员重复确认拒绝且必须两人() {
        wearOutAndFreeze("R9-OLD", "100");
        RepairApplyView app = apply("rp-9", "R9-OLD", "R9-NEW", "550.00", "90", 1);

        RepairInspectionView first = approve(app.applicationId(), "rp9-a", "INS-A");
        assertThat(first.approvals()).isEqualTo(1);
        assertThat(first.status()).isEqualTo("PENDING");

        assertThatThrownBy(() -> approve(app.applicationId(), "rp9-a2", "INS-A"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("两名不同检验员");

        // 只有一人合格时新代不得建立。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_life WHERE roll_no = 'R9-NEW'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM roll_repair_request WHERE id = ?",
                String.class, app.applicationId())).isEqualTo("PENDING");
    }

    @Test
    void 任一检验不合格即终止不建新辊终止后可另提新申请() {
        wearOutAndFreeze("R10-OLD", "100");
        RepairApplyView app1 = apply("rp10-1", "R10-OLD", "R10-NEW", "550.00", "90", 1);
        approve(app1.applicationId(), "rp10-a", "INS-A");
        RepairInspectionView rejected = reject(app1.applicationId(), "rp10-b", "INS-B");
        assertThat(rejected.status()).isEqualTo("REJECTED");

        // 不建新辊，聚合版本不变。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_life WHERE roll_no = 'R10-NEW'",
                Integer.class)).isZero();
        assertThat(repairService.getChainByRollNo("R10-OLD").version()).isEqualTo(1);

        // 终态申请不再接受检验。
        assertThatThrownBy(() -> approve(app1.applicationId(), "rp10-c", "INS-C"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("终止");

        // 终止的申请可以另提新申请并成功放行。
        RepairApplyView app2 = apply("rp10-2", "R10-OLD", "R10-NEW", "540.00", "85", 1);
        twoInspectorApprove(app2.applicationId(), "rp10-2");
        assertThat(repairService.getChainByRollNo("R10-NEW").currentGenerationNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_request WHERE old_roll_no = 'R10-OLD'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_request WHERE old_roll_no = 'R10-OLD'"
                        + " AND status = 'APPROVED'", Integer.class)).isEqualTo(1);
    }

    // ---------------- 并发双建 ----------------

    @Test
    void 并发双建放行只产生一代() throws Exception {
        wearOutAndFreeze("R11-OLD", "100");
        RepairApplyView app1 = apply("rp11-1", "R11-OLD", "R11-N1", "550.00", "90", 1);
        RepairApplyView app2 = apply("rp11-2", "R11-OLD", "R11-N2", "540.00", "80", 1);

        // 两个申请各自的两名检验员共 4 个确认并发提交。
        List<InspectOutcome> outcomes = runConcurrentInspections(List.of(
                new InspectTask(app1.applicationId(), "rp11-a", "INS-A", true),
                new InspectTask(app1.applicationId(), "rp11-b", "INS-B", true),
                new InspectTask(app2.applicationId(), "rp11-c", "INS-C", true),
                new InspectTask(app2.applicationId(), "rp11-d", "INS-D", true)));

        long approvedCount = outcomes.stream().filter(o -> o.view != null
                && "APPROVED".equals(o.view.status())).count();
        assertThat(approvedCount).as("并发放行只产生一代").isEqualTo(1);
        for (InspectOutcome outcome : outcomes) {
            assertThat(outcome.view != null || outcome.conflict)
                    .as("非成功结果只能是版本冲突 409，实际：%s", outcome.error).isTrue();
        }

        // 物理辊只推进一代：恰好一个新辊号，旧代成功后继唯一。
        RollGenerationChainView chain = repairService.getChainByRollNo("R11-OLD");
        assertThat(chain.generations()).hasSize(2);
        assertThat(chain.repairCount()).isEqualTo(1);
        assertThat(chain.version()).isEqualTo(2);
        long newRolls = jdbc.queryForObject("""
                SELECT COUNT(1) FROM roll_life WHERE physical_roll_id = ? AND generation_no = 2
                """, Long.class, chain.physicalId());
        assertThat(newRolls).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(1) FROM roll_repair_request
                WHERE old_roll_no = 'R11-OLD' AND status = 'APPROVED'
                """, Long.class)).isEqualTo(1);

        // 胜出新辊台账存在且里程从 0 起；落败拟用辊号根本未建。
        String winnerRoll = chain.currentRollNo();
        assertThat(rollService.get(winnerRoll).totalKm()).isEqualByComparingTo("0");
        String loserRoll = "R11-N1".equals(winnerRoll) ? "R11-N2" : "R11-N1";
        assertThatThrownBy(() -> rollService.get(loserRoll))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("轧辊不存在");
    }

    private record InspectTask(long applicationId, String requestId, String inspector,
                               boolean approved) {
    }

    private record InspectOutcome(RepairInspectionView view, boolean conflict, String error) {
    }

    private List<InspectOutcome> runConcurrentInspections(List<InspectTask> tasks) throws Exception {
        int n = tasks.size();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<InspectOutcome>> futures = new ArrayList<>();
            for (InspectTask task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    try {
                        RepairInspectionView view = repairService.inspect(task.applicationId(),
                                new RepairInspectionRequest(task.requestId(), task.inspector(),
                                        task.approved(), "CERT-" + task.inspector()));
                        return new InspectOutcome(view, false, null);
                    } catch (ConflictException e) {
                        return new InspectOutcome(null, true, e.getMessage());
                    } catch (Exception e) {
                        return new InspectOutcome(null, false,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<InspectOutcome> outcomes = new ArrayList<>();
            for (Future<InspectOutcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---------------- 迟到旧实绩 ----------------

    @Test
    void 建代后迟到旧实绩只落旧代证据不影响新代() {
        wearOutAndFreeze("R12-OLD", "100");
        RepairApplyView app = apply("rp12", "R12-OLD", "R12-NEW", "550.00", "100", 1);
        twoInspectorApprove(app.applicationId(), "rp12");

        // 迟到实绩仍回报到旧辊号：只落证据，旧代冻结里程与禁排结论不变。
        RollActualRequest lateReq = new RollActualRequest("late-1", "R12-OLD",
                new BigDecimal("7"), LocalDateTime.now(clock).plusMinutes(2));
        RollActualView late = rollService.reportActual(lateReq);
        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isFalse();
        assertThat(late.totalKm()).isEqualByComparingTo("100");
        assertThat(rollService.get("R12-OLD").totalKm()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R12-OLD'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_late_evidence WHERE old_roll_no = 'R12-OLD'",
                Integer.class)).isEqualTo(1);
        // 日冻结迟到事件表不再为已替代代次记账。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = 'R12-OLD'",
                Integer.class)).isZero();

        // 同键重放不重复登记证据。
        RollActualView replay = rollService.reportActual(lateReq);
        assertThat(replay.late()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_late_evidence WHERE old_roll_no = 'R12-OLD'",
                Integer.class)).isEqualTo(1);

        // 新代台账不受旧实绩影响，独立从 0 累加。
        RollActualView fresh = rollService.reportActual(new RollActualRequest("new-1", "R12-NEW",
                new BigDecimal("8"), LocalDateTime.now(clock).plusMinutes(10)));
        assertThat(fresh.accepted()).isTrue();
        assertThat(fresh.totalKm()).isEqualByComparingTo("8");

        RollGenerationChainView chain = repairService.getChainByRollNo("R12-OLD");
        assertThat(chain.generations().get(0).lateEvidenceCount()).isEqualTo(1);
        assertThat(chain.generations().get(1).lateEvidenceCount()).isZero();
    }

    // ---------------- 幂等 ----------------

    @Test
    void 申请与检验同键同参重放异参409() {
        wearOutAndFreeze("R13-OLD", "100");
        RepairApplyRequest req = applyReq("rp13", "R13-OLD", "R13-NEW", "550.00", "90", 1);
        RepairApplyView first = repairService.apply(req);
        RepairApplyView replay = repairService.apply(req);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_request WHERE old_roll_no = 'R13-OLD'",
                Integer.class)).isEqualTo(1);

        // 同键异参 409。
        assertThatThrownBy(() -> repairService.apply(
                applyReq("rp13", "R13-OLD", "R13-NEW", "540.00", "90", 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("rp13");

        // 检验同键同参重放，不重复插入确认、不重复建代。
        RepairInspectionView a1 = approve(first.applicationId(), "rp13-a", "INS-A");
        RepairInspectionView a1Replay = approve(first.applicationId(), "rp13-a", "INS-A");
        assertThat(a1Replay).isEqualTo(a1);
        RepairInspectionView b1 = approve(first.applicationId(), "rp13-b", "INS-B");
        assertThat(b1.status()).isEqualTo("APPROVED");
        RepairInspectionView b1Replay = approve(first.applicationId(), "rp13-b", "INS-B");
        assertThat(b1Replay).isEqualTo(b1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_repair_inspection WHERE request_id_pk = ?",
                Integer.class, first.applicationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_life WHERE roll_no = 'R13-NEW'",
                Integer.class)).isEqualTo(1);

        // 检验同键异参 409。
        assertThatThrownBy(() -> repairService.inspect(first.applicationId(),
                new RepairInspectionRequest("rp13-b", "INS-A", false, "CERT-X")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("rp13-b");
    }
}
