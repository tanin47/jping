package tanin.jping;

public class Main {
  static final int DEFAULT_PORT = 9090;
  static final int DEFAULT_SSL_PORT = 8443;

  public static void main(String[] args) throws Exception {
    var databaseUrl = System.getenv("JPING_DATABASE_URL");
    if (databaseUrl == null || databaseUrl.isEmpty()) {
      System.err.println("JPING_DATABASE_URL is required, e.g. postgres://user:password@localhost:5432/jping");
      System.exit(1);
    }

    var server = new WebServer(
      readPortFromEnv("JPING_PORT", DEFAULT_PORT),
      readPortFromEnv("JPING_SSL_PORT", DEFAULT_SSL_PORT),
      databaseUrl
    );
    server.start();
    server.block();
  }

  /** Returns the port in the env variable, or the default when it isn't set. Exits on an invalid value. */
  static int readPortFromEnv(String name, int defaultPort) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultPort;
    }

    try {
      var port = Integer.parseInt(value.trim());
      if (port >= 1 && port <= 65535) {
        return port;
      }
    } catch (NumberFormatException ignored) {
      // Falls through to the error below.
    }

    System.err.println(name + " must be a port number between 1 and 65535, but got: " + value);
    System.exit(1);
    return -1; // Unreachable.
  }
}
