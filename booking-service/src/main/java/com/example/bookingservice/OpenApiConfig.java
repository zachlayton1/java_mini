package com.example.bookingservice;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI bookingOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Booking Service API")
                .version("v1")
                .description("Create and list bookings"));
    }
}
