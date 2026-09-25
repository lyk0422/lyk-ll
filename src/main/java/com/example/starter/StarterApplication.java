package com.example.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 造纸机改品种过渡料控制服务入口。
 *
 * <p>本地默认使用嵌入式 H2（MODE=MySQL），启动时自动建表并写入合成品种主数据，
 * 不依赖外部 MySQL、Docker 或 DCS；数据仅在 JVM 生命周期内保留。</p>
 */
@SpringBootApplication
public class StarterApplication {

    /**
     * 启动改品种过渡料控制服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
