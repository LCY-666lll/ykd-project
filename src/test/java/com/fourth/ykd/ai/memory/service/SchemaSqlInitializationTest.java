package com.fourth.ykd.ai.memory.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSqlInitializationTest {

    @Test
    void shouldCreateMemoryIndexOutboxTableAndDueTaskIndex() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(dataSource);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master "
                        + "WHERE type = 'table' AND name = 'memory_index_outbox'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master "
                        + "WHERE type = 'index' AND name = 'idx_memory_index_outbox_due'",
                Integer.class
        )).isEqualTo(1);
    }
}
