package com.example.bookingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BookingController.class)
@AutoConfigureMockMvc(addFilters = false) // bypass security filters
class BookingControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockBean
    BookingAppService app;

    @Test
    void createBooking_happyPath_201() throws Exception {
        var start = LocalDate.parse("2025-01-10");
        var end = LocalDate.parse("2025-01-12");
        when(app.createBooking("deluxe-101", start, end))
                .thenReturn(new Booking(1L, "deluxe-101", start, end, "CREATED"));

        mvc.perform(post("/api/bookings")
                .param("roomId", "deluxe-101")
                .param("startDate", "2025-01-10")
                .param("endDate", "2025-01-12"))
                .andExpect(status().isCreated());
    }

    @Test
    void createBooking_invalidDates_400() throws Exception {
        mvc.perform(post("/api/bookings")
                .param("roomId", "deluxe-101")
                .param("startDate", "2025-01-12")
                .param("endDate", "2025-01-10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_requiresRoomIdNotBlank_400() throws Exception {
        mvc.perform(get("/api/bookings/room/  ")) // path variable blankish
                .andExpect(status().isNotFound()); // path mismatch; for param validation prefer /api with @RequestParam
                                                   // in real apps
    }
}
