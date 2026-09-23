package com.github.xingjianluo.onchainriskenginejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 引擎本地配置。
 */
@ConfigurationProperties(prefix = "risk.engine")
public class RiskProperties {

    /** 启动时是否运行本地模拟事件流。 */
    private boolean simulateOnStartup = false;

    /** 默认告警阈值。 */
    private int defaultAlertThreshold = 60;

    public boolean isSimulateOnStartup() {
        return simulateOnStartup;
    }

    public void setSimulateOnStartup(boolean simulateOnStartup) {
        this.simulateOnStartup = simulateOnStartup;
    }

    public int getDefaultAlertThreshold() {
        return defaultAlertThreshold;
    }

    public void setDefaultAlertThreshold(int defaultAlertThreshold) {
        this.defaultAlertThreshold = defaultAlertThreshold;
    }
}
