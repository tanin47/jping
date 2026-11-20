package tanin.jping;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ListWebsiteUptimesTest {

  static final String URL = "https://example.com";
  static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
  static final Instant END = START.plus(Duration.ofMinutes(90));

  DatabaseService db;

  @BeforeAll
  void setUpAll() throws Exception {
    db = new DatabaseService(System.getenv("JPING_DATABASE_URL"));
  }

  @BeforeEach
  void setUp() throws Exception {
    try (var conn = db.connect(); var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE website_url, response, jping_uptime");
    }
    db.addWebsite(URL);
  }

  void addCheck(Instant scheduledAt, Integer responseCode, boolean finished) throws Exception {
    insertCheck(scheduledAt, responseCode, finished ? 1000L : null, null);
  }

  void addCheck(Instant scheduledAt, Integer responseCode, long durationMillis, String error) throws Exception {
    insertCheck(scheduledAt, responseCode, durationMillis, error);
  }

  void insertCheck(Instant scheduledAt, Integer responseCode, Long durationMillis, String error) throws Exception {
    try (
      var conn = db.connect();
      var stmt = conn.prepareStatement(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code, error) VALUES (?, ?, ?, ?, ?, ?)"
      )
    ) {
      stmt.setString(1, URL);
      stmt.setTimestamp(2, Timestamp.from(scheduledAt));
      stmt.setTimestamp(3, Timestamp.from(scheduledAt));
      stmt.setTimestamp(4, durationMillis == null ? null : Timestamp.from(scheduledAt.plusMillis(durationMillis)));
      stmt.setObject(5, responseCode);
      stmt.setString(6, error);
      stmt.executeUpdate();
    }
  }

  DatabaseService.WebsiteUrlUptime website(Instant start, Instant end, int bucketMinutes) throws Exception {
    var websites = db.listWebsiteUptimes(DatabaseService.bucketEdges(start, end, bucketMinutes, ZoneOffset.UTC));
    assertEquals(1, websites.size());
    return websites.getFirst();
  }

  List<DatabaseService.Bucket> buckets(Instant start, Instant end, int bucketMinutes) throws Exception {
    return website(start, end, bucketMinutes).buckets();
  }

  @Test
  void bucketsCoverTheRangeWithoutGaps() throws Exception {
    var buckets = buckets(START, END, 1);

    assertEquals(90, buckets.size());
    assertEquals(START, buckets.getFirst().start());
    assertEquals(END, buckets.getLast().end());
    for (int i = 1; i < buckets.size(); i++) {
      assertEquals(buckets.get(i - 1).end(), buckets.get(i).start());
    }
  }

  @Test
  void splitsTheRangeIntoBucketsOfTheRequestedSize() throws Exception {
    var end = START.plus(Duration.ofHours(24));
    addCheck(START.plus(Duration.ofMinutes(61)), 500, true);

    var buckets = buckets(START, end, 60);
    assertEquals(24, buckets.size());
    for (int i = 0; i < buckets.size(); i++) {
      assertEquals(START.plus(Duration.ofHours(i)), buckets.get(i).start());
      assertEquals(60, buckets.get(i).total());
    }
    assertBucket(buckets.get(1), 60, 0, 1, 59);
  }

  @Test
  void onlyTheLatestBucketIsPartial() throws Exception {
    var end = START.plus(Duration.ofDays(7)).plusSeconds(10);
    addCheck(START.plus(Duration.ofDays(7)), 200, true);

    var buckets = buckets(START.plusSeconds(30), end, 240);
    assertEquals(43, buckets.size());
    assertEquals(START, buckets.getFirst().start());
    for (int i = 0; i < 42; i++) {
      assertEquals(240, buckets.get(i).total());
    }
    var last = buckets.getLast();
    assertEquals(START.plus(Duration.ofDays(7)), last.start());
    assertEquals(START.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(1)), last.end());
    assertBucket(last, 1, 1, 0, 0);
  }

  @Test
  void bucketStartIsInclusiveAndEndIsExclusive() throws Exception {
    // Each bucket is 1 minute: bucket 0 is [00:00, 00:01), bucket 1 is [00:01, 00:02).
    addCheck(START, 200, true);
    addCheck(START.plusSeconds(59), 200, true);
    addCheck(START.plusSeconds(60), 200, true);

    var buckets = buckets(START, END, 1);
    assertEquals(2, buckets.get(0).successes());
    assertEquals(1, buckets.get(1).successes());
  }

  @Test
  void checksOutsideTheRangeAreIgnored() throws Exception {
    addCheck(START.minusMillis(1), 200, true);
    addCheck(END, 200, true);

    var buckets = buckets(START, END, 15);
    assertEquals(0, buckets.stream().mapToInt(b -> b.successes() + b.failures()).sum());
  }

  @Test
  void countsSuccessesFailuresAndNoData() throws Exception {
    var end = START.plus(Duration.ofMinutes(180));
    addCheck(START, 200, true);
    addCheck(START.plusSeconds(60), 500, true);
    addCheck(START.plusSeconds(120), 404, true);
    addCheck(START.plusSeconds(240), null, true);

    var buckets = buckets(START, end, 2);
    assertBucket(buckets.get(0), 2, 1, 1, 0);
    assertBucket(buckets.get(1), 2, 1, 0, 1);
    assertBucket(buckets.get(2), 2, 0, 1, 1);
    assertBucket(buckets.get(3), 2, 0, 0, 2);
  }

  @Test
  void unfinishedChecksCountAsNoData() throws Exception {
    addCheck(START, null, false);

    assertBucket(buckets(START, END, 1).getFirst(), 1, 0, 0, 1);
  }

  @Test
  void finishResponseSetsTheTimesOnlyOnce() throws Exception {
    var startedAt = START.plusSeconds(1);
    var finishedAt = START.plusSeconds(2);
    db.insertResponse(URL, START, START);

    assertTrue(db.finishResponse(URL, START, startedAt, finishedAt, 200, null));
    assertFalse(db.finishResponse(URL, START, START.plusSeconds(5), START.plusSeconds(6), 500, "late"));

    try (
      var conn = db.connect();
      var stmt = conn.prepareStatement("SELECT started_at, finished_at, response_code, error FROM response");
      var rs = stmt.executeQuery()
    ) {
      assertTrue(rs.next());
      assertEquals(startedAt, rs.getTimestamp("started_at").toInstant());
      assertEquals(finishedAt, rs.getTimestamp("finished_at").toInstant());
      assertEquals(200, rs.getInt("response_code"));
      assertNull(rs.getString("error"));
      assertFalse(rs.next());
    }
  }

  @Test
  void latencyPercentilesLeaveOutChecksWithoutAResponse() throws Exception {
    for (int i = 1; i <= 9; i++) {
      addCheck(START.plusSeconds(i), 200, i * 100L, null);
    }
    addCheck(START.plusSeconds(10), 500, 1000L, null);
    addCheck(START.plusSeconds(11), null, 5000L, "Timed out after 5 seconds");

    var bucket = buckets(START, END, 1).getFirst();
    assertEquals(new DatabaseService.Latency(500, 900, 1000, 1000), bucket.latency());
  }

  @Test
  void latencyIsNullWithoutAnyResponse() throws Exception {
    addCheck(START, null, 5000L, "Timed out after 5 seconds");

    var website = website(START, END, 1);
    assertNull(website.buckets().get(0).latency());
    assertNull(website.buckets().get(1).latency());
    assertNull(website.latency());
  }

  @Test
  void latencyOfTheRangeIsComputedFromAllItsChecks() throws Exception {
    // Bucket 0 has 100ms and 200ms, bucket 1 has 300ms, 400ms, and 500ms.
    addCheck(START, 200, 100L, null);
    addCheck(START.plusSeconds(10), 200, 200L, null);
    addCheck(START.plusSeconds(60), 200, 300L, null);
    addCheck(START.plusSeconds(70), 200, 400L, null);
    addCheck(START.plusSeconds(80), 200, 500L, null);

    var website = website(START, END, 1);
    assertEquals(new DatabaseService.Latency(100, 200, 200, 200), website.buckets().get(0).latency());
    assertEquals(new DatabaseService.Latency(400, 500, 500, 500), website.buckets().get(1).latency());
    assertEquals(new DatabaseService.Latency(300, 500, 500, 500), website.latency());
  }

  @Test
  void groupsFailuresByResponseCode() throws Exception {
    addCheck(START, 200, 100L, null);
    addCheck(START.plusSeconds(1), 404, 100L, null);
    addCheck(START.plusSeconds(2), 503, 100L, null);
    addCheck(START.plusSeconds(3), null, 5000L, "Timed out after 5 seconds");
    addCheck(START.plusSeconds(4), 500, 100L, null);
    addCheck(START.plusSeconds(5), null, 10L, "ConnectException");
    addCheck(START.plusSeconds(6), 500, 100L, null);

    var buckets = buckets(START, END, 1);
    assertEquals(
      List.of(
        new DatabaseService.FailureCount(500, 2),
        new DatabaseService.FailureCount(503, 1),
        new DatabaseService.FailureCount(null, 2)
      ),
      buckets.get(0).failureCodes()
    );
    assertEquals(List.of(), buckets.get(1).failureCodes());
  }

  @Test
  void lastFailureIsTheLatestFailedCheckInTheRange() throws Exception {
    addCheck(START, 500, 100L, null);
    addCheck(START.plusSeconds(60), null, 10L, "ConnectException");
    addCheck(START.plusSeconds(120), 200, 100L, null);
    addCheck(END, 503, 100L, null);

    assertEquals(
      new DatabaseService.Failure(START.plusSeconds(60), null, "ConnectException"),
      website(START, END, 1).lastFailure()
    );
  }

  @Test
  void lastFailureIsNullWithoutAnyFailure() throws Exception {
    addCheck(START, 200, 100L, null);

    assertNull(website(START, END, 1).lastFailure());
  }

  @Test
  void listFailuresReturnsTheFailedChecksInTheRangeInOrder() throws Exception {
    db.addWebsite("https://other.example.com");
    addCheck(START.minusSeconds(60), 500, 100L, null);
    addCheck(START.plusSeconds(120), null, 10L, "ConnectException");
    addCheck(START, 503, 100L, null);
    addCheck(START.plusSeconds(60), 200, 100L, null);
    addCheck(START.plusSeconds(180), 404, 100L, null);
    addCheck(START.plusSeconds(240), null, false);
    addCheck(END, 500, 100L, null);
    try (var conn = db.connect(); var stmt = conn.createStatement()) {
      stmt.execute(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code) " +
          "VALUES ('https://other.example.com', '2026-11-01T00:05:00Z', '2026-11-01T00:05:00Z', '2026-11-01T00:05:01Z', 500)"
      );
    }

    assertEquals(
      List.of(
        new DatabaseService.Failure(START, 503, null),
        new DatabaseService.Failure(START.plusSeconds(120), null, "ConnectException")
      ),
      db.listFailures(URL, START, END)
    );
  }

  @Test
  void listNoDataMinutesReturnsTheMinutesWithoutAFinishedCheck() throws Exception {
    db.addWebsite("https://other.example.com");
    addCheck(START, 200, 100L, null);
    addCheck(START.plusSeconds(60), null, 5000L, "Timed out after 5 seconds");
    addCheck(START.plusSeconds(120), null, false);
    addCheck(START.plusSeconds(240), 500, 100L, null);
    try (var conn = db.connect(); var stmt = conn.createStatement()) {
      stmt.execute(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code) " +
          "VALUES ('https://other.example.com', '2026-11-01T00:03:00Z', '2026-11-01T00:03:00Z', '2026-11-01T00:03:01Z', 200)"
      );
    }

    assertEquals(
      List.of(START.plusSeconds(120), START.plusSeconds(180), START.plusSeconds(300)),
      db.listNoDataMinutes(URL, START.plusSeconds(30), START.plusSeconds(360))
    );
  }

  void assertBucket(DatabaseService.Bucket bucket, int total, int successes, int failures, int noData) {
    assertEquals(total, bucket.total());
    assertEquals(successes, bucket.successes());
    assertEquals(failures, bucket.failures());
    assertEquals(noData, bucket.noData());
  }
}
