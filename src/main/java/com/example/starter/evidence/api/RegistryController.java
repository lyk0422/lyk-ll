package com.example.starter.evidence.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.evidence.api.dto.RegistryVersionView;
import com.example.starter.evidence.repo.RegistryRepository;

/**
 * 领域版本号查询 API，供调用方读取预期版本。
 */
@RestController
@RequestMapping("/api/registry")
public class RegistryController {

    private final RegistryRepository registryRepository;

    public RegistryController(RegistryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    /**
     * 查询当前领域版本号。
     */
    @GetMapping("/version")
    public RegistryVersionView version() {
        return new RegistryVersionView(registryRepository.currentVersion());
    }
}
