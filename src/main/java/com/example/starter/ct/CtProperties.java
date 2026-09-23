package com.example.starter.ct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 证书透明日志配置。
 */
@Component
@ConfigurationProperties(prefix = "ct")
public class CtProperties {

    private final Observer observer = new Observer();

    public Observer getObserver() {
        return observer;
    }

    /**
     * 观察者验签配置。
     */
    public static class Observer {

        /** 合成 Ed25519 公钥（X.509，Base64）；为空时验签服务不可用。 */
        private String publicKey = "";

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }
    }
}
