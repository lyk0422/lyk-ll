package com.example.starter.difference;

import java.time.Instant;
import java.util.Map;

import com.example.starter.difference.DifferenceService.DifferenceView;
import com.example.starter.difference.DifferenceService.RespondDifferenceRequest;
import com.example.starter.difference.DifferenceService.RespondDifferenceResponse;
import com.example.starter.difference.DifferenceService.SubmitDifferenceRequest;
import com.example.starter.difference.DifferenceService.SubmitDifferenceResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购方对账差异单接口：登记、销方处置、超时升级与详情查询。
 */
@RestController
@RequestMapping("/api/differences")
public class DifferenceController {

    private final DifferenceService differenceService;

    public DifferenceController(DifferenceService differenceService) {
        this.differenceService = differenceService;
    }

    @PostMapping
    public SubmitDifferenceResponse submit(@RequestBody SubmitDifferenceRequest request) {
        return differenceService.submit(request);
    }

    @PostMapping("/{differenceNo}/respond")
    public RespondDifferenceResponse respond(@PathVariable String differenceNo,
                                             @RequestBody RespondDifferenceRequest request) {
        return differenceService.respond(differenceNo, request);
    }

    @PostMapping("/escalations")
    public Map<String, Integer> escalateOverdue() {
        return Map.of("escalated", differenceService.escalateOverdue(Instant.now()));
    }

    @GetMapping("/{differenceNo}")
    public DifferenceView getDifference(@PathVariable String differenceNo) {
        return differenceService.getDifference(differenceNo);
    }
}
