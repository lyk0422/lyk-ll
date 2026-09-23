package com.example.starter.compiler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 编译服务配置入口；具体组件通过构造型注解被扫描装配。
 */
@Configuration
@EnableConfigurationProperties(CompilerProperties.class)
public class CompilerConfig {
}
