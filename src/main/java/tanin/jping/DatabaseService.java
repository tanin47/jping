package tanin.jping;

import tanin.jmigrate.DatabaseConnection;
import tanin.jmigrate.JMigrate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

public class DatabaseService {

  private static final Logger logger = Logger.getLogger(DatabaseService.class.getName());

  // Postgres error code for a unique/primary key violation.
  private static final String UNIQUE_VIOLATION = "23505";

  public record WebsiteUrl(String url, String status) {}

  /**
   * One slice of the uptime timeline, covering [start, end).
   * checks is the number of checks that started in the slice; successfulChecks is how many of them got an OK response.
   */
  public record Bucket(Instant start, Instant end, int checks, int successfulChecks) {
    /** The uptime percentage (0..100), or null when there were no checks in this bucket. */
    public Double uptime() {
      return checks == 0 ? null : successfulChecks * 100.0 / checks;
    }
  }

  /** A website with its uptime timeline, split into UPTIME_BUCKET_COUNT buckets ordered from oldest to newest. */
  public record WebsiteUrlUptime(String url, String status, List<Bucket> buckets) {}

  public static final int UPTIME_BUCKET_COUNT = 90;

  /** A period when JPing itself was running, covering [startedAt, endedAt). */
  public record UpInterval(Instant startedAt, Instant endedAt) {}

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

  /**
   * Returns every website with its uptime between start and end, split into UPTIME_BUCKET_COUNT equal buckets.
   */
  public List<WebsiteUrlUptime> listWebsiteUptimes(Instant start, Instant end) throws SQLException {
    var bucketMillis = (end.toEpochMilli() - start.toEpochMilli()) / (double) UPTIME_BUCKET_COUNT;
    // The last edge is exactly `end` so rounding never leaves a gap.
    var edges = new Instant[UPTIME_BUCKET_COUNT + 1];
    for (int i = 0; i < UPTIME_BUCKET_COUNT; i++) {
      edges[i] = Instant.ofEpochMilli(start.toEpochMilli() + Math.round(i * bucketMillis));
    }
    edges[UPTIME_BUCKET_COUNT] = end;

    var checksByUrl = new HashMap<String, int[][]>();
    try (
      var conn = connect();
      // width_bucket(value, thresholds) returns i when thresholds[i] <= value < thresholds[i + 1] (1-based),
      // so each bucket includes its start and excludes its end.
      // Checks that haven't finished yet are skipped so they don't count as failures.
      var stmt = conn.prepareStatement("""
        SELECT
          url,
          width_bucket(started_at, ?::timestamptz[]) AS bucket,
          count(*) AS checks,
          count(*) FILTER (WHERE response_code BETWEEN 200 AND 499) AS successful_checks
        FROM response
        WHERE started_at >= ? AND started_at < ? AND finished_at IS NOT NULL
        GROUP BY url, bucket
        """)
    ) {
      var bucketStarts = new Timestamp[UPTIME_BUCKET_COUNT];
      for (int i = 0; i < UPTIME_BUCKET_COUNT; i++) {
        bucketStarts[i] = Timestamp.from(edges[i]);
      }
      stmt.setArray(1, conn.createArrayOf("timestamptz", bucketStarts));
      stmt.setTimestamp(2, Timestamp.from(start));
      stmt.setTimestamp(3, Timestamp.from(end));

      try (var rs = stmt.executeQuery()) {
        while (rs.next()) {
          var counts = checksByUrl.computeIfAbsent(rs.getString("url"), k -> new int[UPTIME_BUCKET_COUNT][2]);
          var index = rs.getInt("bucket") - 1;
          counts[index][0] = rs.getInt("checks");
          counts[index][1] = rs.getInt("successful_checks");
        }
      }
    }

    var result = new ArrayList<WebsiteUrlUptime>();
    for (var website : listWebsites()) {
      var counts = checksByUrl.getOrDefault(website.url(), new int[UPTIME_BUCKET_COUNT][2]);
      var buckets = new ArrayList<Bucket>(UPTIME_BUCKET_COUNT);
      for (int i = 0; i < UPTIME_BUCKET_COUNT; i++) {
        buckets.add(new Bucket(edges[i], edges[i + 1], counts[i][0], counts[i][1]));
      }
      result.add(new WebsiteUrlUptime(website.url(), website.status(), buckets));
    }
    return result;
  }

  public void insertJpingUptime(Instant startedAt, Instant endedAt) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("INSERT INTO jping_uptime (started_at, ended_at) VALUES (?, ?)")
    ) {
      stmt.setTimestamp(1, Timestamp.from(startedAt));
      stmt.setTimestamp(2, Timestamp.from(endedAt));
      stmt.executeUpdate();
    }
  }

  public void updateJpingUptimeEndedAt(Instant startedAt, Instant endedAt) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("UPDATE jping_uptime SET ended_at = ? WHERE started_at = ?")
    ) {
      stmt.setTimestamp(1, Timestamp.from(endedAt));
      stmt.setTimestamp(2, Timestamp.from(startedAt));
      stmt.executeUpdate();
    }
  }

  /** Inserts an unfinished check. finishResponse fills in its result. */
  public void insertResponse(String url, Instant startedAt) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("INSERT INTO response (url, started_at) VALUES (?, ?)")
    ) {
      stmt.setString(1, url);
      stmt.setTimestamp(2, Timestamp.from(startedAt));
      stmt.executeUpdate();
    }
  }

  public void finishResponse(String url, Instant startedAt, Instant finishedAt, Integer responseCode, String error) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement(
        "UPDATE response SET finished_at = ?, response_code = ?, error = ? WHERE url = ? AND started_at = ?"
      )
    ) {
      stmt.setTimestamp(1, Timestamp.from(finishedAt));
      stmt.setObject(2, responseCode);
      stmt.setString(3, error);
      stmt.setString(4, url);
      stmt.setTimestamp(5, Timestamp.from(startedAt));
      stmt.executeUpdate();
    }
  }

  /**
   * Returns the periods when JPing was running that overlap [start, end), ordered by start time.
   * Each interval is clipped to [start, end).
   */
  public List<UpInterval> listJpingUpIntervals(Instant start, Instant end) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement(
        "SELECT started_at, ended_at FROM jping_uptime WHERE started_at < ? AND ended_at > ? ORDER BY started_at"
      )
    ) {
      stmt.setTimestamp(1, Timestamp.from(end));
      stmt.setTimestamp(2, Timestamp.from(start));
      try (var rs = stmt.executeQuery()) {
        var intervals = new ArrayList<UpInterval>();
        while (rs.next()) {
          var startedAt = rs.getTimestamp("started_at").toInstant();
          var endedAt = rs.getTimestamp("ended_at").toInstant();
          intervals.add(new UpInterval(
            startedAt.isBefore(start) ? start : startedAt,
            endedAt.isAfter(end) ? end : endedAt
          ));
        }
        return intervals;
      }
    }
  }
}
