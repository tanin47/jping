package tanin.jping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AuthTest extends Base {

  @Test
  void everythingExceptTheHealthcheckRequiresCredentials() throws Exception {
    for (var path : new String[]{"/", "/admin", "/api/websites", "/assets/images/logo.png"}) {
      var response = request("GET", path, null, false);
      assertEquals(401, response.statusCode(), path);
      assertContains(response.headers().firstValue("WWW-Authenticate").orElse(""), "Basic");
    }
    assertEquals(200, request("GET", "/healthcheck", null, false).statusCode());
  }

  @Test
  void addingAndDeletingWithoutCredentialsIsForbidden() throws Exception {
    db.addWebsite("https://example.com");
    var body = "{\"url\": \"https://example.com\"}";

    assertEquals(403, request("POST", "/api/websites", "{\"url\": \"https://new.example.com\"}", false).statusCode());
    assertEquals(403, request("DELETE", "/api/websites", body, false).statusCode());
    assertEquals(1, db.listWebsites().size());

    var wrongToken = java.util.Base64.getEncoder().encodeToString((USERNAME + ":wrong").getBytes());
    var response = java.net.http.HttpClient.newHttpClient().send(
      java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + PORT + "/api/websites"))
        .header("Authorization", "Basic " + wrongToken)
        .method("DELETE", java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .build(),
      java.net.http.HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(403, response.statusCode());
    assertEquals(1, db.listWebsites().size());
  }

  @Test
  void credentialsGiveAccess() throws Exception {
    assertEquals(200, request("GET", "/", null, true).statusCode());
    assertEquals(200, request("GET", "/api/websites", null, true).statusCode());
  }

  @Test
  void wrongPasswordIsRejected() throws Exception {
    var token = java.util.Base64.getEncoder().encodeToString((USERNAME + ":wrong").getBytes());
    var response = java.net.http.HttpClient.newHttpClient().send(
      java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + PORT + "/"))
        .header("Authorization", "Basic " + token)
        .build(),
      java.net.http.HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(401, response.statusCode());
  }

  @Test
  void homeShowsTheAdminControls() throws Exception {
    db.addWebsite("https://example.com");
    go("/");

    assertTrue(elem(tid("new-url-input")).isDisplayed());
    assertTrue(elem(tid("remove-website-button")).isDisplayed());
  }

  @Test
  void credentialsAreRequired() {
    for (var credentials : new String[][]{{null, PASSWORD}, {USERNAME, null}, {"", PASSWORD}, {USERNAME, ""}}) {
      assertThrows(
        IllegalArgumentException.class,
        () -> new WebServer(PORT + 1, -1, credentials[0], credentials[1], false, databaseUrl)
      );
    }
  }
}
