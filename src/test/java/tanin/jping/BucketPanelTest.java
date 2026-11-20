package tanin.jping;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.support.ui.Select;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

public class BucketPanelTest extends Base {

  static final String URL = "https://example.com";

  void addCheck(Instant scheduledAt, Integer responseCode, String error) throws Exception {
    try (
      var conn = db.connect();
      var stmt = conn.prepareStatement(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code, error) VALUES (?, ?, ?, ?, ?, ?)"
      )
    ) {
      stmt.setString(1, URL);
      stmt.setTimestamp(2, Timestamp.from(scheduledAt));
      stmt.setTimestamp(3, Timestamp.from(scheduledAt));
      stmt.setTimestamp(4, Timestamp.from(scheduledAt.plusMillis(100)));
      stmt.setObject(5, responseCode);
      stmt.setString(6, error);
      stmt.executeUpdate();
    }
  }

  @Test
  void clickingABucketShowsItsFailedChecksAndMinutesWithoutDataGrouped() throws Exception {
    db.addWebsite(URL);
    // Two hours back, so the bucket is complete even right after the hour starts.
    var hour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.HOURS);
    addCheck(hour.plus(10, ChronoUnit.MINUTES), 503, null);
    addCheck(hour.plus(11, ChronoUnit.MINUTES), 503, null);
    addCheck(hour.plus(12, ChronoUnit.MINUTES), 503, null);
    addCheck(hour.plus(13, ChronoUnit.MINUTES), 200, null);
    addCheck(hour.plus(20, ChronoUnit.MINUTES), null, "Timed out after 5 seconds");
    addCheck(hour.plus(40, ChronoUnit.MINUTES), 500, null);
    go("/");
    new Select(elem(tid("time-zone"))).selectByValue("utc");

    var bucket = tid("bucket") + "[data-test-value='" + hour.toString().replace("Z", ".000Z") + "']";
    click(bucket);

    waitUntil(() -> assertEquals(7, elems(tid("issue-group")).size()));
    var groups = elems(tid("issue-group")).stream().map(e -> e.getText()).toList();
    assertContains(groups.get(0), "No data ×10");
    assertContains(groups.get(1), "503 ×3");
    assertContains(groups.get(2), "No data ×6");
    assertContains(groups.get(3), "No response");
    assertContains(groups.get(3), "Timed out after 5 seconds");
    assertContains(groups.get(4), "No data ×19");
    assertContains(groups.get(5), "500");
    assertContains(groups.get(6), "No data ×19");
    assertContains(elem(tid("bucket-panel-summary")).getText(), "5 failed checks · 54 minutes with no data");

    click(bucket);
    waitUntil(() -> assertFalse(hasElem(tid("bucket-panel"))));

    click(bucket);
    elem(tid("bucket-panel"));
    click(tid("close-bucket-panel"));
    waitUntil(() -> assertFalse(hasElem(tid("bucket-panel"))));
  }

  @Test
  void everyBucketIsClickable() throws Exception {
    db.addWebsite(URL);
    go("/");

    var buckets = elems(tid("bucket"));
    assertEquals(24, buckets.size());
    var latest = buckets.getLast();
    assertTrue(latest.getSize().getWidth() >= 6, "The latest bucket is " + latest.getSize().getWidth() + "px wide");
    latest.click();

    assertContains(elem(tid("bucket-panel-summary")).getText(), "0 failed checks");
  }
}
