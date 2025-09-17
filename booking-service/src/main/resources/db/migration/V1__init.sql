-- Create and use dedicated schema for booking-service objects
CREATE SCHEMA IF NOT EXISTS booking;

-- Main table
CREATE TABLE IF NOT EXISTS booking.booking (
  id BIGSERIAL PRIMARY KEY,
  room_id VARCHAR(128) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  status VARCHAR(32) NOT NULL
);

-- Helpful index
CREATE INDEX IF NOT EXISTS idx_booking_room ON booking.booking (room_id);
