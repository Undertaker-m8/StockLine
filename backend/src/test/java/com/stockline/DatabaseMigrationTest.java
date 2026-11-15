package com.stockline;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("stockline_test")
                    .withUsername("stockline")
                    .withPassword("stockline");

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void shouldCreateAllFourteenTables() throws Exception {
        Set<String> expectedTables = Set.of(
                "location",
                "app_user",
                "product",
                "stock_level",
                "stock_movement",
                "sale",
                "sale_line",
                "transfer",
                "transfer_line",
                "supplier",
                "purchase_order",
                "purchase_order_line",
                "stock_snapshot_daily",
                "weekly_report"
        );

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                     """)) {

            Set<String> actualTables = readStringSet(resultSet);

            assertThat(actualTables)
                    .containsExactlyInAnyOrderElementsOf(expectedTables);
        }
    }

    @Test
    void shouldRegisterFlywayVersionOneAsSuccessful() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version, success
                     FROM flyway_schema_history
                     WHERE version = '1'
                     """)) {

            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("version")).isEqualTo("1");
            assertThat(resultSet.getBoolean("success")).isTrue();
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void shouldCreateEssentialDatabaseConstraints() throws Exception {
        Set<String> expectedConstraints = Set.of(
                "chk_stock_level_quantity",
                "chk_stock_movement_quantity_delta",
                "chk_sale_total_minor",
                "chk_transfer_distinct_locations",
                "chk_transfer_line_received",
                "chk_purchase_order_line_received",
                "chk_stock_snapshot_quantity",
                "uq_weekly_report_period_location"
        );

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT conname
                     FROM pg_constraint
                     WHERE connamespace = 'public'::regnamespace
                     """)) {

            Set<String> actualConstraints = readStringSet(resultSet);

            assertThat(actualConstraints)
                    .containsAll(expectedConstraints);
        }
    }

    @Test
    void shouldProtectStockMovementsFromUpdateAndDelete() throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT tgname
                     FROM pg_trigger
                     WHERE tgrelid = 'stock_movement'::regclass
                       AND NOT tgisinternal
                     """)) {

            Set<String> triggers = readStringSet(resultSet);

            assertThat(triggers)
                    .contains("trg_stock_movement_immutable");
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private Set<String> readStringSet(ResultSet resultSet) throws Exception {
        Set<String> values = new java.util.HashSet<>();

        while (resultSet.next()) {
            values.add(resultSet.getString(1));
        }

        return values;
    }
}