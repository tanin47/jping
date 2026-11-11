package tanin.jping;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PingerTest {

  static final int PORT = 9092;

  DatabaseService db;
  WebServer server;

  @BeforeAll
  void setUpAll() throws Exception {
    db = new DatabaseService(System.getenv("JPING_DATABASE_URL"));
  }

  @BeforeEach
  void setUp() throws Exception {
    try (var conn = db.connect(); var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE website_url, response, jping_uptime");
    }
    server = new WebServer(PORT, -1, null, null, false, db);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  @Test
  void insertsUptimeRowOnStartAndUpdatesItOnStop() throws Exception {
    var startedAt = server.pinger.uptimeStartedAt;
    var intervals = db.listJpingUpIntervals(startedAt.minusSeconds(60), startedAt.plusSeconds(60));
    assertEquals(1, intervals.size());
    assertEquals(startedAt, intervals.getFirst().startedAt());
    assertEquals(startedAt, intervals.getFirst().endedAt());

    Thread.sleep(50);
    server.stop();

    intervals = db.listJpingUpIntervals(startedAt.minusSeconds(60), startedAt.plusSeconds(60));
    assertTrue(intervals.getFirst().endedAt().isAfter(startedAt));
  }

  @Test
  void recordsTheStatusCode() throws Exception {
    var url = "http://localhost:" + PORT + "/healthcheck";
    db.addWebsite(url);
    var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

    server.pinger.ping(url, minute);

    assertResponse(url, minute, 200, null);
  }

  @Test
  void recordsTheErrorWhenThereIsNoResponse() throws Exception {
    var url = "http://localhost:1/";
    db.addWebsite(url);
    var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

    server.pinger.ping(url, minute);

    assertResponse(url, minute, null, "ConnectException");
  }

  void assertResponse(String url, Instant scheduledAt, Integer responseCode, String errorPrefix) throws Exception {
    try (
      var conn = db.connect();
      var stmt = conn.prepareStatement("SELECT scheduled_at, started_at, finished_at, response_code, error FROM response WHERE url = ?")
    ) {
      stmt.setString(1, url);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(scheduledAt, rs.getTimestamp("scheduled_at").toInstant());
        assertFalse(rs.getTimestamp("started_at").toInstant().isBefore(scheduledAt));
        assertNotNull(rs.getTimestamp("finished_at"));
        assertEquals(responseCode, rs.getObject("response_code"));
        if (errorPrefix == null) {
          assertNull(rs.getString("error"));
        } else {
          assertTrue(rs.getString("error").startsWith(errorPrefix), rs.getString("error"));
        }
        assertFalse(rs.next());
      }
    }
  }
}
