package com.example.starter.discrepancy;

import com.example.starter.discrepancy.DiscrepancyService.DiscrepancyView;
import com.example.starter.discrepancy.DiscrepancyService.RespondDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.RespondDiscrepancyResponse;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyRequest;
import com.example.starter.discrepancy.DiscrepancyService.SubmitDiscrepancyResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购方对账差异单接口：提交、销方处置与详情查询。
 */
@RestController
@RequestMapping("/api/discrepancies")
public class DiscrepancyController {

    private final DiscrepancyService discrepancyService;

    public DiscrepancyController(DiscrepancyService discrepancyService) {
        this.discrepancyService = discrepancyService;
    }

    @PostMapping
    public SubmitDiscrepancyResponse submit(@RequestBody SubmitDiscrepancyRequest request) {
        return discrepancyService.submit(request);
    }

    @PostMapping("/{discrepancyNo}/respond")
    public RespondDiscrepancyResponse respond(@PathVariable String discrepancyNo,
                                              @RequestBody RespondDiscrepancyRequest request) {
        return discrepancyService.respond(discrepancyNo, request);
    }

    @GetMapping("/{discrepancyNo}")
    public DiscrepancyView getDiscrepancy(@PathVariable String discrepancyNo) {
        return discrepancyService.getDiscrepancy(discrepancyNo);
    }
}
