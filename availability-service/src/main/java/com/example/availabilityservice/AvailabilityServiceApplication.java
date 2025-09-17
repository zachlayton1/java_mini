package com.example.availabilityservice;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.security.config.Customizer.withDefaults;

// region Application
@SpringBootApplication
public class AvailabilityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AvailabilityServiceApplication.class, args);
    }

    // Seed a single row so GET has data on first run (optional)
    @Bean
    CommandLineRunner seed(AvailabilityRepository repository) {
        return args -> {
            if (repository.count() == 0) {
                var availability = new Availability(null, "deluxe-101",
                        LocalDate.now().plusDays(10), 5, 1, null);
                repository.save(availability);
                LoggerFactory.getLogger(AvailabilityServiceApplication.class)
                        .info("Initial availability data created.");
            }
        };
    }
}
// endregion

// region Domain DTOs
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
class Availability {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String roomId;

    @Column(nullable = false)
    private LocalDate availableDate;

    @Column(nullable = false)
    private int totalRooms;

    @Column(nullable = false)
    private int bookedRooms;

    @Version
    private Long version; // optimistic locking
}

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
class ProcessedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String consumerGroup;

    @Column(nullable = false)
    private String streamId;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class BookingEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long bookingId;
    private String roomId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String eventType; // e.g., BOOKING_CREATED
}
// endregion

// region Repositories
interface AvailabilityRepository extends JpaRepository<Availability, Long> {
    List<Availability> findByRoomIdAndAvailableDateBetween(String roomId, LocalDate startDate, LocalDate endDate);

    Availability findByRoomIdAndAvailableDate(String roomId, LocalDate date);
}

interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByConsumerGroupAndStreamId(String consumerGroup, String streamId);
}
// endregion

// region Service
@org.springframework.stereotype.Service
@RequiredArgsConstructor
class AvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);
    private static final String GROUP = "availability"; // Redis consumer group name
    private static final int DEFAULT_TOTAL_ROOMS = 5; // demo default (move to config if needed)

    private final AvailabilityRepository availabilityRepository;
    private final ProcessedEventRepository processedEventRepository;

    /** Idempotent, optimistic-lock-safe update for a booking-created event. */
    @org.springframework.transaction.annotation.Transactional
    public void updateAvailabilityForBooking(BookingEvent event, String streamId) {
        // Idempotency: skip if we've seen this stream message
        if (processedEventRepository.existsByConsumerGroupAndStreamId(GROUP, streamId)) {
            log.info("Skip duplicate streamId {}", streamId);
            return;
        }

        LocalDate current = event.getStartDate();
        while (!current.isAfter(event.getEndDate())) {
            boolean saved = false;
            int attempts = 0;
            while (!saved && attempts < 3) {
                attempts++;
                Availability day = availabilityRepository.findByRoomIdAndAvailableDate(event.getRoomId(), current);
                if (day == null) {
                    day = new Availability(null, event.getRoomId(), current, DEFAULT_TOTAL_ROOMS, 0, null);
                }
                day.setBookedRooms(day.getBookedRooms() + 1);

                try {
                    availabilityRepository.saveAndFlush(day);
                    saved = true;
                } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
                    log.info("Optimistic lock on {} {} (attempt {}/3)", event.getRoomId(), current, attempts);
                }
            }
            if (!saved)
                throw new IllegalStateException("Failed to persist availability for " + current);
            current = current.plusDays(1);
        }

        processedEventRepository.save(new ProcessedEvent(null, GROUP, streamId));
        log.info("Processed booking {} (streamId {}) from {} to {}",
                event.getBookingId(), streamId, event.getStartDate(), event.getEndDate());
    }

    public List<Availability> checkAvailability(String roomId, LocalDate startDate, LocalDate endDate) {
        return availabilityRepository.findByRoomIdAndAvailableDateBetween(roomId, startDate, endDate);
    }
}
// endregion

// region API
@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
@Validated
class AvailabilityController {
    private final AvailabilityService availabilityService;

    @GetMapping("/{roomId}")
    public ResponseEntity<List<Availability>> getAvailability(
            @PathVariable @NotBlank String roomId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        return ResponseEntity.ok(availabilityService.checkAvailability(roomId, startDate, endDate));
    }
}
// endregion

// region Error Handling
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

@RestControllerAdvice
class ValidationErrors {
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<?> onValidation(org.springframework.web.bind.MethodArgumentNotValidException e) {
        var errs = e.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        org.springframework.context.support.DefaultMessageSourceResolvable::getDefaultMessage,
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of("errors", errs));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<?> onConstraint(jakarta.validation.ConstraintViolationException e) {
        var errs = e.getConstraintViolations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        jakarta.validation.ConstraintViolation::getMessage,
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of("errors", errs));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> onTypeMismatch(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid parameter type", "details", e.getMessage()));
    }
}
// endregion

// region Security
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
        UserDetails user = User.withUsername("user")
                .password(encoder.encode("password"))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
// endregion

// region Redis Streams
@Configuration
@RequiredArgsConstructor
class RedisStreamConfig {

    private static final String STREAM = "booking-events";
    private static final String GROUP = "availability";
    private static final String CONSUMER = "availability-1";

    /** Ensure the consumer group exists. */
    @Bean
    InitializingBean ensureGroup(StringRedisTemplate redis) {
        return () -> {
            try {
                redis.opsForStream().createGroup(STREAM, ReadOffset.latest(), GROUP);
            } catch (Exception ignored) {
                // group likely exists
            }
        };
    }

    /** Run the listener container that reads from the Redis Stream. */
    @Bean
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamContainer(
            RedisConnectionFactory cf,
            BookingEventListener listener) {

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .<String, MapRecord<String, String, String>>builder()
                .pollTimeout(Duration.ofMillis(250))
                .build();

        var container = StreamMessageListenerContainer.create(cf, options);

        container.receive(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()),
                listener);

        container.start();
        return container;
    }
}

@Component
@RequiredArgsConstructor
class BookingEventListener implements StreamListener<String, MapRecord<String, String, String>> {
    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);
    private final AvailabilityService availabilityService;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        RecordId rid = message.getId();
        String streamId = (rid != null ? rid.getValue() : "unknown");
        try {
            Map<String, String> map = message.getValue();

            BookingEvent event = new BookingEvent(
                    parseLong(map.get("bookingId")),
                    map.getOrDefault("roomId", ""),
                    LocalDate.parse(map.get("startDate")),
                    LocalDate.parse(map.get("endDate")),
                    map.getOrDefault("eventType", ""));

            log.info("Received '{}' for booking ID {} (streamId {})",
                    event.getEventType(), event.getBookingId(), streamId);

            if ("BOOKING_CREATED".equals(event.getEventType())) {
                availabilityService.updateAvailabilityForBooking(event, streamId);
            } else {
                log.debug("Ignoring eventType '{}' (streamId {})", event.getEventType(), streamId);
            }

        } catch (Exception e) {
            log.warn("Failed to process stream message {}: {}", streamId, e.toString());
        }
    }

    private Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }
}
// endregion
