package tanin.jping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Pinger {

  private static final Logger logger = Logger.getLogger(Pinger.class.getName());

  static final Duration UPTIME_UPDATE_INTERVAL = Duration.ofSeconds(30);
  static final Duration PING_TIMEOUT = Duration.ofSeconds(10);

  final DatabaseService db;
  final HttpClient httpClient;

  ScheduledExecutorService scheduler;
  ExecutorService pingExecutor;
  Instant uptimeStartedAt;
  Thread shutdownHook;

  public Pinger(DatabaseService db) {
    this.db = db;
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(PING_TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  }

  public synchronized void start() throws Exception {
    if (scheduler != null) {
      return;
    }

    // Postgres stores microseconds, so truncating keeps the value we match on in later updates identical.
    uptimeStartedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    db.insertJpingUptime(uptimeStartedAt, uptimeStartedAt);
    logger.info("Started JPing's uptime at " + uptimeStartedAt);

    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      var thread = new Thread(r, "jping-pinger");
      thread.setDaemon(true);
      return thread;
    });
    pingExecutor = Executors.newVirtualThreadPerTaskExecutor();

    var intervalMillis = UPTIME_UPDATE_INTERVAL.toMillis();
    scheduler.scheduleAtFixedRate(this::updateUptime, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    scheduleNextPing();

    shutdownHook = new Thread(() -> {
      logger.info("Received a shutdown signal. Stopping the pinger...");
      stop();
    }, "jping-pinger-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  public synchronized void stop() {
    if (scheduler == null) {
      return;
    }
    scheduler.shutdownNow();
    pingExecutor.shutdownNow();
    scheduler = null;
    pingExecutor = null;
    updateUptime();
  }

  void updateUptime() {
    try {
      var endedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      db.updateJpingUptimeEndedAt(uptimeStartedAt, endedAt.plusSeconds(60));
      logger.info("Updated JPing's uptime (started at " + uptimeStartedAt + ") to end at " + endedAt);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not update JPing's uptime", e);
    }
  }

  // Scheduling one minute at a time (instead of a fixed rate) keeps every run aligned to the minute without drift.
  synchronized void scheduleNextPing() {
    if (scheduler == null) {
      return;
    }
    var now = Instant.now();
    var nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
    scheduler.schedule(
      () -> pingAll(nextMinute),
      Duration.between(now, nextMinute).toMillis(),
      TimeUnit.MILLISECONDS
    );
  }

  void pingAll(Instant minute) {
    try {
      for (var website : db.listWebsites()) {
        pingExecutor.submit(() -> ping(website.url(), minute));
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not start the pings for " + minute, e);
    } finally {
      scheduleNextPing();
    }
  }

  void ping(String url, Instant scheduledAt) {
    var startedAt = Instant.now();
    try {
      db.insertResponse(url, scheduledAt, startedAt);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not insert the response row for " + url, e);
      return;
    }

    Integer responseCode = null;
    String error = null;
    var pingStartedAt = Instant.now();
    var requestStartedAt = System.nanoTime();
    try {
      var request = HttpRequest.newBuilder(URI.create(url))
        .timeout(PING_TIMEOUT)
        .header("User-Agent", "JPing")
        .GET()
        .build();
      responseCode = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (HttpTimeoutException e) {
      error = "Timed out after " + PING_TIMEOUT.toSeconds() + " seconds";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "Interrupted";
    } catch (Exception e) {
      error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    var finishedAt = Instant.now();
    var durationMillis = (System.nanoTime() - requestStartedAt) / 1_000_000;
    if (error == null) {
      logger.info("Pinged " + url + " (scheduled at " + scheduledAt + "): status " + responseCode + " in " + durationMillis + "ms");
    } else {
      logger.warning("Pinging " + url + " (scheduled at " + scheduledAt + ") failed after " + durationMillis + "ms: " + error);
    }

    try {
      if (!db.finishResponse(url, scheduledAt, pingStartedAt, finishedAt, responseCode, error)) {
        logger.info("Skipped updating the response for " + url + " (scheduled at " + scheduledAt + ") because it's already finished");
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not update the response row for " + url, e);
    }
  }
}
