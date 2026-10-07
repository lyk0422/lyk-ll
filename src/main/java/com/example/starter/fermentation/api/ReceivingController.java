package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptRequest;
import com.example.starter.fermentation.api.ReceivingDtos.MergeReceiptResponse;
import com.example.starter.fermentation.api.ReceivingDtos.ReceivingTankResponse;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterReceivingTankRequest;
import com.example.starter.fermentation.api.ReceivingDtos.RegisterSourceVolumeRequest;
import com.example.starter.fermentation.api.ReceivingDtos.SourceVolumeResponse;
import com.example.starter.fermentation.api.ReceivingDtos.UpdateCapacityRequest;
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

import java.util.List;

/**
 * 合批接收 API：接收罐登记与调容、源批实际升数台账、合批接收。
 * 所有写接口以 requestId 幂等：同键同参重放首次结果，同键异参 409，失败不占键。
 */
@RestController
@RequestMapping("/api")
public class ReceivingController {

    private final ReceivingService receivingService;

    public ReceivingController(ReceivingService receivingService) {
        this.receivingService = receivingService;
    }

    @PostMapping("/receiving-tanks")
    public ResponseEntity<String> registerTank(@Valid @RequestBody RegisterReceivingTankRequest request) {
        return respond(receivingService.registerTank(request));
    }

    @PutMapping("/receiving-tanks/{tankCode}/capacity")
    public ResponseEntity<String> updateCapacity(@PathVariable String tankCode,
                                                 @Valid @RequestBody UpdateCapacityRequest request) {
        return respond(receivingService.updateCapacity(tankCode, request));
    }

    @GetMapping("/receiving-tanks/{tankCode}")
    public ReceivingTankResponse getTank(@PathVariable String tankCode) {
        return receivingService.getTank(tankCode);
    }

    @GetMapping("/receiving-tanks/{tankCode}/receipts")
    public List<MergeReceiptResponse> getReceipts(@PathVariable String tankCode) {
        return receivingService.getReceipts(tankCode);
    }

    @PostMapping("/batches/{id}/sake-volume")
    public ResponseEntity<String> registerSourceVolume(
            @PathVariable long id, @Valid @RequestBody RegisterSourceVolumeRequest request) {
        return respond(receivingService.registerSourceVolume(id, request));
    }

    @GetMapping("/batches/{id}/sake-volume")
    public SourceVolumeResponse getSourceVolume(@PathVariable long id) {
        return receivingService.getSourceVolume(id);
    }

    @PostMapping("/merge-receipts")
    public ResponseEntity<String> merge(@Valid @RequestBody MergeReceiptRequest request) {
        return respond(receivingService.merge(request));
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
