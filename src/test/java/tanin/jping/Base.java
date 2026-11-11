package tanin.jping;

import org.junit.jupiter.api.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Base {
  static int PORT = 9091;
  static final String USERNAME = "admin";
  static final String PASSWORD = "secret";
  static boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");

  WebDriver webDriver;
  WebServer server;
  DatabaseService db;
  Connection conn;
  String databaseUrl;
  boolean isPublicAccess = false;

  @BeforeAll
  void setUpAll() throws Exception {
    databaseUrl = System.getenv("JPING_DATABASE_URL");
    assertNotNull(databaseUrl, "JPING_DATABASE_URL is required. It's set in build.gradle.kts for the test task.");

    initializeWebDriver();
  }

  public void initializeWebDriver() {
    if (webDriver != null) {
      webDriver.close();
    }
    var options = new ChromeOptions();

    if (System.getenv("HEADLESS") != null) {
      options.addArguments("--headless");
    }
    options.addArguments("--guest");
    options.addArguments("--disable-extensions");
    options.addArguments("--disable-web-security");
    options.addArguments("--window-size=1280,800");
    options.addArguments("--disable-dev-shm-usage");
    options.addArguments("--disable-smooth-scrolling");

    var logPrefs = new LoggingPreferences();
    logPrefs.enable(LogType.BROWSER, Level.ALL);
    options.setCapability("goog:loggingPrefs", logPrefs);

    webDriver = new ChromeDriver(options);
    // Answers the Basic Authentication prompt whenever the server asks for the credentials.
    ((HasAuthentication) webDriver).register(UsernameAndPassword.of(USERNAME, PASSWORD));
  }

  @BeforeEach
  void setUp() throws Exception {
    // Creating the server runs the migration, so the tables exist before they're emptied below.
    server = new WebServer(PORT, -1, USERNAME, PASSWORD, isPublicAccess, databaseUrl);
    db = server.db;

    // Start each test with an empty database.
    try (var c = db.connect(); var stmt = c.createStatement()) {
      stmt.execute("TRUNCATE website_url, response, jping_uptime");
    }
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @AfterAll
  void tearDownAll() throws SQLException {
    webDriver.close();
  }

  HttpResponse<String> request(String method, String path, String body, boolean withCredentials) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + path))
      .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    if (withCredentials) {
      var token = Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
      builder.header("Authorization", "Basic " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  void go(String path) {
    webDriver.get("http://localhost:" + PORT + path);
  }

  String tid(String... args) {
    var beginning = true;
    var sb = new StringBuilder();

    for (int i = 0; i < args.length; i++) {
      if (beginning) {
        sb.append("[data-test-id=")
          .append(args[i])
          .append("]");
        beginning = false;
      } else if (args[i] == null) {
        sb.append(" ");
        beginning = true;
      } else {
        sb.append("[data-test-value=")
          .append(args[i])
          .append("]");
      }
    }

    return sb.toString();
  }

  WebElement elem(String cssSelector) throws InterruptedException {
    return elem(cssSelector, true);
  }

  WebElement elem(String cssSelector, boolean checkDisplay) throws InterruptedException {
    if (checkDisplay) {
      waitUntil(() -> assertTrue(elems(cssSelector).stream().anyMatch(WebElement::isDisplayed)));
      return elems(cssSelector).stream().filter(WebElement::isDisplayed).findFirst().orElse(null);
    } else {
      return elems(cssSelector).getFirst();
    }
  }

  boolean hasElem(String cssSelector) throws InterruptedException {
    return webDriver.findElements(new By.ByCssSelector(cssSelector)).stream().anyMatch(WebElement::isDisplayed);
  }

  List<WebElement> elems(String cssSelector) throws InterruptedException {
    waitUntil(() -> assertFalse(webDriver.findElements(new By.ByCssSelector(cssSelector)).isEmpty()));
    return webDriver.findElements(new By.ByCssSelector(cssSelector));
  }

  void retryInteraction(VoidFn fn) throws InterruptedException {
    for (int retry = 0; retry < 2; retry++) {
      try {
        fn.invoke();
        return;
      } catch (ElementNotInteractableException ex) {
        Thread.sleep(1000);
      }
    }

    // Last try
    fn.invoke();
  }

  void click(String cssSelector) throws InterruptedException {
    retryInteraction(() -> elem(cssSelector).click());
  }

  void hover(String cssSelector) throws InterruptedException {
    var actions = new Actions(webDriver);
    actions.moveToElement(elem(cssSelector), 2, 2).perform();
  }

  void fill(String cssSelector, String text) throws InterruptedException {
    retryInteraction(() -> {
      var el = elem(cssSelector);

      el.sendKeys(IS_MAC ? Keys.COMMAND : Keys.CONTROL, "a");
      el.sendKeys(Keys.BACK_SPACE);

      Thread.sleep(10);
      el.sendKeys(text);
    });
  }

  void sendClearKeys() throws InterruptedException {
    var actions = new Actions(webDriver);
    var modifierKey = IS_MAC ? Keys.COMMAND : Keys.CONTROL;
    actions
      .keyDown(modifierKey)
      .sendKeys("a")
      .keyUp(modifierKey)
      .sendKeys(Keys.BACK_SPACE)
      .perform();
  }

  void sendKeys(String text) {
    var actions = new Actions(webDriver);
    actions.sendKeys(text).perform();
  }


  int WAIT_UNTIL_TIMEOUT_MILLIS = 5000;

  @FunctionalInterface
  interface InterruptibleSupplier {
    boolean get() throws InterruptedException;
  }

  @FunctionalInterface
  interface VoidFn {
    void invoke() throws InterruptedException;
  }

  void waitUntil(VoidFn fn) throws InterruptedException {
    InterruptibleSupplier newFn = () -> {
      try {
        fn.invoke();
        return true;
      } catch (AssertionError | StaleElementReferenceException | java.util.NoSuchElementException |
               NoSuchElementException e) {
        return false;
      }
    };

    var startTime = System.currentTimeMillis();
    while ((System.currentTimeMillis() - startTime) < WAIT_UNTIL_TIMEOUT_MILLIS) {
      if (newFn.get()) return;
      Thread.sleep(500);
    }

    fn.invoke();
  }

  void assertContains(String actual, String expectedPart) {
    assertTrue(actual.contains(expectedPart), () -> actual + " doesn't contain " + expectedPart);
  }
}
