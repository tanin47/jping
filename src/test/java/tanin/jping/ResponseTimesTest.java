package tanin.jping;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class ResponseTimesTest extends Base {

  static final String URL = "https://example.com";

  void addCheck(Instant scheduledAt, Integer responseCode, long durationMillis, String error) throws Exception {
    try (
      var conn = db.connect();
      var stmt = conn.prepareStatement(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code, error) VALUES (?, ?, ?, ?, ?, ?)"
      )
    ) {
      stmt.setString(1, URL);
      stmt.setTimestamp(2, Timestamp.from(scheduledAt));
      stmt.setTimestamp(3, Timestamp.from(scheduledAt));
      stmt.setTimestamp(4, Timestamp.from(scheduledAt.plusMillis(durationMillis)));
      stmt.setObject(5, responseCode);
      stmt.setString(6, error);
      stmt.executeUpdate();
    }
  }

  @Test
  void showsTheResponseTimesAndTheLastFailure() throws Exception {
    db.addWebsite(URL);
    var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    addCheck(minute.minus(30, ChronoUnit.MINUTES), 200, 150, null);
    addCheck(minute.minus(20, ChronoUnit.MINUTES), 503, 2500, null);
    addCheck(minute.minus(10, ChronoUnit.MINUTES), null, 5000, "Timed out after 5 seconds");
    go("/");

    var latency = elem(tid("website-latency")).getText();
    assertContains(latency, "p50 150ms");
    assertContains(latency, "p99 2.5s");
    assertContains(latency, "max 2.5s");
    assertContains(elem(tid("website-last-failure")).getText(), "Last failure: Timed out after 5 seconds");
  }
}
