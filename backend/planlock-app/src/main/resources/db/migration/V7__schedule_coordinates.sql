-- Nullable WGS84 coordinates; legacy text-only locations remain unchanged.
ALTER TABLE schedules ADD COLUMN location_lat DOUBLE NULL AFTER location;
ALTER TABLE schedules ADD COLUMN location_lon DOUBLE NULL AFTER location_lat;
