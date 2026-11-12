package tanin.jping;

import tanin.jmigrate.DatabaseConnection;
import tanin.jmigrate.JMigrate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

public class DatabaseService {

  private static final Logger logger = Logger.getLogger(DatabaseService.class.getName());

  // Postgres error code for a unique/primary key violation.
  private static final String UNIQUE_VIOLATION = "23505";

  public record WebsiteUrl(String url) {}

  /**
   * One slice of the uptime timeline, covering [start, end). Both ends are at the start of a minute.
   * total is the number of checks that should have run (one per minute); noData is the ones without a finished result.
   */
  public record Bucket(Instant start, Instant end, int total, int successes, int failures) {
    public int noData() {
      return Math.max(0, total - successes - failures);
    }
  }

  /** A website with its uptime timeline, split into buckets ordered from oldest to newest. */
  public record WebsiteUrlUptime(String url, List<Bucket> buckets) {}

  public static final int DEFAULT_BUCKET_MINUTES = 60;
  public static final int MAX_UPTIME_BUCKET_COUNT = 500;

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
      var stmt = conn.prepareStatement("SELECT url FROM website_url ORDER BY url");
      var rs = stmt.executeQuery()
    ) {
      var websites = new ArrayList<WebsiteUrl>();
      while (rs.next()) {
        websites.add(new WebsiteUrl(rs.getString("url")));
      }
      return websites;
    }
  }

  public WebsiteUrl addWebsite(String url) throws SQLException, DuplicateWebsiteException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("INSERT INTO website_url (url) VALUES (?)")
    ) {
      stmt.setString(1, url);
      stmt.executeUpdate();
      return new WebsiteUrl(url);
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
   * Splits [start, end) into buckets that are bucketMinutes long on the wall clock of zone. Bucket edges are at
   * the start of the day plus a multiple of bucketMinutes, so buckets of an hour or longer start on the hour.
   * Only the first and the last bucket can be shorter: start is rounded down and end is rounded up to the minute,
   * and either may fall between two edges. bucketMinutes must divide a day (1440 minutes).
   * Throws IllegalArgumentException when there would be more than MAX_UPTIME_BUCKET_COUNT buckets.
   */
  public static List<Instant> bucketEdges(Instant start, Instant end, int bucketMinutes, ZoneId zone) {
    var alignedStart = start.truncatedTo(ChronoUnit.MINUTES);
    var alignedEnd = end.truncatedTo(ChronoUnit.MINUTES);
    if (alignedEnd.isBefore(end)) {
      alignedEnd = alignedEnd.plus(Duration.ofMinutes(1));
    }

    var edges = new ArrayList<Instant>();
    edges.add(alignedStart);
    var edge = nextBucketEdge(alignedStart, bucketMinutes, zone);
    while (edge.isBefore(alignedEnd)) {
      if (edges.size() >= MAX_UPTIME_BUCKET_COUNT) {
        throw new IllegalArgumentException("The range has more than " + MAX_UPTIME_BUCKET_COUNT + " buckets.");
      }
      edges.add(edge);
      edge = nextBucketEdge(edge, bucketMinutes, zone);
    }
    edges.add(alignedEnd);
    return edges;
  }

  // The first edge after t. On a daylight saving change, the wall clock jumps, so an edge can come from the
  // transition itself (e.g. the repeated 1:00 when the clock falls back) instead of the next wall-clock multiple.
  static Instant nextBucketEdge(Instant t, int bucketMinutes, ZoneId zone) {
    var zoned = t.atZone(zone);
    var nextMinuteOfDay = (minuteOfDay(zoned) / bucketMinutes + 1) * bucketMinutes;
    var local = zoned.toLocalDate().atStartOfDay().plusMinutes(nextMinuteOfDay);
    var candidate = ZonedDateTime.ofLocal(local, zone, zoned.getOffset()).toInstant();

    var transition = zone.getRules().nextTransition(t);
    if (transition != null && transition.getInstant().isBefore(candidate)) {
      var at = transition.getInstant();
      var atZoned = at.atZone(zone);
      if (atZoned.getSecond() == 0 && minuteOfDay(atZoned) % bucketMinutes == 0) {
        return at;
      }
      return nextBucketEdge(at, bucketMinutes, zone);
    }
    return candidate;
  }

  static int minuteOfDay(ZonedDateTime zoned) {
    return zoned.getHour() * 60 + zoned.getMinute();
  }

  /** Returns every website with its uptime split into the buckets between consecutive edges, which must be ascending. */
  public List<WebsiteUrlUptime> listWebsiteUptimes(List<Instant> edges) throws SQLException {
    var bucketCount = edges.size() - 1;
    var alignedStart = edges.getFirst();
    var alignedEnd = edges.getLast();

    var countsByUrl = new HashMap<String, int[][]>();
    try (
      var conn = connect();
      // width_bucket(value, thresholds) returns i when thresholds[i] <= value < thresholds[i + 1] (1-based),
      // so each bucket includes its start and excludes its end.
      // Checks that haven't finished yet are left out, so they count as no data.
      var stmt = conn.prepareStatement("""
        SELECT
          url,
          width_bucket(scheduled_at, ?::timestamptz[]) AS bucket,
          count(*) FILTER (WHERE response_code BETWEEN 200 AND 499) AS successes,
          count(*) FILTER (WHERE response_code IS NULL OR response_code NOT BETWEEN 200 AND 499) AS failures
        FROM response
        WHERE scheduled_at >= ? AND scheduled_at < ? AND finished_at IS NOT NULL
        GROUP BY url, bucket
        """)
    ) {
      var bucketStarts = new Timestamp[bucketCount];
      for (int i = 0; i < bucketCount; i++) {
        bucketStarts[i] = Timestamp.from(edges.get(i));
      }
      stmt.setArray(1, conn.createArrayOf("timestamptz", bucketStarts));
      stmt.setTimestamp(2, Timestamp.from(alignedStart));
      stmt.setTimestamp(3, Timestamp.from(alignedEnd));

      try (var rs = stmt.executeQuery()) {
        while (rs.next()) {
          var counts = countsByUrl.computeIfAbsent(rs.getString("url"), k -> new int[bucketCount][2]);
          var index = rs.getInt("bucket") - 1;
          counts[index][0] = rs.getInt("successes");
          counts[index][1] = rs.getInt("failures");
        }
      }
    }

    var result = new ArrayList<WebsiteUrlUptime>();
    for (var website : listWebsites()) {
      var counts = countsByUrl.getOrDefault(website.url(), new int[bucketCount][2]);
      var buckets = new ArrayList<Bucket>(bucketCount);
      for (int i = 0; i < bucketCount; i++) {
        var total = (int) Duration.between(edges.get(i), edges.get(i + 1)).toMinutes();
        buckets.add(new Bucket(edges.get(i), edges.get(i + 1), total, counts[i][0], counts[i][1]));
      }
      result.add(new WebsiteUrlUptime(website.url(), buckets));
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
  public void insertResponse(String url, Instant scheduledAt, Instant startedAt) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("INSERT INTO response (url, scheduled_at, started_at) VALUES (?, ?, ?)")
    ) {
      stmt.setString(1, url);
      stmt.setTimestamp(2, Timestamp.from(scheduledAt));
      stmt.setTimestamp(3, Timestamp.from(startedAt));
      stmt.executeUpdate();
    }
  }

  /** Returns false, and changes nothing, when the check already has a finished_at. */
  public boolean finishResponse(
    String url,
    Instant scheduledAt,
    Instant startedAt,
    Instant finishedAt,
    Integer responseCode,
    String error
  ) throws SQLException {
    try (
      var conn = connect();
      var stmt = conn.prepareStatement("""
        UPDATE response SET started_at = ?, finished_at = ?, response_code = ?, error = ?
        WHERE url = ? AND scheduled_at = ? AND finished_at IS NULL
        """)
    ) {
      stmt.setTimestamp(1, Timestamp.from(startedAt));
      stmt.setTimestamp(2, Timestamp.from(finishedAt));
      stmt.setObject(3, responseCode);
      stmt.setString(4, error);
      stmt.setString(5, url);
      stmt.setTimestamp(6, Timestamp.from(scheduledAt));
      return stmt.executeUpdate() > 0;
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
