import http from "k6/http";
import { check, sleep } from "k6";

// Read base URLs from environment (fallback to localhost for local CLI runs)
const BOOKING = __ENV.BASE_URL_BOOKING || "http://localhost:8085";
const AVAIL = __ENV.BASE_URL_AVAIL || "http://localhost:8086";

export const options = { vus: 10, duration: "30s" };

const auth =
  "Basic " +
  (function () {
    // "user:password" base64, inline to avoid needing k6/encoding
    return "dXNlcjpwYXNzd29yZA==";
  })();

export default function () {
  const start = "2025-01-20";
  const end = "2025-01-22";

  const b = http.post(
    `${BOOKING}/api/bookings?roomId=deluxe-101&startDate=${start}&endDate=${end}`,
    null,
    { headers: { Authorization: auth } }
  );
  check(b, { "booking 201": (r) => r.status === 201 });

  const a = http.get(
    `${AVAIL}/api/availability/deluxe-101?startDate=${start}&endDate=${end}`,
    { headers: { Authorization: auth } }
  );
  check(a, { "availability 200": (r) => r.status === 200 });

  sleep(1);
}
