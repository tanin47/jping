package tanin.jping;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DemoModeTest extends Base {

  static final String URL = "https://example.com";

  DemoModeTest() {
    isDemo = true;
  }

  HttpResponse<String> request(String method, String url) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/websites"))
      .header("Content-Type", "application/json")
      .method(method, HttpRequest.BodyPublishers.ofString("{\"url\": \"" + url + "\"}"))
      .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  List<String> savedUrls() throws Exception {
    return db.listWebsites().stream().map(DatabaseService.WebsiteUrl::url).toList();
  }

  @Test
  void addButtonIsDisabled() throws Exception {
    go("/");

    assertFalse(elem(tid("add-website-button")).isEnabled());
  }

  @Test
  void addEndpointIsDisabled() throws Exception {
    var response = request("POST", URL);

    assertEquals(403, response.statusCode());
    assertContains(response.body(), "You cannot add a URL because this is a demo mode.");
    assertEquals(List.of(), savedUrls());
  }

  @Test
  void deleteDialogSaysItIsDisabled() throws Exception {
    db.addWebsite(URL);
    go("/");
    click(tid("remove-website-button"));

    assertEquals("You cannot delete a URL because this is a demo mode.", elem(tid("remove-dialog-demo")).getText());
    assertFalse(elem(tid("remove-dialog-confirm")).isEnabled());
  }

  @Test
  void deleteEndpointIsDisabled() throws Exception {
    db.addWebsite(URL);

    var response = request("DELETE", URL);

    assertEquals(403, response.statusCode());
    assertContains(response.body(), "You cannot delete a URL because this is a demo mode.");
    assertEquals(List.of(URL), savedUrls());
  }
}
