package com.example.starter.bonded;

import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.ItemRequest;
import com.example.starter.bonded.dto.TransferView;
import com.example.starter.bonded.dto.VerifyItemRequest;
import com.example.starter.bonded.dto.VerifyRequest;
import com.example.starter.bonded.service.TransferService;
import com.example.starter.bonded.web.ApiBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 转关主流程与失败分支测试：申请幂等、核注原子性、分批核销、结案终态、撤销。
 */
class TransferServiceTest extends BaseIntegrationTest {

    @Autowired
    private TransferService service;

    private ApplyTransferRequest applyReq(String requestId, String enterprise, long qty1, long qty2) {
        return new ApplyTransferRequest(requestId, enterprise, "WH-A", "WH-B", List.of(
                new ItemRequest("HS-1001", qty1, new BigDecimal("10.50")),
                new ItemRequest("HS-1002", qty2, new BigDecimal("20.00"))));
    }

    private String applyAndEndorseAndDepart(String requestId, long qty1, long qty2) {
        service.apply(applyReq(requestId, "ENT-001", qty1, qty2));
        service.endorse(requestId);
        service.depart(requestId);
        return requestId;
    }

    @Test
    @DisplayName("申请成功：状态 APPLIED，留痕一条，库存未动")
    void applyCreatesOrder() {
        var result = service.apply(applyReq("REQ-1", "ENT-001", 100, 50));
        assertThat(result.replayed()).isFalse();
        assertThat(statusOf("REQ-1")).isEqualTo("APPLIED");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(1000L, 0L);
        TransferView view = service.get("REQ-1");
        assertThat(view.items()).hasSize(2);
        assertThat(view.history()).hasSize(1);
        assertThat(view.history().get(0).toStatus()).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("申请幂等：同键同参返回首次结果；同键异参返回 409 且不改单据")
    void applyIdempotency() {
        var first = service.apply(applyReq("REQ-2", "ENT-001", 100, 50));
        var replay = service.apply(applyReq("REQ-2", "ENT-001", 100, 50));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bodyJson()).isEqualTo(first.bodyJson());
        // 商品项顺序不同但规范化后指纹一致，仍视为同参
        var reordered = new ApplyTransferRequest("REQ-2", "ENT-001", "WH-A", "WH-B", List.of(
                new ItemRequest("HS-1002", 50L, new BigDecimal("20.00")),
                new ItemRequest("HS-1001", 100L, new BigDecimal("10.50"))));
        assertThat(service.apply(reordered).replayed()).isTrue();
        // 异参：数量不同 -> 409
        assertThatThrownBy(() -> service.apply(applyReq("REQ-2", "ENT-001", 101, 50)))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf("REQ-2")).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("申请校验：企业冻结时拒绝新申请；起运仓=目的仓拒绝；不存在的企业/仓库拒绝")
    void applyValidation() {
        jdbc.update("UPDATE enterprise SET frozen = 1 WHERE id = 'ENT-001'");
        assertThatThrownBy(() -> service.apply(applyReq("REQ-F1", "ENT-001", 1, 1)))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE enterprise SET frozen = 0 WHERE id = 'ENT-001'");
        var sameWarehouse = new ApplyTransferRequest("REQ-F2", "ENT-001", "WH-A", "WH-A",
                List.of(new ItemRequest("HS-1001", 1L, new BigDecimal("1"))));
        assertThatThrownBy(() -> service.apply(sameWarehouse))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.apply(applyReq("REQ-F3", "ENT-404", 1, 1)))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("核注原子性：库存冻结扣减与状态迁移同事务；库存不足时整体回滚")
    void endorseAtomicity() {
        service.apply(applyReq("REQ-3", "ENT-001", 100, 50));
        TransferView view = service.endorse("REQ-3");
        assertThat(view.status()).isEqualTo("ENDORSED");
        assertThat(view.deadline()).isEqualTo(T0.plusSeconds(48 * 3600));
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 100L);
        assertThat(inventoryOf("WH-A", "HS-1002")).containsExactly(450L, 50L);

