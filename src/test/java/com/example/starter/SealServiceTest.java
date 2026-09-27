package com.example.starter;

import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.UnsealQuotaEvent;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.ConflictException;
import com.example.starter.service.Fingerprints;
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
 * 铅封管理：登记、解封批准、撤销、复封与额度轨迹的主流程与失败分支。
 */
class SealServiceTest extends IntegrationTestBase {

    @Autowired
    private SealService sealService;

    private ValveSeal registerValve1() {
        return sealService.registerSeal(1L, "req-seal-1", "SEAL-0001", 0, "张三");
    }

    private UnsealOrder approveValve1(Instant start, Instant end, int quota) {
        return sealService.approveUnseal(1L, "req-unseal-1", "SEAL-0001", 0,
                "李四", "王五", start, end, quota);
    }

    // ---------- 登记 ----------

    @Test
    void 登记铅封记录封号开度版本与封存时刻() {
        ValveSeal seal = registerValve1();

        assertThat(seal.sealNo()).isEqualTo("SEAL-0001");
        assertThat(seal.valveVersion()).isZero();
        assertThat(seal.sealedAt()).isEqualTo(T0);
        assertThat(seal.status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);
        assertThat(seal.version()).isEqualTo(1);
        assertThat(seal.operatorA()).isEqualTo("张三");
    }

