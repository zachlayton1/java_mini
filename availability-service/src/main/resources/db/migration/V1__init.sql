-- Schema is created by Flyway (spring.flyway.create-schemas=true and default schema=availability).
-- Create tables first-run only; Flyway ensures V1 runs once.

-- Availability per day
CREATE TABLE availability.availability (
  id BIGSERIAL PRIMARY KEY,
  room_id VARCHAR(128) NOT NULL,
  available_date DATE NOT NULL,
  total_rooms INT NOT NULL,
  booked_rooms INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
);

-- Prevent duplicates per room/day (NO IF NOT EXISTS here)
ALTER TABLE availability.availability
  ADD CONSTRAINT uniq_room_day UNIQUE (room_id, available_date);

-- Helpful index for range queries
CREATE INDEX idx_availability_room_date
  ON availability.availability(room_id, available_date);

-- Track processed stream messages for idempotency
CREATE TABLE availability.processed_event (
  id BIGSERIAL PRIMARY KEY,
  consumer_group VARCHAR(128) NOT NULL,
  stream_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP DEFAULT now(),
  CONSTRAINT uq_processed UNIQUE (consumer_group, stream_id)
);
