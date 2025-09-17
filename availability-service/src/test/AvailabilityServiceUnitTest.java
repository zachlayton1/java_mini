package com.example.availabilityservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceUnitTest {

    @Mock
    AvailabilityRepository availabilityRepository;
    @Mock
    ProcessedEventRepository processedEventRepository;

    @InjectMocks
    AvailabilityService service;

    @Test
    void updatesThreeDays_thenMarksProcessed() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 1, 3); // 3 days inclusive
        var event = new BookingEvent(42L, "deluxe-101", start, end, "BOOKING_CREATED");

        when(processedEventRepository.existsByConsumerGroupAndStreamId(anyString(), anyString()))
                .thenReturn(false);
        // simulate "no rows yet", so service creates them
        when(availabilityRepository.findByRoomIdAndAvailableDate(eq("deluxe-101"), any()))
                .thenReturn(null);

        service.updateAvailabilityForBooking(event, "1700000000-0");

        // saved 3 times (for each day)
        verify(availabilityRepository, times(3)).saveAndFlush(any(Availability.class));

        // captured rooms are incremented to 1
        ArgumentCaptor<Availability> captor = ArgumentCaptor.forClass(Availability.class);
        verify(availabilityRepository, atLeastOnce()).saveAndFlush(captor.capture());
        List<Availability> saved = captor.getAllValues();
        assertThat(saved).hasSize(3);
        assertThat(saved).allSatisfy(a -> {
            assertThat(a.getRoomId()).isEqualTo("deluxe-101");
            assertThat(a.getBookedRooms()).isEqualTo(1);
        });

        // processed-event recorded
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void duplicateStreamId_isNoOp() {
        var start = LocalDate.of(2025, 1, 1);
        var end = LocalDate.of(2025, 1, 1);
        var event = new BookingEvent(1L, "deluxe-101", start, end, "BOOKING_CREATED");

        when(processedEventRepository.existsByConsumerGroupAndStreamId(anyString(), anyString()))
                .thenReturn(true);

        service.updateAvailabilityForBooking(event, "dup-1");

        verifyNoInteractions(availabilityRepository);
        verify(processedEventRepository, never()).save(any());
    }
}