    @Test
    void 登记同键同参重放首次结果同键异参409() {
        ValveSeal first = registerValve1();

        ValveSeal replay = sealService.registerSeal(1L, "req-seal-1", "SEAL-0001", 0, "张三");
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(countOf("valve_seal")).isEqualTo(1);

        assertThatThrownBy(() -> sealService.registerSeal(1L, "req-seal-1", "SEAL-0002", 0, "张三"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 登记失败不占键可换参重试() {
        assertThatThrownBy(() -> sealService.registerSeal(1L, "req-seal-retry", "SEAL-0001", 9, "张三"))
                .isInstanceOf(ConflictException.class);
        assertThat(countOf("valve_seal")).isZero();

        ValveSeal seal = sealService.registerSeal(1L, "req-seal-retry", "SEAL-0001", 0, "张三");
        assertThat(seal.sealNo()).isEqualTo("SEAL-0001");
    }

    @Test
    void 登记版本不符409阀门不存在404已有有效铅封409() {
        assertThatThrownBy(() -> sealService.registerSeal(1L, "req-s-v", "SEAL-0001", 3, "张三"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> sealService.registerSeal(99L, "req-s-nf", "SEAL-0001", 0, "张三"))
                .isInstanceOf(NotFoundException.class);

        registerValve1();
        assertThatThrownBy(() -> sealService.registerSeal(1L, "req-s-dup", "SEAL-0002", 0, "张三"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 铅封号全局唯一复用他人封号409() {
        registerValve1();
        assertThatThrownBy(() -> sealService.registerSeal(2L, "req-s-steal", "SEAL-0001", 0, "张三"))
                .isInstanceOf(ConflictException.class);
    }

    // ---------- 批准解封 ----------

    @Test
    void 批准解封两名授权人窗口额度且不改开度() {
        registerValve1();
        Instant start = T0.minus(Duration.ofHours(1));
        Instant end = T0.plus(Duration.ofHours(2));

        UnsealOrder order = approveValve1(start, end, 2);

        assertThat(order.status()).isEqualTo(UnsealOrder.OrderStatus.ACTIVE);
        assertThat(order.authorizerA()).isEqualTo("李四");
        assertThat(order.authorizerB()).isEqualTo("王五");
        assertThat(order.windowStart()).isEqualTo(start);
        assertThat(order.windowEnd()).isEqualTo(end);
        assertThat(order.quotaTotal()).isEqualTo(2);
        assertThat(order.quotaUsed()).isZero();
        assertThat(order.expectedValveVersion()).isZero();
        assertThat(order.version()).isEqualTo(1);
        // 解封生效不改变开度
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        // 额度轨迹：授予事件
        List<UnsealQuotaEvent> events = sealService.getQuotaEvents(order.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo(UnsealQuotaEvent.EventType.GRANT);
    }

    @Test
    void 批准解封参数与状态校验() {
        registerValve1();
        Instant start = T0.minus(Duration.ofHours(1));
        Instant end = T0.plus(Duration.ofHours(2));

        // 同一授权人 → 422
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-u-same", "SEAL-0001", 0,
                "李四", "李四", start, end, 2))
                .isInstanceOf(UnprocessableException.class);
        // 额度超过两次 → 422
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-u-q3", "SEAL-0001", 0,
                "李四", "王五", start, end, 3))
                .isInstanceOf(UnprocessableException.class);
        // 额度为零 → 422
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-u-q0", "SEAL-0001", 0,
                "李四", "王五", start, end, 0))
                .isInstanceOf(UnprocessableException.class);
        // 窗口起点不早于终点 → 422
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-u-w", "SEAL-0001", 0,
                "李四", "王五", end, start, 2))
                .isInstanceOf(UnprocessableException.class);
        // 铅封号不符 → 404
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-u-s", "SEAL-9999", 0,
                "李四", "王五", start, end, 2))
                .isInstanceOf(NotFoundException.class);
        // 开度版本不符 → 409
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-u-v", "SEAL-0001", 7,
                "李四", "王五", start, end, 2))
                .isInstanceOf(ConflictException.class);
        // 以上失败均不占键不生成解封单
        assertThat(countOf("unseal_order")).isZero();
        // 无铅封阀门 → 404
        assertThatThrownBy(() -> sealService.approveUnseal(2L, "req-u-nf", "SEAL-0001", 0,
                "李四", "王五", start, end, 2))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 批准解封幂等与并发占用() {
        registerValve1();
        Instant start = T0.minus(Duration.ofHours(1));
        Instant end = T0.plus(Duration.ofHours(2));
        UnsealOrder first = approveValve1(start, end, 2);

        UnsealOrder replay = approveValve1(start, end, 2);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(countOf("unseal_order")).isEqualTo(1);

        // 同键异参 → 409
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-unseal-1", "SEAL-0001", 0,
                "李四", "王五", start, end, 1))
                .isInstanceOf(ConflictException.class);
        // 已有生效中解封单 → 409
        assertThatThrownBy(() -> sealService.approveUnseal(1L, "req-unseal-2", "SEAL-0001", 0,
                "赵六", "孙七", start, end, 1))
                .isInstanceOf(ConflictException.class);
    }

    // ---------- 撤销 ----------

    @Test
    void 撤销解封后剩余额度作废且行版本加一() {
        registerValve1();
        UnsealOrder order = approveValve1(T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);

        UnsealOrder revoked = sealService.revokeUnseal(order.id(), "req-revoke-1", 1);

        assertThat(revoked.status()).isEqualTo(UnsealOrder.OrderStatus.REVOKED);
        assertThat(revoked.version()).isEqualTo(2);
        List<UnsealQuotaEvent> events = sealService.getQuotaEvents(order.id());
        assertThat(events).extracting(UnsealQuotaEvent::eventType)
                .containsExactly(UnsealQuotaEvent.EventType.GRANT, UnsealQuotaEvent.EventType.REVOKE);

        // 同键重放冻结结果
        UnsealOrder replay = sealService.revokeUnseal(order.id(), "req-revoke-1", 1);
        assertThat(replay.status()).isEqualTo(UnsealOrder.OrderStatus.REVOKED);
        // 已撤销不允许再撤销（新键）→ 409
        assertThatThrownBy(() -> sealService.revokeUnseal(order.id(), "req-revoke-2", 2))
                .isInstanceOf(ConflictException.class);
        // 行版本不符 → 409
        assertThatThrownBy(() -> sealService.revokeUnseal(order.id(), "req-revoke-3", 9))
                .isInstanceOf(ConflictException.class);
        // 解封单不存在 → 404
        assertThatThrownBy(() -> sealService.revokeUnseal(99L, "req-revoke-4", 1))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------- 复封 ----------

    @Test
    void 复封终结解封权限并保留历史封号链() {
        registerValve1();
        UnsealOrder order = approveValve1(T0.minus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)), 2);

        ValveSeal renewed = sealService.reseal(1L, "req-reseal-1", "SEAL-0002", 0, "赵六", "孙七");

        assertThat(renewed.sealNo()).isEqualTo("SEAL-0002");
        assertThat(renewed.status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);
        assertThat(renewed.operatorA()).isEqualTo("赵六");
        assertThat(renewed.operatorB()).isEqualTo("孙七");
        // 旧解封权限原子终结
        UnsealOrder terminated = sealService.getUnsealOrder(order.id());
        assertThat(terminated.status()).isEqualTo(UnsealOrder.OrderStatus.TERMINATED);
        // 历史封号链保留：旧封号 SUPERSEDED，新封号 ACTIVE
        List<ValveSeal> chain = sealService.getSealChain(1L);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).sealNo()).isEqualTo("SEAL-0001");
        assertThat(chain.get(0).status()).isEqualTo(ValveSeal.SealStatus.SUPERSEDED);
        assertThat(chain.get(0).version()).isEqualTo(2);
        assertThat(chain.get(1).sealNo()).isEqualTo("SEAL-0002");
        // 轨迹含终结事件
        assertThat(sealService.getQuotaEvents(order.id()))
                .extracting(UnsealQuotaEvent::eventType)
                .containsExactly(UnsealQuotaEvent.EventType.GRANT,
                        UnsealQuotaEvent.EventType.TERMINATE);
    }

    @Test
    void 复封校验两人不同版本一致且铅封存在() {
        registerValve1();

        // 同一核对人 → 422
        assertThatThrownBy(() -> sealService.reseal(1L, "req-r-same", "SEAL-0002", 0, "赵六", "赵六"))
                .isInstanceOf(UnprocessableException.class);
        // 开度版本不符 → 409
        assertThatThrownBy(() -> sealService.reseal(1L, "req-r-v", "SEAL-0002", 5, "赵六", "孙七"))
                .isInstanceOf(ConflictException.class);
        // 新封号与历史封号重复 → 409
        assertThatThrownBy(() -> sealService.reseal(1L, "req-r-dup", "SEAL-0001", 0, "赵六", "孙七"))
                .isInstanceOf(ConflictException.class);
        // 无有效铅封 → 404
        assertThatThrownBy(() -> sealService.reseal(2L, "req-r-nf", "SEAL-0009", 0, "赵六", "孙七"))
                .isInstanceOf(NotFoundException.class);
        // 失败不占键：换正确参数可重试同键
        ValveSeal renewed = sealService.reseal(1L, "req-r-v", "SEAL-0002", 0, "赵六", "孙七");
        assertThat(renewed.sealNo()).isEqualTo("SEAL-0002");
    }

    // ---------- 结构化指纹 ----------

    @Test
    void 结构化指纹对含分隔符的合法文本无歧义() {
        // 简单拼接会把 ["A|B","C"] 与 ["A","B|C"] 混为一谈，长度前缀编码必须区分
        assertThat(Fingerprints.of("A|B", "C")).isNotEqualTo(Fingerprints.of("A", "B|C"));
        assertThat(Fingerprints.of("AB")).isNotEqualTo(Fingerprints.of("A", "B"));
        assertThat(Fingerprints.of("1:2", "3")).isNotEqualTo(Fingerprints.of("1", "2:3"));
        // null 与空串、缺失字段区分
        assertThat(Fingerprints.of("A", (String) null)).isNotEqualTo(Fingerprints.of("A", ""));
        assertThat(Fingerprints.of("A", (String) null)).isNotEqualTo(Fingerprints.of("A"));
        // 同参稳定
        assertThat(Fingerprints.of("X", "Y")).isEqualTo(Fingerprints.of("X", "Y"));
    }
}
