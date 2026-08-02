package com.fourth.ykd.ai.memory.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExecutorConfigurationTest {

    @Test
    void shouldKeepMemoryManagementExecutorIndependent() {
        MemoryExecutorConfiguration configuration = new MemoryExecutorConfiguration();
        ThreadPoolTaskExecutor automaticExecutor = configuration.memoryExecutor();
        ThreadPoolTaskExecutor managementExecutor = configuration.memoryManagementExecutor();

        automaticExecutor.initialize();
        managementExecutor.initialize();

        try {
            assertThat(managementExecutor).isNotSameAs(automaticExecutor);
            assertThat(managementExecutor.getCorePoolSize()).isEqualTo(1);
            assertThat(managementExecutor.getMaxPoolSize()).isEqualTo(1);
            assertThat(managementExecutor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(1);
        } finally {
            automaticExecutor.shutdown();
            managementExecutor.shutdown();
        }
    }
}