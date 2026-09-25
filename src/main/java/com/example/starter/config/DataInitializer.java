package com.example.starter.config;

import com.example.starter.repository.MerchantRepository;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 合成数据初始化：写入演示商户与费率，仅用于本地 H2 演示。 */
@Configuration
public class DataInitializer {

    @Bean
    public ApplicationRunner seedDataRunner(MerchantRepository merchantRepository, Clock clock) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                merchantRepository.insertIfAbsent("M001", "合成商户甲", new BigDecimal("0.006000"), clock.instant());
                merchantRepository.insertIfAbsent("M002", "合成商户乙", new BigDecimal("0.010000"), clock.instant());
            }
        };
    }
}
