package tanin.jping;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AddWebsiteTest extends Base {

  void addWebsite(String url) throws InterruptedException {
    go("/");
    fill(tid("new-url-input"), url);
    click(tid("add-website-button"));
  }

  List<String> shownUrls() throws InterruptedException {
    return elems(tid("website-url")).stream().map(WebElement::getText).toList();
  }

  List<String> savedUrls() throws Exception {
    return db.listWebsites().stream().map(DatabaseService.WebsiteUrl::url).toList();
  }

  @Test
  void addHttpsWebsite() throws Exception {
    addWebsite("https://example.com");

    waitUntil(() -> assertEquals(List.of("https://example.com"), shownUrls()));
    assertEquals(List.of("https://example.com"), savedUrls());
    assertEquals("", elem(tid("new-url-input")).getAttribute("value"));
  }

  @Test
  void addHttpWebsite() throws Exception {
    addWebsite("http://example.com");

    waitUntil(() -> assertEquals(List.of("http://example.com"), shownUrls()));
    assertEquals(List.of("http://example.com"), savedUrls());
  }

  @Test
  void addWebsiteWithUnsupportedScheme() throws Exception {
    addWebsite("ftp://example.com");

    assertEquals("The URL must start with http:// or https://", elem(tid("add-website-error")).getText());
    assertFalse(hasElem(tid("website-url")));
    assertEquals(List.of(), savedUrls());
  }

  @Test
  void addInvalidUrl() throws Exception {
    addWebsite("https://");

    assertEquals("This is not a valid URL.", elem(tid("add-website-error")).getText());
    assertFalse(hasElem(tid("website-url")));
    assertEquals(List.of(), savedUrls());
  }
}
