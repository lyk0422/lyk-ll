package com.example.starter.forwarder;

import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 转发器装配：时钟、启动恢复与定时补发/超时重排。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ForwarderProperties.class)
public class ForwarderConfig {

    /**
     * 系统时钟（测试中可替换为可控时钟）。
     */
    @Bean
    public Clock forwarderClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * 服务重启后：将未确认的 in-flight 消息恢复为待补发。
     */
    @Bean
    public ApplicationRunner forwarderRecoveryRunner(ForwarderService service) {
        return (ApplicationArguments args) -> service.recoverAfterRestart();
    }

    /**
     * 定时任务：在线时补发，并将超时未确认的 in-flight 消息重新排队。
     */
    @org.springframework.stereotype.Component
    public static class ForwarderScheduler {

        private final ForwarderService service;

        public ForwarderScheduler(ForwarderService service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "1000", initialDelayString = "1000")
        public void tick() {
            service.requeueTimedOutInFlight();
            service.drain();
        }
    }
}
