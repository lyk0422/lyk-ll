package com.example.starter;

import com.example.starter.arbitration.ArbitrationApplication;

/**
 * 历史启动入口，委托到仲裁服务入口 {@link ArbitrationApplication}。
 */
public class StarterApplication {

    public static void main(String[] args) {
        ArbitrationApplication.main(args);
    }
}
