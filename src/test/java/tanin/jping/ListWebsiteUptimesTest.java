package tanin.jping;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

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

  DatabaseService.Bucket bucket(int index) throws Exception {
    var websites = db.listWebsiteUptimes(START, END);
    assertEquals(1, websites.size());
    return websites.getFirst().buckets().get(index);
  }

  @Test
  void bucketsCoverTheRangeWithoutGaps() throws Exception {
    var buckets = db.listWebsiteUptimes(START, END).getFirst().buckets();

    assertEquals(DatabaseService.UPTIME_BUCKET_COUNT, buckets.size());
    assertEquals(START, buckets.getFirst().start());
    assertEquals(END, buckets.getLast().end());
    for (int i = 1; i < buckets.size(); i++) {
      assertEquals(buckets.get(i - 1).end(), buckets.get(i).start());
    }
  }

  @Test
  void bucketStartIsInclusiveAndEndIsExclusive() throws Exception {
    // Each bucket is 1 minute: bucket 0 is [00:00, 00:01), bucket 1 is [00:01, 00:02).
    addCheck(START, 200, true);
    addCheck(START.plusSeconds(59), 200, true);
    addCheck(START.plusSeconds(60), 200, true);

    assertEquals(2, bucket(0).checks());
    assertEquals(1, bucket(1).checks());
  }

  @Test
  void checksOutsideTheRangeAreIgnored() throws Exception {
    addCheck(START.minusMillis(1), 200, true);
    addCheck(END, 200, true);

    var buckets = db.listWebsiteUptimes(START, END).getFirst().buckets();
    assertEquals(0, buckets.stream().mapToInt(DatabaseService.Bucket::checks).sum());
  }

  @Test
  void countsSuccessfulChecks() throws Exception {
    addCheck(START, 200, true);
    addCheck(START.plusSeconds(1), 302, true);
    addCheck(START.plusSeconds(2), 404, true);
    addCheck(START.plusSeconds(3), 500, true);
    addCheck(START.plusSeconds(4), null, true);

    var bucket = bucket(0);
    assertEquals(5, bucket.checks());
    assertEquals(3, bucket.successfulChecks());
  }

  @Test
  void unfinishedChecksAreSkipped() throws Exception {
    addCheck(START, null, false);

    assertEquals(0, bucket(0).checks());
    assertNull(bucket(0).uptime());
  }
}
