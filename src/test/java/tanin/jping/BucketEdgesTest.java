package tanin.jping;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BucketEdgesTest {

  static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

  static List<Instant> instants(String... values) {
    return Arrays.stream(values).map(Instant::parse).toList();
  }

  @Test
  void fifteenMinuteBucketsStartOnTheQuarterHour() {
    var edges = DatabaseService.bucketEdges(
      Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T00:50:00Z"), 15, ZoneOffset.UTC
    );
    assertEquals(
      instants("2026-11-01T00:00:00Z", "2026-11-01T00:15:00Z", "2026-11-01T00:30:00Z", "2026-11-01T00:45:00Z", "2026-11-01T00:50:00Z"),
      edges
    );
  }

  @Test
  void bucketsStartOnTheHourOfTheTimeZone() {
    // India is UTC+5:30, so its hours start at half past the hour in UTC.
    var edges = DatabaseService.bucketEdges(
      Instant.parse("2026-11-01T00:30:00Z"), Instant.parse("2026-11-01T03:10:00Z"), 60, ZoneId.of("Asia/Kolkata")
    );
    assertEquals(
      instants("2026-11-01T00:30:00Z", "2026-11-01T01:30:00Z", "2026-11-01T02:30:00Z", "2026-11-01T03:10:00Z"),
      edges
    );
  }

  @Test
  void dayBucketsStartAtMidnightAcrossDaylightSavingTime() {
    // The clock falls back on Nov 1, 2026 in Los Angeles, so that day is 25 hours long.
    var edges = DatabaseService.bucketEdges(
      Instant.parse("2026-10-31T07:00:00Z"), Instant.parse("2026-11-03T08:00:00Z"), 1440, LOS_ANGELES
    );
    assertEquals(
      instants("2026-10-31T07:00:00Z", "2026-11-01T07:00:00Z", "2026-11-02T08:00:00Z", "2026-11-03T08:00:00Z"),
      edges
    );
  }

  @Test
  void hourBucketsIncludeTheRepeatedHourWhenTheClockFallsBack() {
    // 1:00 happens twice: at 08:00Z (PDT) and at 09:00Z (PST).
    var edges = DatabaseService.bucketEdges(
      Instant.parse("2026-11-01T07:00:00Z"), Instant.parse("2026-11-01T11:00:00Z"), 60, LOS_ANGELES
    );
    assertEquals(
      instants("2026-11-01T07:00:00Z", "2026-11-01T08:00:00Z", "2026-11-01T09:00:00Z", "2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z"),
      edges
    );
  }

  @Test
  void bucketsSkipTheMissingHourWhenTheClockSpringsForward() {
    // On Mar 8, 2026, 2:00 PST jumps to 3:00 PDT, so the 0:00-4:00 bucket is 3 hours long.
    var edges = DatabaseService.bucketEdges(
      Instant.parse("2026-03-08T08:00:00Z"), Instant.parse("2026-03-08T16:00:00Z"), 240, LOS_ANGELES
    );
    assertEquals(
      instants("2026-03-08T08:00:00Z", "2026-03-08T11:00:00Z", "2026-03-08T15:00:00Z", "2026-03-08T16:00:00Z"),
      edges
    );
  }

  @Test
  void rejectsTooManyBuckets() {
    var start = Instant.parse("2026-11-01T00:00:00Z");
    assertEquals(
      DatabaseService.MAX_UPTIME_BUCKET_COUNT + 1,
      DatabaseService.bucketEdges(start, start.plusSeconds(60L * DatabaseService.MAX_UPTIME_BUCKET_COUNT), 1, ZoneOffset.UTC).size()
    );
    assertThrows(
      IllegalArgumentException.class,
      () -> DatabaseService.bucketEdges(start, start.plusSeconds(60L * (DatabaseService.MAX_UPTIME_BUCKET_COUNT + 1)), 1, ZoneOffset.UTC)
    );
  }
}
