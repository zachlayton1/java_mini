package com.example.availabilityservice;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class AvailabilityIntegrationTest {

    private static final String STREAM = "booking-events";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hotel")
            .withUsername("postgres")
            .withPassword("pass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // Postgres
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Redis
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    StringRedisTemplate redisTemplate;
    @Autowired
    AvailabilityRepository availabilityRepository;

    @BeforeEach
    void ensureGroupExists() {
        try {
            // create consumer group if missing (stream might not exist yet)
            redisTemplate.opsForStream().createGroup(STREAM,
                    org.springframework.data.redis.connection.stream.ReadOffset.latest(), "availability");
        } catch (Exception ignored) {
            // group likely exists
        }
    }

    @Test
    void publishingBookingEvent_updatesAvailabilityRows() {
        var start = LocalDate.of(2025, 1, 10);
        var end = LocalDate.of(2025, 1, 12);
        var days = 3;

        Map<String, String> map = Map.of(
                "bookingId", "1",
                "roomId", "deluxe-101",
                "startDate", start.toString(),
                "endDate", end.toString(),
                "eventType", "BOOKING_CREATED");

        MapRecord<String, String, String> rec = StreamRecords.newRecord()
                .in(STREAM)
                .ofMap(map);

        redisTemplate.opsForStream().add(rec);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Availability> rows = availabilityRepository
                    .findByRoomIdAndAvailableDateBetween("deluxe-101", start, end);
            assertThat(rows).hasSize(days);
            assertThat(rows).allSatisfy(a -> assertThat(a.getBookedRooms()).isGreaterThanOrEqualTo(1));
        });
    }
}
