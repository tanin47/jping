package tanin.jping;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.renomad.minum.web.FullSystem;
import com.renomad.minum.web.IRequest;
import com.renomad.minum.web.IResponse;
import com.renomad.minum.web.Response;
import com.renomad.minum.web.StatusLine;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static com.renomad.minum.web.RequestLine.Method.DELETE;
import static com.renomad.minum.web.RequestLine.Method.GET;
import static com.renomad.minum.web.RequestLine.Method.POST;

public class WebServer {

  private static final Logger logger = Logger.getLogger(WebServer.class.getName());

  static {
    try (var configFile = WebServer.class.getResourceAsStream("/jping_default_logging.properties")) {
      LogManager.getLogManager().readConfiguration(configFile);
      logger.info("The log config (default_logging.properties) has been loaded.");
    } catch (IOException e) {
      logger.warning("Could not load the log config file (default_logging.properties): " + e.getMessage());
    }
  }

  int port;
  int sslPort;
  public FullSystem minum;

  final String username;
  final String password;
  final boolean authEnabled;
  final DatabaseService db;

  WebServer(int port, int sslPort, DatabaseService db) {
    this(port, sslPort, System.getenv("JPING_USERNAME"), System.getenv("JPING_PASSWORD"), db);
  }

  WebServer(int port, int sslPort, String username, String password, DatabaseService db) {
    this.port = port;
    this.sslPort = sslPort;
    this.db = db;
    this.username = username;
    this.password = password;
    this.authEnabled = username != null && !username.isEmpty() && password != null && !password.isEmpty();

    if (authEnabled) {
      logger.info("HTTP Basic Authentication is enabled.");
    } else {
      logger.warning("HTTP Basic Authentication is disabled. Set JPING_USERNAME and JPING_PASSWORD to enable it.");
    }
  }

  boolean isAuthorized(List<String> authHeaders) {
    if (authHeaders == null || authHeaders.isEmpty()) {
      return false;
    }

    var header = authHeaders.getFirst().trim();
    if (!header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return false;
    }

    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return false;
    }

    var separator = decoded.indexOf(':');
    if (separator < 0) {
      return false;
    }

    // Compare both parts in constant time so response timing doesn't leak which part was wrong.
    var usernameMatches = MessageDigest.isEqual(
      decoded.substring(0, separator).getBytes(StandardCharsets.UTF_8),
      username.getBytes(StandardCharsets.UTF_8)
    );
    var passwordMatches = MessageDigest.isEqual(
      decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8),
      password.getBytes(StandardCharsets.UTF_8)
    );
    return usernameMatches & passwordMatches;
  }

  public void start() throws Exception {
    minum = MinumBuilder.build(port, sslPort);
    var wf = minum.getWebFramework();

    wf.registerPreHandler((inputs) -> {
      var request = inputs.clientRequest();
      var path = request.getRequestLine().getPathDetails().getIsolatedPath();

      // The healthcheck stays open so load balancers can reach it without credentials.
      if (authEnabled && !path.equals("healthcheck") && !isAuthorized(request.getHeaders().valueByKey("authorization"))) {
        return Response.buildResponse(
          StatusLine.StatusCode.CODE_401_UNAUTHORIZED,
          Map.of(
            "WWW-Authenticate", "Basic realm=\"JPing\", charset=\"UTF-8\"",
            "Content-Type", "text/plain"
          ),
          "Unauthorized"
        );
      }

      return inputs.endpoint().apply(request);
    });

    wf.registerPath(
      GET,
      "",
      r -> {
        logger.info("Serve /");
        try (var stream = WebServer.class.getResourceAsStream("/html/index.html")) {
          assert stream != null;
          String content = new String(stream.readAllBytes());
          return Response.htmlOk(content);
        }
      }
    );

    wf.registerPath(
      GET,
      "healthcheck",
      req -> {
        return Response.buildResponse(StatusLine.StatusCode.CODE_200_OK, Map.of("Content-Type", "text/plain"), "OK JPing");
      }
    );

    wf.registerPath(GET, "api/websites", req -> {
      var websites = new JsonArray();
      for (var website : db.listWebsites()) {
        websites.add(toJson(website));
      }
      return json(StatusLine.StatusCode.CODE_200_OK, new JsonObject().add("websites", websites));
    });

    wf.registerPath(POST, "api/websites", req -> {
      var url = readUrl(req);
      var urlError = validateUrl(url);
      if (urlError != null) {
        return error(StatusLine.StatusCode.CODE_400_BAD_REQUEST, urlError);
      }

      try {
        var website = db.addWebsite(url, DEFAULT_STATUS);
        logger.info("Added website: " + url);
        return json(StatusLine.StatusCode.CODE_201_CREATED, new JsonObject().add("website", toJson(website)));
      } catch (DatabaseService.DuplicateWebsiteException e) {
        return error(StatusLine.StatusCode.CODE_409_CONFLICT, "This website is already being monitored.");
      }
    });

    wf.registerPath(DELETE, "api/websites", req -> {
      var url = readUrl(req);
      if (url == null || url.isEmpty()) {
        return error(StatusLine.StatusCode.CODE_400_BAD_REQUEST, "The URL is required.");
      }

      if (!db.deleteWebsite(url)) {
        return error(StatusLine.StatusCode.CODE_404_NOT_FOUND, "This website isn't being monitored.");
      }
      logger.info("Deleted website: " + url);
      return json(StatusLine.StatusCode.CODE_200_OK, new JsonObject().add("url", url));
    });
  }

  static final String DEFAULT_STATUS = "active";
  static final int MAX_URL_LENGTH = 2048;

  /** Reads the "url" field from a JSON body. Returns null if the body isn't a JSON object with a string "url". */
  static String readUrl(IRequest req) {
    try {
      var value = Json.parse(req.getBody().asString()).asObject().get("url");
      return value != null && value.isString() ? value.asString().trim() : null;
    } catch (com.eclipsesource.json.ParseException | UnsupportedOperationException e) {
      return null;
    }
  }

  /** Returns an error message, or null when the URL is acceptable. */
  static String validateUrl(String url) {
    if (url == null || url.isEmpty()) {
      return "The URL is required.";
    }
    if (url.length() > MAX_URL_LENGTH) {
      return "The URL is too long.";
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      return "The URL must start with http:// or https://";
    }
    try {
      var host = new URI(url).getHost();
      if (host == null || host.isEmpty()) {
        return "This is not a valid URL.";
      }
    } catch (URISyntaxException e) {
      return "This is not a valid URL.";
    }
    return null;
  }

  static JsonObject toJson(DatabaseService.WebsiteUrl website) {
    return new JsonObject().add("url", website.url()).add("status", website.status());
  }

  static IResponse json(StatusLine.StatusCode code, JsonValue body) {
    return Response.buildResponse(code, Map.of("Content-Type", "application/json"), body.toString());
  }

  static IResponse error(StatusLine.StatusCode code, String message) {
    return json(code, new JsonObject().add("error", message));
  }

  public void block() {
    minum.block();
  }

  public void stop() {
    if (minum != null) {
      MinumBuilder.shutdownQuietly(minum);
    }
  }
}