        // 库存不足：HS-1001 申请 2000 > 可用 900，核注失败且库存与状态均不变
        service.apply(new ApplyTransferRequest("REQ-4", "ENT-001", "WH-A", "WH-B",
                List.of(new ItemRequest("HS-1001", 2000L, new BigDecimal("1")))));
        assertThatThrownBy(() -> service.endorse("REQ-4"))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf("REQ-4")).isEqualTo("APPLIED");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 100L);
    }

    @Test
    @DisplayName("分批部分核销：累计不超核注量，恰好核满即结案，结案后拒绝新批次")
    void partialVerifyAndClose() {
        applyAndEndorseAndDepart("REQ-5", 100, 50);
        // 第一批：部分核销
        var r1 = service.verify("REQ-5", new VerifyRequest("B-1",
                List.of(new VerifyItemRequest("HS-1001", 40L))));
        assertThat(r1.replayed()).isFalse();
        TransferView v1 = service.get("REQ-5");
        assertThat(v1.status()).isEqualTo("IN_TRANSIT");
        assertThat(v1.items()).filteredOn(i -> i.hsCode().equals("HS-1001"))
                .singleElement().extracting(TransferView.ItemView::verifiedQty).isEqualTo(40L);
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 60L);

        // 第二批：核销剩余全部 -> 结案
        service.verify("REQ-5", new VerifyRequest("B-2", List.of(
                new VerifyItemRequest("HS-1001", 60L),
                new VerifyItemRequest("HS-1002", 50L))));
        TransferView v2 = service.get("REQ-5");
        assertThat(v2.status()).isEqualTo("CLOSED");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 0L);
        assertThat(inventoryOf("WH-A", "HS-1002")).containsExactly(450L, 0L);

        // 结案为终态：同键重放返回首次结果，新批次拒绝
        var replay = service.verify("REQ-5", new VerifyRequest("B-2", List.of(
                new VerifyItemRequest("HS-1001", 60L),
                new VerifyItemRequest("HS-1002", 50L))));
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> service.verify("REQ-5", new VerifyRequest("B-3",
                List.of(new VerifyItemRequest("HS-1001", 1L)))))
                .isInstanceOf(ApiBusinessException.class)
                .hasMessageContaining("结案");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 0L);
    }

    @Test
    @DisplayName("核销不超余量：单批超量整批拒绝，累计超量同样拒绝，且核销税号必须属于单据")
    void verifyNotExceedQuota() {
        applyAndEndorseAndDepart("REQ-6", 100, 50);
        // 单批超量：整批拒绝，两个税号都不核销
        assertThatThrownBy(() -> service.verify("REQ-6", new VerifyRequest("B-1", List.of(
                new VerifyItemRequest("HS-1001", 10L),
                new VerifyItemRequest("HS-1002", 51L)))))
                .isInstanceOf(ApiBusinessException.class)
                .hasMessageContaining("余量");
        TransferView v = service.get("REQ-6");
        assertThat(v.items()).allSatisfy(i -> assertThat(i.verifiedQty()).isZero());
        assertThat(inventoryOf("WH-A", "HS-1002")).containsExactly(450L, 50L);

        // 先核销 90，再核销 11 -> 累计 101 > 100，拒绝
        service.verify("REQ-6", new VerifyRequest("B-2", List.of(new VerifyItemRequest("HS-1001", 90L))));
        assertThatThrownBy(() -> service.verify("REQ-6", new VerifyRequest("B-3",
                List.of(new VerifyItemRequest("HS-1001", 11L)))))
                .isInstanceOf(ApiBusinessException.class)
                .hasMessageContaining("余量");
        // 未知税号拒绝
        assertThatThrownBy(() -> service.verify("REQ-6", new VerifyRequest("B-4",
                List.of(new VerifyItemRequest("HS-9999", 1L)))))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("核销幂等：同 batchRequestId 同参重放返回首次结果，异参返回 409，均不再变动库存")
    void verifyIdempotency() {
        applyAndEndorseAndDepart("REQ-7", 100, 50);
        var first = service.verify("REQ-7", new VerifyRequest("B-1",
                List.of(new VerifyItemRequest("HS-1001", 40L))));
        var replay = service.verify("REQ-7", new VerifyRequest("B-1",
                List.of(new VerifyItemRequest("HS-1001", 40L))));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bodyJson()).isEqualTo(first.bodyJson());
        assertThatThrownBy(() -> service.verify("REQ-7", new VerifyRequest("B-1",
                List.of(new VerifyItemRequest("HS-1001", 41L)))))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 60L);
    }

    @Test
    @DisplayName("撤销：核注前撤销不动库存；核注后（在途前）撤销原子恢复库存；在途后拒绝撤销")
    void cancelRestoresInventory() {
        // 核注前撤销
        service.apply(applyReq("REQ-8", "ENT-001", 100, 50));
        TransferView v1 = service.cancel("REQ-8");
        assertThat(v1.status()).isEqualTo("CANCELLED");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(1000L, 0L);

        // 核注后撤销：恢复冻结库存
        service.apply(applyReq("REQ-9", "ENT-001", 100, 50));
        service.endorse("REQ-9");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(900L, 100L);
        TransferView v2 = service.cancel("REQ-9");
        assertThat(v2.status()).isEqualTo("CANCELLED");
        assertThat(inventoryOf("WH-A", "HS-1001")).containsExactly(1000L, 0L);
        assertThat(inventoryOf("WH-A", "HS-1002")).containsExactly(500L, 0L);

        // 在途后拒绝撤销
        applyAndEndorseAndDepart("REQ-10", 10, 10);
        assertThatThrownBy(() -> service.cancel("REQ-10"))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        // 已撤销为终态：再核注拒绝
        assertThatThrownBy(() -> service.endorse("REQ-9"))
                .isInstanceOf(ApiBusinessException.class)
                .extracting(e -> ((ApiBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("状态留痕：全链路状态变更按序可查")
    void historyTrail() {
        applyAndEndorseAndDepart("REQ-11", 10, 10);
        service.verify("REQ-11", new VerifyRequest("B-1", List.of(
                new VerifyItemRequest("HS-1001", 10L),
                new VerifyItemRequest("HS-1002", 10L))));
        TransferView view = service.get("REQ-11");
        assertThat(view.history()).extracting(TransferView.HistoryView::toStatus)
                .containsExactly("APPLIED", "ENDORSED", "IN_TRANSIT", "CLOSED");
    }
}
