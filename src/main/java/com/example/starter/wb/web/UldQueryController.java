package com.example.starter.wb.web;

import com.example.starter.wb.domain.UldOccupationRow;
import com.example.starter.wb.exception.NotFoundException;
import com.example.starter.wb.service.AssemblyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 集装器实物占用查询：追溯编号当前被哪个未关闭航班占用。
 */
@RestController
@RequestMapping("/api/uld")
public class UldQueryController {

    private final AssemblyService assemblyService;

    public UldQueryController(AssemblyService assemblyService) {
        this.assemblyService = assemblyService;
    }

    @GetMapping("/{uldNumber}/occupation")
    public UldOccupationRow occupation(@PathVariable String uldNumber) {
        return assemblyService.getOccupation(uldNumber)
                .orElseThrow(() -> new NotFoundException("集装器 " + uldNumber + " 当前未被占用"));
    }
}
