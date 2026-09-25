package com.example.starter.cutover.init;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 合成基础数据初始化：业务系统、设备、设备-业务关联。
 * 仅在内存库为空时写入，全部为合成数据，JVM 退出后随内存库消失。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SyntheticDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM device", Integer.class);
        if (count != null && count > 0) {
            return;
        }

        jdbc.update("INSERT INTO biz_system(system_code, system_name) VALUES (?,?)",
                "BIZ-PAY", "支付系统");
        jdbc.update("INSERT INTO biz_system(system_code, system_name) VALUES (?,?)",
                "BIZ-ORDER", "订单系统");
        jdbc.update("INSERT INTO biz_system(system_code, system_name) VALUES (?,?)",
                "BIZ-RISK", "风控系统");
        jdbc.update("INSERT INTO biz_system(system_code, system_name) VALUES (?,?)",
                "BIZ-LEDGER", "账务系统");

        jdbc.update("INSERT INTO device(device_code, device_name) VALUES (?,?)",
                "DEV-CORE-SW-01", "核心交换机01");
        jdbc.update("INSERT INTO device(device_code, device_name) VALUES (?,?)",
                "DEV-CORE-SW-02", "核心交换机02");
        jdbc.update("INSERT INTO device(device_code, device_name) VALUES (?,?)",
                "DEV-DB-01", "核心数据库01");
        jdbc.update("INSERT INTO device(device_code, device_name) VALUES (?,?)",
                "DEV-FW-01", "边界防火墙01");

        // 设备 -> 关联业务（影响分析清单依据，须列出全部关联业务）
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-CORE-SW-01", "BIZ-PAY");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-CORE-SW-01", "BIZ-ORDER");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-CORE-SW-01", "BIZ-RISK");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-CORE-SW-02", "BIZ-ORDER");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-CORE-SW-02", "BIZ-LEDGER");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-DB-01", "BIZ-PAY");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-DB-01", "BIZ-LEDGER");
        jdbc.update("INSERT INTO device_biz(device_code, system_code) VALUES (?,?)",
                "DEV-FW-01", "BIZ-RISK");
    }
}
