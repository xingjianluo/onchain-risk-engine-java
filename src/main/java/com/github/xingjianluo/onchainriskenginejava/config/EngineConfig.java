package com.github.xingjianluo.onchainriskenginejava.config;

import com.github.xingjianluo.onchainriskenginejava.sim.LocalEventSimulator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * 引擎装配：启用配置属性；risk.engine.simulate-on-startup=true 时，
 * 应用启动后自动跑一遍本地模拟事件流并打印输入事件与判定依据。
 */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class EngineConfig {

    private final RiskProperties properties;
    private final LocalEventSimulator simulator;

    public EngineConfig(RiskProperties properties, LocalEventSimulator simulator) {
        this.properties = properties;
        this.simulator = simulator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runSimulationOnStartup() {
        if (properties.isSimulateOnStartup()) {
            simulator.runSequential();
        }
    }
}
