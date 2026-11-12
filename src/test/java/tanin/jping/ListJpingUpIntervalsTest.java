package tanin.jping;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ListJpingUpIntervalsTest {

  static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
  static final Instant END = START.plusSeconds(3600);

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
  }

  static Instant at(int minute) {
    return START.plusSeconds(minute * 60L);
  }

  static DatabaseService.UpInterval interval(int startMinute, int endMinute) {
    return new DatabaseService.UpInterval(at(startMinute), at(endMinute));
  }

  @Test
  void mergesConsecutiveIntervals() throws Exception {
    db.insertJpingUptime(at(0), at(10));
    db.insertJpingUptime(at(10), at(20));

    assertEquals(List.of(interval(0, 20)), db.listJpingUpIntervals(START, END));
  }

  @Test
  void mergesOverlappingIntervals() throws Exception {
    db.insertJpingUptime(at(0), at(15));
    db.insertJpingUptime(at(10), at(20));
    db.insertJpingUptime(at(12), at(18));

    assertEquals(List.of(interval(0, 20)), db.listJpingUpIntervals(START, END));
  }

  @Test
  void keepsSeparateIntervalsWithAGap() throws Exception {
    db.insertJpingUptime(at(0), at(10));
    db.insertJpingUptime(at(11), at(20));
    db.insertJpingUptime(at(20), at(30));

    assertEquals(List.of(interval(0, 10), interval(11, 30)), db.listJpingUpIntervals(START, END));
  }

  @Test
  void mergesIntervalsAfterClippingToTheRange() throws Exception {
    db.insertJpingUptime(at(-30), at(5));
    db.insertJpingUptime(at(-10), at(10));
    db.insertJpingUptime(at(50), at(90));
    db.insertJpingUptime(at(55), at(70));

    assertEquals(List.of(interval(0, 10), interval(50, 60)), db.listJpingUpIntervals(START, END));
  }
}
