package tanin.jping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DeleteWebsiteTest extends Base {

  static final String FIRST = "https://first.example.com";
  static final String SECOND = "https://second.example.com";

  @BeforeEach
  void addWebsites() throws Exception {
    db.addWebsite(FIRST);
    db.addWebsite(SECOND);
    addResponse(FIRST);
    addResponse(SECOND);
  }

  void addResponse(String url) throws Exception {
    try (
      var c = db.connect();
      var stmt = c.prepareStatement(
        "INSERT INTO response (url, scheduled_at, started_at, finished_at, response_code) VALUES (?, ?, ?, ?, 200)"
      )
    ) {
      var now = java.sql.Timestamp.from(Instant.now());
      stmt.setString(1, url);
      stmt.setTimestamp(2, now);
      stmt.setTimestamp(3, now);
      stmt.setTimestamp(4, now);
      stmt.executeUpdate();
    }
  }

  int countResponses(String url) throws Exception {
    try (var c = db.connect(); var stmt = c.prepareStatement("SELECT COUNT(*) FROM response WHERE url = ?")) {
      stmt.setString(1, url);
      try (var rs = stmt.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  // The URL has characters like ':' and '/', so the attribute value must be quoted.
  String removeButton(String url) {
    return tid("remove-website-button") + "[data-test-value=\"" + url + "\"]";
  }

  List<String> shownUrls() throws InterruptedException {
    return elems(tid("website-url")).stream().map(WebElement::getText).toList();
  }

  List<String> savedUrls() throws Exception {
    return db.listWebsites().stream().map(DatabaseService.WebsiteUrl::url).toList();
  }

  void openRemoveDialog(String url) throws InterruptedException {
    go("/");
    waitUntil(() -> assertEquals(List.of(FIRST, SECOND), shownUrls()));

    click(removeButton(url));
    assertEquals(url, elem(tid("remove-dialog-url")).getText());
  }

  @Test
  void deleteWebsite() throws Exception {
    openRemoveDialog(FIRST);
    click(tid("remove-dialog-confirm"));

    waitUntil(() -> assertFalse(hasElem(tid("remove-dialog-confirm"))));
    waitUntil(() -> assertEquals(List.of(SECOND), shownUrls()));
    assertEquals(List.of(SECOND), savedUrls());

    // The deleted website's responses are removed too; the other website's are kept.
    assertEquals(0, countResponses(FIRST));
    assertEquals(1, countResponses(SECOND));
  }

  @Test
  void cancelDeletingWebsite() throws Exception {
    openRemoveDialog(FIRST);
    click(tid("remove-dialog-cancel"));

    waitUntil(() -> assertFalse(hasElem(tid("remove-dialog-confirm"))));
    assertEquals(List.of(FIRST, SECOND), shownUrls());
    assertEquals(List.of(FIRST, SECOND), savedUrls());
  }

  @Test
  void deletedWebsiteStaysGoneAfterReload() throws Exception {
    openRemoveDialog(SECOND);
    click(tid("remove-dialog-confirm"));
    waitUntil(() -> assertEquals(List.of(FIRST), shownUrls()));

    go("/");
    waitUntil(() -> assertEquals(List.of(FIRST), shownUrls()));
  }
}
