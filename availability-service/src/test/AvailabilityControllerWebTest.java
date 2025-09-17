package com.example.availabilityservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AvailabilityController.class)
@AutoConfigureMockMvc(addFilters = false) // bypass security filters for slice test
class AvailabilityControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockBean
    AvailabilityService availabilityService;

    @Test
    void happyPath_returns200() throws Exception {
        when(availabilityService.checkAvailability("deluxe-101",
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03")))
                .thenReturn(List.of());

        mvc.perform(get("/api/availability/deluxe-101")
                .param("startDate", "2025-01-01")
                .param("endDate", "2025-01-03"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidDates_returns400() throws Exception {
        mvc.perform(get("/api/availability/deluxe-101")
                .param("startDate", "2025-01-03")
                .param("endDate", "2025-01-01"))
                .andExpect(status().isBadRequest());
    }
}
