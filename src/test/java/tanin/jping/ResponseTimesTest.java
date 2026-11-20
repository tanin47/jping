package tanin.jping;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

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
  void issuesApiReturnsTheFailedChecksAndTheMinutesWithoutData() throws Exception {
    db.addWebsite(URL);
    var start = Instant.parse("2026-11-01T00:00:00Z");
    addCheck(start, 503, 100, null);
    addCheck(start.plusSeconds(60), 200, 100, null);
    addCheck(start.plusSeconds(120), null, 5000, "Timed out after 5 seconds");

    var response = request(
      "GET",
      "/api/websites/issues?url=https%3A%2F%2Fexample.com&start=2026-11-01T00:00:00Z&end=2026-11-01T00:05:00Z",
      null,
      true
    );
    assertEquals(200, response.statusCode());
    var body = com.eclipsesource.json.Json.parse(response.body()).asObject();
    var failures = body.get("failures").asArray();
    assertEquals(2, failures.size());
    assertEquals("2026-11-01T00:00:00Z", failures.get(0).asObject().getString("scheduledAt", null));
    assertEquals(503, failures.get(0).asObject().getInt("responseCode", 0));
    assertTrue(failures.get(1).asObject().get("responseCode").isNull());
    assertEquals("Timed out after 5 seconds", failures.get(1).asObject().getString("error", null));
    var noData = body.get("noData").asArray();
    assertEquals(2, noData.size());
    assertEquals("2026-11-01T00:03:00Z", noData.get(0).asString());
    assertEquals("2026-11-01T00:04:00Z", noData.get(1).asString());

    assertEquals(400, request("GET", "/api/websites/issues?start=2026-11-01T00:00:00Z&end=2026-11-01T01:00:00Z", null, true).statusCode());
    assertEquals(400, request("GET", "/api/websites/issues?url=https%3A%2F%2Fexample.com", null, true).statusCode());
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
    assertEquals("33.33333333%", elem(tid("website-uptime")).getText());
  }
}
