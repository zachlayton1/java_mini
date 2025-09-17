package com.example.bookingservice;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.security.config.Customizer.withDefaults;

@SpringBootApplication
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(BookingRepository repo) {
        return args -> {
            if (repo.count() == 0) {
                repo.save(new Booking(
                        null,
                        "deluxe-101",
                        LocalDate.now().plusDays(10),
                        LocalDate.now().plusDays(12),
                        "CREATED"));
            }
        };
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        // allow Swagger/OpenAPI
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // protect APIs
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
    public InMemoryUserDetailsManager users(PasswordEncoder encoder) {
        UserDetails user = User.withUsername("user")
                .password(encoder.encode("password"))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}

// region Domain
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String roomId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
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
    private String eventType; // e.g., "BOOKING_CREATED"
}

// region Repository
interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByRoomId(String roomId);
}

// region Service
@org.springframework.stereotype.Service
@RequiredArgsConstructor
class BookingAppService {
    private static final Logger log = LoggerFactory.getLogger(BookingAppService.class);
    private static final String STREAM_KEY = "booking-events";

    private final BookingRepository repository;
    private final StringRedisTemplate stringRedisTemplate; // simple strings

    public Booking createBooking(String roomId, LocalDate start, LocalDate end) {
        // 1) Persist
        Booking booking = repository.save(new Booking(null, roomId, start, end, "CREATED"));

        // 2) Publish a simple String map
        Map<String, String> map = Map.of(
                "bookingId", booking.getId().toString(),
                "roomId", roomId,
                "startDate", start.toString(),
                "endDate", end.toString(),
                "eventType", "BOOKING_CREATED");

        try {
            var record = StreamRecords.newRecord().in(STREAM_KEY).ofMap(map);
            stringRedisTemplate.opsForStream().add(record);
            log.info("Published event to {} for booking {}", STREAM_KEY, booking.getId());
        } catch (Exception e) {
            log.warn("Failed to publish to Redis stream {}: {}", STREAM_KEY, e.toString());
        }

        return booking;
    }

    public List<Booking> byRoom(String roomId) {
        return repository.findByRoomId(roomId);
    }
}

// region REST Controller
@Validated
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
class BookingController {

    private final BookingAppService app;

    @PostMapping
    public ResponseEntity<Booking> createBooking(
            @RequestParam @NotBlank String roomId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        Booking b = app.createBooking(roomId, startDate, endDate);
        return ResponseEntity.status(201).body(b);
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<List<Booking>> list(@PathVariable @NotBlank String roomId) {
        return ResponseEntity.ok(app.byRoom(roomId));
    }
}

// region Error Handling
@RestControllerAdvice
class ApiErrors {

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badReq(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<?> onValidation(org.springframework.web.bind.MethodArgumentNotValidException e) {
        var errs = e.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        org.springframework.context.support.DefaultMessageSourceResolvable::getDefaultMessage,
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(java.util.Map.of("errors", errs));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<?> onConstraint(jakarta.validation.ConstraintViolationException e) {
        var errs = e.getConstraintViolations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        jakarta.validation.ConstraintViolation::getMessage,
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(java.util.Map.of("errors", errs));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> onTypeMismatch(Exception e) {
        return ResponseEntity.badRequest()
                .body(java.util.Map.of("error", "Invalid parameter type", "details", e.getMessage()));
    }
}
