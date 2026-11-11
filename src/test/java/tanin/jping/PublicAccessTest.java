package tanin.jping;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PublicAccessTest extends Base {

  static final String URL = "https://example.com";
  static final String URL_BODY = "{\"url\": \"" + URL + "\"}";

  PublicAccessTest() {
    isPublicAccess = true;
  }

  List<String> savedUrls() {
    try {
      return db.listWebsites().stream().map(DatabaseService.WebsiteUrl::url).toList();
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void homeHidesTheAdminControls() throws Exception {
    db.addWebsite(URL);
    go("/");

    assertEquals(URL, elem(tid("website-url")).getText());
    assertFalse(hasElem(tid("new-url-input")));
    assertFalse(hasElem(tid("remove-website-button")));
  }

  @Test
  void adminShowsTheAdminControls() throws Exception {
    db.addWebsite(URL);
    go("/admin");

    assertEquals(URL, elem(tid("website-url")).getText());
    assertTrue(elem(tid("new-url-input")).isDisplayed());
    assertTrue(elem(tid("remove-website-button")).isDisplayed());
  }

  @Test
  void adminCanAddWebsite() throws Exception {
    go("/admin");
    fill(tid("new-url-input"), URL);
    click(tid("add-website-button"));

    waitUntil(() -> assertEquals(List.of(URL), savedUrls()));
  }

  @Test
  void viewingDoesNotRequireCredentials() throws Exception {
    assertEquals(200, request("GET", "/", null, false).statusCode());
    assertEquals(200, request("GET", "/api/websites", null, false).statusCode());
    assertEquals(
      200,
      request("GET", "/api/websites/uptime?start=2026-11-10T00:00:00Z&end=2026-11-11T00:00:00Z", null, false).statusCode()
    );
  }

  @Test
  void adminRequiresCredentials() throws Exception {
    assertEquals(401, request("GET", "/admin", null, false).statusCode());
    assertEquals(200, request("GET", "/admin", null, true).statusCode());
  }

  @Test
  void addingRequiresCredentials() throws Exception {
    assertEquals(401, request("POST", "/api/websites", URL_BODY, false).statusCode());
    assertEquals(List.of(), savedUrls());

    assertEquals(201, request("POST", "/api/websites", URL_BODY, true).statusCode());
    assertEquals(List.of(URL), savedUrls());
  }

  @Test
  void deletingRequiresCredentials() throws Exception {
    db.addWebsite(URL);

    assertEquals(401, request("DELETE", "/api/websites", URL_BODY, false).statusCode());
    assertEquals(List.of(URL), savedUrls());

    assertEquals(200, request("DELETE", "/api/websites", URL_BODY, true).statusCode());
    assertEquals(List.of(), savedUrls());
  }
}
