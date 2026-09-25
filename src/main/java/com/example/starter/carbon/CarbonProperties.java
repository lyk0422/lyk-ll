package com.example.starter.carbon;

import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 履约配置。
 *
 * @param deadline 履约截止时刻（UTC）；恰在该时刻到达的申请仍受理，之后一律拒绝
 */
@ConfigurationProperties(prefix = "carbon.compliance")
public record CarbonProperties(Instant deadline) {
}
