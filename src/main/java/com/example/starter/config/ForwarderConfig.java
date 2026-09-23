package com.example.starter.config;

import com.example.starter.service.ForwarderProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用转发器配置属性绑定。 */
@Configuration
@EnableConfigurationProperties(ForwarderProperties.class)
public class ForwarderConfig {
}
