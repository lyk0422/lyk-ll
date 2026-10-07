package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiveRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterSourceLiquorRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterTankRequest;
import com.example.starter.fermentation.api.ReceivingDtos.UpdateTankCapacityRequest;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.ReceivingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合批接收 API：接收罐登记/容量调整、源批清酒量登记、多批合入同一接收罐。
 * 写接口以 requestId 幂等，容量调整与合批接收以 expectedVersion 做乐观并发控制。
 */
@RestController
@RequestMapping("/api")
public class ReceivingController {

    private final ReceivingService receivingService;

    public ReceivingController(ReceivingService receivingService) {
        this.receivingService = receivingService;
    }

    /** 登记合成接收罐（正数容量升数）。 */
    @PostMapping("/receiving-tanks")
    public ResponseEntity<String> registerTank(@Valid @RequestBody RegisterTankRequest request) {
        return respond(receivingService.registerTank(request));
    }

    /** 调整接收罐容量（不得缩到已接收量以下）。 */
    @PutMapping("/receiving-tanks/{tankCode}/capacity")
    public ResponseEntity<String> updateCapacity(@PathVariable String tankCode,
                                                 @Valid @RequestBody UpdateTankCapacityRequest request) {
        return respond(receivingService.updateTankCapacity(tankCode, request));
    }

    /** 查询接收罐（容量、累计接收量、版本）。 */
    @GetMapping("/receiving-tanks/{tankCode}")
    public ReceivingDtos.ReceivingTankResponse getTank(@PathVariable String tankCode) {
        return receivingService.getTank(tankCode);
    }

    /** 查询接收罐的各次合批受理（按受理次序，可逐单复算累计接收与来源总升数）。 */
    @GetMapping("/receiving-tanks/{tankCode}/merge-receipts")
    public java.util.List<ReceivingDtos.MergeReceiptResponse> listReceipts(
            @PathVariable String tankCode) {
        return receivingService.listReceiptsByTank(tankCode);
    }

    /** 给未转出且成熟度合格的源批登记一次实际清酒量（登记后冻结）。 */
    @PostMapping("/batches/{id}/source-liquor")
    public ResponseEntity<String> registerSourceLiquor(
            @PathVariable long id, @Valid @RequestBody RegisterSourceLiquorRequest request) {
        return respond(receivingService.registerSourceLiquor(id, request));
    }

    /** 查询源批清酒量台账。 */
    @GetMapping("/batches/{id}/source-liquor")
    public ReceivingDtos.SourceLiquorResponse getSourceLiquor(@PathVariable long id) {
        return receivingService.getSourceLiquor(id);
    }

    /** 合批接收：2 至 5 个同曲线版本受控源批完整合入同一接收罐。 */
    @PostMapping("/merge-receipts")
    public ResponseEntity<String> mergeReceive(@Valid @RequestBody MergeReceiveRequest request) {
        return respond(receivingService.mergeReceive(request));
    }

    /** 查询某次合批接收受理单及来源明细（可复算升数勾稽）。 */
    @GetMapping("/merge-receipts/{id}")
    public ReceivingDtos.MergeReceiptResponse getReceipt(@PathVariable long id) {
        return receivingService.getReceipt(id);
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
