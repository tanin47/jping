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
    try (
      var conn = db.connect();
      var stmt = conn.prepareStatement(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code) VALUES (?, ?, ?, ?, ?)"
      )
    ) {
      stmt.setString(1, URL);
      stmt.setTimestamp(2, Timestamp.from(scheduledAt));
      stmt.setTimestamp(3, Timestamp.from(scheduledAt));
      stmt.setTimestamp(4, finished ? Timestamp.from(scheduledAt.plusSeconds(1)) : null);
      stmt.setObject(5, responseCode);
      stmt.executeUpdate();
    }
  }

  List<DatabaseService.Bucket> buckets(Instant start, Instant end, int bucketMinutes) throws Exception {
    var websites = db.listWebsiteUptimes(DatabaseService.bucketEdges(start, end, bucketMinutes, ZoneOffset.UTC));
    assertEquals(1, websites.size());
    return websites.getFirst().buckets();
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

  void assertBucket(DatabaseService.Bucket bucket, int total, int successes, int failures, int noData) {
    assertEquals(total, bucket.total());
    assertEquals(successes, bucket.successes());
    assertEquals(failures, bucket.failures());
    assertEquals(noData, bucket.noData());
  }
}
