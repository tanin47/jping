JPing
==============================

[![Sonatype Central](https://maven-badges.sml.io/sonatype-central/io.github.tanin47/jping/badge.svg)](https://central.sonatype.com/artifact/io.github.tanin47/jping)
![Github Actions](https://github.com/tanin47/jping/actions/workflows/ci.yml/badge.svg?branch=main)
[![codecov](https://codecov.io/gh/tanin47/jping/graph/badge.svg?token=XQKPZUG2LA)](https://codecov.io/gh/tanin47/jping)


JPing is a minimal and robust website monitoring written in Java. You can run it as a standalone server (using a single JAR file or the docker image) or embed it into your larger Java system.

You'll know your sites' uptime at all times. No more guesswork. Plus, it looks great on both desktop and mobile.

Demo: https://jping.summerarea.com/

| Desktop | Mobile |
| :---: | :---: |
| <img width="550" alt="JPing desktop screenshot" src="screenshot.png" /> | <img width="180" alt="JPing mobile screenshot" src="https://github.com/user-attachments/assets/c187d807-f50f-4088-bc4c-93163a11b089" /> |

Features
---------

* Checks each URL once a minute. A check succeeds on a 2xx, 3xx, or 4xx status code, and fails on a 5xx status code or
  when the URL doesn't respond within 10 seconds.
* Shows an uptime timeline for each URL over the last 24 hours, 7 days, 30 days, or 90 days.
* Tracks JPing's own uptime, so you can tell a down website apart from a time when JPing itself wasn't running.
* Refreshes the page automatically every 30 seconds.
* Protected by HTTP Basic Authentication.
* An optional public access, which lets anyone view the uptimes while only `/admin` can add and delete URLs.
* A `/healthcheck` endpoint for load balancers. It never requires the credentials.

Motivation
-----------

I run multiple VPSes for various side projects. One of my servers hosts all of my low-traffic apps, and whenever one of them actually takes off, I migrate it over to its own dedicated VPS.

With VPS, it requires more attention on uptime/reliability compared to using Vercel, Render, Heroku, and other PaaSes. I need a way to keep track my VPS's uptime and reliability.

So, I built JPing, which is a website monitoring system that pings your URLs every minute and presents everything on a clean, beautiful dashboard. Because I can just spin it up right on my main server alongside my other small apps, it doesn't cost me an extra dime in hosting fees.


Configuration
--------------

JPing is configured with the env variables below:

| Env                                 | Required | Default | Description                                                                                |
|-------------------------------------|----------|---------|--------------------------------------------------------------------------------------------|
| `JPING_DATABASE_URL`                | Yes      |         | The Postgres database, e.g. `postgres://user:password@localhost:5432/jping`. A `jdbc:postgresql://` URL works too. |
| `JPING_PORT`                        | No       | `9090`  | The HTTP port.                                                                             |
| `JPING_SSL_PORT`                    | No       | `8443`  | The HTTPS port. The web framework always starts an HTTPS server, so this port must be free. |
| `JPING_USERNAME` / `JPING_PASSWORD` | Yes      |         | The credentials for HTTP Basic Authentication. Neither can be empty.                       |
| `JPING_PUBLIC_ACCESS`               | No       |         | Set to `true` to let anyone view the uptimes. See [Public access](#public-access).          |

JPing creates its tables on startup, so the database only needs to exist.

### Public access

By default, every page and API requires the credentials, and `/` shows the controls for adding and deleting URLs.

With `JPING_PUBLIC_ACCESS=true`:

* Anyone can open `/` and view the uptimes. The form for adding a URL and the delete buttons are hidden.
* `/admin` asks for the credentials and shows the same page with the add form and the delete buttons.
  The home page doesn't link to `/admin`, so you have to type it in.
* Adding and deleting URLs always require the credentials.


How to run
------------

There are 2 ways to run JPing:

1. Run as a standalone: JAR, Docker, and Render.com
2. Embed JPing into a larger system

### 1. Run as a standalone

__<ins>Run from the JAR file</ins>__

First, download the `jping-VERSION.jar` file from the [Releases](https://github.com/tanin47/jping/releases) page.

Then, run the command below with Java 21 or newer:

```
JPING_DATABASE_URL=postgres://user:password@localhost:5432/jping \
JPING_USERNAME=admin \
JPING_PASSWORD=change-me \
java -jar jping-1.7.0.jar
```

Then, visit http://localhost:9090

__<ins>Use Docker</ins>__

The docker image is here: https://hub.docker.com/repository/docker/tanin47/jping

```
docker run -p 9090:9090 \
           -e JPING_DATABASE_URL=postgres://user:password@host.docker.internal:5432/jping \
           -e JPING_USERNAME=admin \
           -e JPING_PASSWORD=change-me \
           --pull always \
           tanin47/jping:v1.7.0
```

Inside the container, `localhost` is the container itself. Use `host.docker.internal` to reach a Postgres database
running on your machine.

To keep secrets out of your shell history, put the env variables in a file and use `--env-file jping.env` instead of
`-e`.

__<ins>Use Render.com</ins>__

The file [render.yaml](./render.yaml) shows a blueprint example of how to run JPing on Render. Render asks for
`JPING_DATABASE_URL`, `JPING_USERNAME`, `JPING_PASSWORD`, and `JPING_PUBLIC_ACCESS` when you create the service from the blueprint.

### 2. Embed JPing into a larger system

1. Add JPing as a dependency of the larger system:

```
<dependency>
    <groupId>io.github.tanin47</groupId>
    <artifactId>jping</artifactId>
    <version>1.7.0</version>
</dependency>
```

See the latest version [here](https://central.sonatype.com/artifact/io.github.tanin47/jping)

2. Start JPing when the larger system initializes:

```java
import tanin.jping.WebServer;

var server = new WebServer(
  9090, // The HTTP port
  8443, // The HTTPS port
  "admin", // The username for HTTP Basic Authentication
  "change-me", // The password for HTTP Basic Authentication
  false, // Whether the public access is enabled. See "Public access" above.
  "postgres://user:password@localhost:5432/jping"
);
server.start();
```

`WebServer` doesn't read any env variables, so the larger system passes everything in. It throws when the username or
password is empty.

3. Visit http://localhost:9090 to confirm that the embedded JPing is working.

FAQ
-----

### What if we cannot open another port?

Some services like Render or Heroku allow only one port to be served.

What you can do here is to designate a path e.g. `/jping` where it proxies to JPing.

An example proxy code that requires no dependency would look like below:

```java
// In your endpoint of /jping
var client = HttpClient.newHttpClient();
var httpRequest = HttpRequest
    .newBuilder()
    .uri(URI.create("http://localhost:9090" + path)) // The path without /jping
    .method("GET", HttpRequest.BodyPublishers.ofByteArray(new byte[0])) // Set the method and body in bytes
    .headers(/* ... */) // Forward the headers as-is.
    .build();
var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

// Return the response as-is
```

### What are the dependencies?

JPing aims to be as small as possible. Therefore, the dependencies are chosen because of their minimalism and small sizes.

1. [Minum](https://github.com/byronka/minum): the smallest Java webapp framework.
2. [JMigrate](https://github.com/tanin47/jmigrate): the simplest database schema migration management.
3. [Postgres JDBC Driver](https://jdbc.postgresql.org/): Postgres is used as the database.
4. [Minimal JSON](https://github.com/ralfstx/minimal-json): the smallest JSON library.

How to develop
---------------

1. Run `npm install` to install all dependencies.
2. Run `./setup/setup_db.sh` to create the local Postgres databases: `jping_dev` for development and `jping_test` for
   the tests.
3. On a separate terminal, run `npm run hmr`. It serves the frontend code and hot-reloads its changes.
4. Run `./gradlew run` to run the web server at http://localhost:9090.
   * It sets `JPING_LOCAL_DEV=true`, which turns on the dev mode, and uses the `jping_dev` database.
   * The username is `admin` and the password is `dev`.
   * Hot-reloading is only supported on the frontend code.

To fill the dev database with fake data, add a few URLs first, then run:

```
psql postgres://jping_dev_user:dev@localhost:5432/jping_dev -f setup/fake_responses.sql
psql postgres://jping_dev_user:dev@localhost:5432/jping_dev -f setup/fake_jping_uptime.sql
```

How to run the tests
---------------------

1. Make sure `npm run hmr` is running. The tests run in the dev mode, so the browser tests can't load the frontend
   without it.
2. Run `HEADLESS=true ./gradlew test`. The tests use the `jping_test` database, and the browser tests need Chrome.
   Without `HEADLESS=true`, Chrome opens a window for each browser test.


Publish JAR
------------

This flow has been set up as the Github Actions workflow: `publish-jar`.

Here's how you can build the fat JAR:

1. Run `./gradlew clean publish`. This step is IMPORTANT to clean out the previous versions.

The fat JAR is built at `./build/staging-deploy/io/github/tanin47/jping/VERSION/jping-VERSION.jar`

You can run it with: `JPING_DATABASE_URL=... java -jar ./build/staging-deploy/io/github/tanin47/jping/VERSION/jping-VERSION.jar`

To publish to a Maven repository, please follow the below steps:

1. Set up `~/.jreleaser/config.toml` with `JRELEASER_MAVENCENTRAL_USERNAME` and `JRELEASER_MAVENCENTRAL_PASSWORD`
2. Run `./gradlew jreleaserDeploy`


Publish Docker
---------------

This flow has been set up as a part of the Github Actions workflow: `create-release-and-docker`.

1. Run `./gradlew clean publish` to build the fat JAR, which the Dockerfile copies into the image.
2. Run `docker buildx build --platform linux/amd64,linux/arm64 -t jping:v1.7.0 .`
3. Test locally with:
   `docker run -p 9090:9090 -e JPING_DATABASE_URL=postgres://user:password@host.docker.internal:5432/jping -e JPING_USERNAME=admin -e JPING_PASSWORD=change-me jping:v1.7.0`
4. Run: `docker tag jping:v1.7.0 tanin47/jping:v1.7.0`
5. Run: `docker push tanin47/jping:v1.7.0`
6. Go to Render.com, sync the blueprint, and test that it works

Release a new version
----------------------

1. Create an empty release with a new tag. The tag must follow the format: `vX.Y.Z`.
2. Go to Actions and wait for the `create-release-and-docker` (which is triggered automatically) workflow to finish.
3. Test the docker with
   `docker run -p 9090:9090 -e JPING_DATABASE_URL=postgres://user:password@host.docker.internal:5432/jping -e JPING_USERNAME=admin -e JPING_PASSWORD=change-me tanin47/jping:vX.Y.Z`.
4. Go to Actions and trigger the workflow `publish-jar` on the tag `vX.Y.Z` in order to publish the JAR to Central
   Sonatype.
