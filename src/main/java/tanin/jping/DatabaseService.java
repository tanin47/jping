package tanin.jping;

import tanin.jmigrate.DatabaseConnection;
import tanin.jmigrate.JMigrate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class DatabaseService {

  private static final Logger logger = Logger.getLogger(DatabaseService.class.getName());

  // Postgres error code for a unique/primary key violation.
  private static final String UNIQUE_VIOLATION = "23505";

  public record WebsiteUrl(String url, String status) {}

  public static class DuplicateWebsiteException extends Exception {
    public DuplicateWebsiteException(String url) {
      super("The website is already being monitored: " + url);
    }
  }

  // Accepts postgres://user:password@host:port/db as well as a jdbc:postgresql:// URL.
  final String databaseUrl;

  public DatabaseService(String databaseUrl) throws Exception {
    this.databaseUrl = databaseUrl;
    migrate();
  }

  public void migrate() throws Exception {
    JMigrate.migrate(
      databaseUrl,
      DatabaseService.class, // In the same jar as the migration scripts, so it can read them from the resources.
      "/migrations",
      MinumBuilder.IS_LOCAL_DEV // Allow running down scripts only in local dev, so production data is never dropped automatically.
    );
    logger.info("The database migration has finished.");
  }

  // A new connection per call keeps this simple and safe across Minum's request threads.
  Connection connect() throws SQLException {
    try {
      return new DatabaseConnection(databaseUrl).connection;
    } catch (java.net.URISyntaxException e) {
      throw new SQLException("Invalid database URL", e);
    }
  }

  public List<WebsiteUrl> listWebsites() throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("SELECT url, status FROM website_url ORDER BY url");
      var rs = stmt.executeQuery()
    ) {
      var websites = new ArrayList<WebsiteUrl>();
      while (rs.next()) {
        websites.add(new WebsiteUrl(rs.getString("url"), rs.getString("status")));
      }
      return websites;
    }
  }

  public WebsiteUrl addWebsite(String url, String status) throws SQLException, DuplicateWebsiteException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("INSERT INTO website_url (url, status) VALUES (?, ?)")
    ) {
      stmt.setString(1, url);
      stmt.setString(2, status);
      stmt.executeUpdate();
      return new WebsiteUrl(url, status);
    } catch (SQLException e) {
      if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
        throw new DuplicateWebsiteException(url);
      }
      throw e;
    }
  }

  /** Returns false when no website with this URL exists. Its responses are deleted by ON DELETE CASCADE. */
  public boolean deleteWebsite(String url) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("DELETE FROM website_url WHERE url = ?")
    ) {
      stmt.setString(1, url);
      return stmt.executeUpdate() > 0;
    }
  }
}
