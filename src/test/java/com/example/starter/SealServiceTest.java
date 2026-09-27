package com.example.starter;

import com.example.starter.domain.UnsealPermit;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.BadRequestException;
import com.example.starter.service.ConflictException;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铅封登记、解封批准、撤销、复封：主流程、参数/版本/状态失败分支与幂等边界。
 */
class SealServiceTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = T0;
    private static final Instant WINDOW_END = T0.plus(Duration.ofHours(2));

    @Autowired
    private SealService sealService;

    private ValveSeal registerSeal(String requestId, String sealCode) {
        return sealService.registerSeal(new SealService.RegisterSealCommand(
                requestId, 1L, sealCode, 0));
    }

    private UnsealPermit approve(String requestId, String sealCode) {
        return sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                requestId, sealCode, 0, "张三", "李四", WINDOW_START, WINDOW_END));
    }

    @Test
    void 铅封登记主流程记录封号版本与封存时刻() {
        ValveSeal seal = registerSeal("req-seal-1", "SEAL-001");

        assertThat(seal.sealCode()).isEqualTo("SEAL-001");
        assertThat(seal.valveVersion()).isZero();
        assertThat(seal.status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);
        assertThat(seal.sealedAt()).isEqualTo(T0);
        assertThat(sealService.getSeal("SEAL-001").id()).isEqualTo(seal.id());
        assertThat(sealService.getSealChain(1L)).hasSize(1);
    }

    @Test
    void 铅封登记同键同参重放首次结果且异参409() {
        ValveSeal first = registerSeal("req-seal-idem", "SEAL-002");

        ValveSeal replay = registerSeal("req-seal-idem", "SEAL-002");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(countOf("valve_seal")).isEqualTo(1);

        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-idem", 1L, "SEAL-OTHER", 0)))
                .isInstanceOf(ConflictException.class);
        assertThat(countOf("valve_seal")).isEqualTo(1);
    }

    @Test
    void 铅封登记版本不一致与重复封号被拒绝() {
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-v", 1L, "SEAL-003", 1)))
                .isInstanceOf(UnprocessableException.class);

        registerSeal("req-seal-a", "SEAL-004");
        // 同阀门已存在生效铅封
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-b", 1L, "SEAL-005", 0)))
                .isInstanceOf(UnprocessableException.class);
        // 铅封号全局唯一（其他阀门也不能复用）
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-c", 2L, "SEAL-004", 0)))
                .isInstanceOf(UnprocessableException.class);
        // 失败不占键：同键换合法参数可重新受理
        ValveSeal ok = sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-b", 2L, "SEAL-006", 0));
        assertThat(ok.sealCode()).isEqualTo("SEAL-006");
    }

    @Test
    void 铅封登记参数非法400与对象不存在404() {
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-p1", 1L, " ", 0)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-p2", 1L, "SEAL-007", null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-p3", 99L, "SEAL-008", 0)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 解封批准主流程双人确认且固定窗口与两次额度() {
        registerSeal("req-seal-10", "SEAL-010");

        UnsealPermit permit = approve("req-unseal-10", "SEAL-010");

        assertThat(permit.status()).isEqualTo(UnsealPermit.PermitStatus.ACTIVE);
        assertThat(permit.approvers()).containsExactly("张三", "李四");
        assertThat(permit.windowStart()).isEqualTo(WINDOW_START);
        assertThat(permit.windowEnd()).isEqualTo(WINDOW_END);
        assertThat(permit.quotaTotal()).isEqualTo(2);
        assertThat(permit.quotaUsed()).isZero();
        assertThat(permit.version()).isEqualTo(1);
        // 解封生效不改变开度
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        assertThat(countOf("valve_opening_version")).isZero();
    }

    @Test
    void 解封批准同键重放冻结结果且异参409() {
        registerSeal("req-seal-11", "SEAL-011");
        UnsealPermit first = approve("req-unseal-11", "SEAL-011");

        UnsealPermit replay = approve("req-unseal-11", "SEAL-011");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(countOf("unseal_permit")).isEqualTo(1);

        assertThatThrownBy(() -> sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-unseal-11", "SEAL-011", 0, "张三", "王五", WINDOW_START, WINDOW_END)))
                .isInstanceOf(ConflictException.class);
        assertThat(countOf("unseal_permit")).isEqualTo(1);
    }

    @Test
    void 解封批准失败分支() {
        registerSeal("req-seal-12", "SEAL-012");

        // 同一授权人
        assertThatThrownBy(() -> sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-u1", "SEAL-012", 0, "张三", "张三", WINDOW_START, WINDOW_END)))
                .isInstanceOf(BadRequestException.class);
        // 窗口终点不晚于起点
        assertThatThrownBy(() -> sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-u2", "SEAL-012", 0, "张三", "李四", WINDOW_END, WINDOW_START)))
                .isInstanceOf(BadRequestException.class);
        // 引用版本与台账不一致
        assertThatThrownBy(() -> sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-u3", "SEAL-012", 1, "张三", "李四", WINDOW_START, WINDOW_END)))
                .isInstanceOf(UnprocessableException.class);
        // 铅封号不存在
        assertThatThrownBy(() -> sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-u4", "SEAL-MISSING", 0, "张三", "李四", WINDOW_START, WINDOW_END)))
                .isInstanceOf(NotFoundException.class);
        // 失败不占键
        UnsealPermit ok = sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-u3", "SEAL-012", 0, "张三", "李四", WINDOW_START, WINDOW_END));
        assertThat(ok.status()).isEqualTo(UnsealPermit.PermitStatus.ACTIVE);
        // 已存在生效解封单时不得重复批准
        assertThatThrownBy(() -> approve("req-u5", "SEAL-012"))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void 撤销主流程与失败分支() {
        registerSeal("req-seal-20", "SEAL-020");
        UnsealPermit permit = approve("req-unseal-20", "SEAL-020");

        UnsealPermit revoked = sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-20", permit.id(), 1, "王五"));

        assertThat(revoked.status()).isEqualTo(UnsealPermit.PermitStatus.REVOKED);
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.revokedAt()).isEqualTo(T0);

        // 重复撤销（同键重放返回首次冻结结果）
        UnsealPermit replay = sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-20", permit.id(), 1, "王五"));
        assertThat(replay.status()).isEqualTo(UnsealPermit.PermitStatus.REVOKED);
        assertThat(countOf("unseal_permit")).isEqualTo(1);
        // 非生效单不可再撤销（新键）
        assertThatThrownBy(() -> sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-21", permit.id(), 2, "王五")))
                .isInstanceOf(UnprocessableException.class);
        // 解封单不存在
        assertThatThrownBy(() -> sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-22", 999L, 1, "王五")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 撤销版本不一致被拒绝且失败不占键() {
        registerSeal("req-seal-21", "SEAL-021");
        UnsealPermit permit = approve("req-unseal-21", "SEAL-021");

        assertThatThrownBy(() -> sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-x", permit.id(), 9, "王五")))
                .isInstanceOf(UnprocessableException.class);

        UnsealPermit ok = sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-x", permit.id(), 1, "王五"));
        assertThat(ok.status()).isEqualTo(UnsealPermit.PermitStatus.REVOKED);
    }

    @Test
    void 复封主流程原子终结旧解封且保留封号链() {
        registerSeal("req-seal-30", "SEAL-030");
        UnsealPermit permit = approve("req-unseal-30", "SEAL-030");

        ValveSeal newSeal = sealService.reseal(new SealService.ResealCommand(
                "req-reseal-30", "SEAL-030", 0, "SEAL-031", "赵六", "孙七"));

        assertThat(newSeal.sealCode()).isEqualTo("SEAL-031");
        assertThat(newSeal.status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);
        assertThat(newSeal.valveVersion()).isZero();
        // 旧铅封已终结，旧解封单已关闭且版本 +1
        assertThat(sealService.getSeal("SEAL-030").status()).isEqualTo(ValveSeal.SealStatus.RESEALED);
        UnsealPermit closed = sealService.getPermit(permit.id());
        assertThat(closed.status()).isEqualTo(UnsealPermit.PermitStatus.CLOSED);
        assertThat(closed.version()).isEqualTo(2);
        assertThat(closed.closedAt()).isEqualTo(T0);
        // 封号链保留历史
        List<ValveSeal> chain = sealService.getSealChain(1L);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).sealCode()).isEqualTo("SEAL-030");
        assertThat(chain.get(1).sealCode()).isEqualTo("SEAL-031");
        // 复封同键重放返回首次结果，不重复终结
        ValveSeal replay = sealService.reseal(new SealService.ResealCommand(
                "req-reseal-30", "SEAL-030", 0, "SEAL-031", "赵六", "孙七"));
        assertThat(replay.id()).isEqualTo(newSeal.id());
        assertThat(countOf("valve_seal")).isEqualTo(2);
    }

    @Test
    void 复封失败分支() {
        registerSeal("req-seal-40", "SEAL-040");

        // 同一核对人
        assertThatThrownBy(() -> sealService.reseal(new SealService.ResealCommand(
                "req-r1", "SEAL-040", 0, "SEAL-041", "赵六", "赵六")))
                .isInstanceOf(BadRequestException.class);
        // 版本不一致
        assertThatThrownBy(() -> sealService.reseal(new SealService.ResealCommand(
                "req-r2", "SEAL-040", 3, "SEAL-041", "赵六", "孙七")))
                .isInstanceOf(UnprocessableException.class);
        // 旧铅封不存在
        assertThatThrownBy(() -> sealService.reseal(new SealService.ResealCommand(
                "req-r3", "SEAL-MISSING", 0, "SEAL-041", "赵六", "孙七")))
                .isInstanceOf(NotFoundException.class);
        // 新封号与旧封号相同
        assertThatThrownBy(() -> sealService.reseal(new SealService.ResealCommand(
                "req-r4", "SEAL-040", 0, "SEAL-040", "赵六", "孙七")))
                .isInstanceOf(UnprocessableException.class);
        // 失败不占键
        ValveSeal ok = sealService.reseal(new SealService.ResealCommand(
                "req-r2", "SEAL-040", 0, "SEAL-041", "赵六", "孙七"));
        assertThat(ok.sealCode()).isEqualTo("SEAL-041");
        // 已复封不可重复复封
        assertThatThrownBy(() -> sealService.reseal(new SealService.ResealCommand(
                "req-r5", "SEAL-040", 0, "SEAL-042", "赵六", "孙七")))
                .isInstanceOf(UnprocessableException.class);
    }
}
