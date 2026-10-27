# Running Streama in the Base44 sandbox

Streama is a Grails 3.2.6 app built with Gradle 3.1, so it needs **JDK 8** — a newer JDK
cannot run this Gradle/Spring Boot 1.4 combination. `Dockerfile.base44` pins
`eclipse-temurin:8-jdk`; the source is bind-mounted and the app runs via `gradlew bootRun`
(the project's dev server, with the asset pipeline compiling Sass/JS from source on request).

## Stack

`docker compose -f docker-compose.base44.yml up -d --build` — two services: `streama` (the app,
internal port 8080) and `preview` (nginx, owns host port **3000** → 80).

**The preview must go through the `preview` proxy** (`.base44/nginx-preview.conf`), not straight
to port 8080. Streama builds *absolute* redirects from the request scheme, and the sandbox proxy
does not forward `X-Forwarded-Proto`, so the app answered `Location: http://<host>/login/auth`,
which browsers block inside the https preview frame (blank preview). The shim forwards
`X-Forwarded-Proto: https`, and serves the login page as a 200 at `/` for anonymous visitors so the
frame gets a real entry document (the app's own `/` is a 302 with an empty body). Signed-in
visitors pass straight through to the app shell. It changes no application behaviour — outside the
sandbox, point the web server/proxy at 8080 directly.

- Grails environment `docker` (the project's own profile from `grails-app/conf/application.yml`),
  which uses an **H2 file database** at `/data/streamadb` — no MySQL service needed.
- `/data` is the `streama_data` volume: it holds the database and the default upload
  directory (`/data/streama`). Deleting the volume wipes all app data.
- `/root/.gradle` is the `gradle_cache` volume, so dependency resolution is only paid once.
- First boot takes several minutes (Gradle distribution + full dependency download +
  Groovy compile). The healthcheck's `start_period` accounts for that; check logs with
  `docker compose -f docker-compose.base44.yml logs -f` for `Grails application running at`.
- Healthcheck probes `GET /login/auth` (public login page). `/` requires auth and 302s there.

## Behaviour worth knowing

- Built-in seed data (`BootStrap` + `DefaultDataService`): roles and an **Admin / 4upocx0v** user
  (only created when the database is empty — an existing database keeps its own credentials).
  The login screen shows that hint while the "First Time Login Info" setting is true.
- **Videos are stored on Mux, not on the server.** Uploading a video on a movie/show page creates a
  Mux direct upload, streams the file to Mux, and stores only the Mux ids on the `File` row
  (`muxUploadId`, `muxAssetId`, `muxPlaybackId`, `muxStatus`). `File.src` then returns the Mux HLS
  URL (`https://stream.mux.com/<playbackId>.m3u8`) instead of `/file/serve/...`.
  - Mux ingests asynchronously: the row is `muxStatus: 'processing'` until `MuxController.fileStatus`
    resolves the playback id. `upload-service.js` polls that endpoint every 5s and only adds the
    file to the UI when it is `ready`.
  - Deleting a video also deletes the Mux asset (`FileService.fullyRemoveFile`).
  - Only videos go to Mux; images and subtitles still use the local upload directory
    (`/data/streama`, the `Upload Directory` setting). That setting is kept in the database and in
    `UploadService`, but is hidden in the Settings UI, and the first-run gate now checks whether Mux
    is configured instead.
- **Credentials:** `Mux Token ID` / `Mux Token Secret` can be entered on the Settings page
  (stored in the database, like the TMDb key) and otherwise fall back to the
  `MUX_TOKEN_ID` / `MUX_TOKEN_SECRET` environment variables, which compose feeds from the
  platform-managed `/run/base44/app.env`. Videos cannot be uploaded until one of the two is set.
  Validating a Mux setting calls the Mux API (`MuxService.checkCredentials`).
- Playback of Mux streams needs HLS: `hls.js` is vendored at
  `grails-app/assets/bower/hls.js/hls.min.js` and required from `vendor.js`;
  `streama-video-player-directive.js` attaches it for any `.m3u8` source. hls.js is used
  whenever `Hls.isSupported()`, and native playback is only the fallback: `canPlayType
  ('application/vnd.apple.mpegurl')` answers `"maybe"` in the sandbox's browser even though it
  cannot play the stream, so it must not be used to skip hls.js.
- **Any new controller action needs a security rule.** `grails-app/conf/application.groovy` sets
  `rejectIfNoRule = true` plus an explicit `controllerAnnotations.staticRules` list, so a URL with
  no matching pattern is rejected even for a logged-in admin — Spring Security redirects it to
  `/login/full` (which reads like an auth problem but is really a missing rule). The Mux endpoints
  are listed there: `/mux/configured` (authenticated-remembered, used by the dashboard gate) and
  `/mux/fileStatus` (content manager, used while polling a Mux upload).
- The dev server serves the JavaScript as individual source files (`?compile=false`), not as a
  bundled `application.js` — so a page reload picks up JS edits, but `/assets/application.js` does
  not exist. Groovy edits still need a `streama` restart.
- The **TheMovieDB API key** is still a *required* setting: `Save Settings` stays disabled while it
  is empty. It is stored in the database via the Settings UI, not in the environment.
- Edits to GSPs, Sass/JS assets and templates are picked up without a restart. Changes to
  Groovy classes/domain objects need `docker compose -f docker-compose.base44.yml restart streama`
  (the Grails 3.2 spring-loaded agent is not attached when running the `docker` environment),
  followed by a preview reload.
- `deploy/` is the **public deployment** stack (Oracle Cloud Always Free VM: Caddy for HTTPS +
  the release jar in the `docker` environment, one `streama_data` volume for DB and videos).
  It is independent of this sandbox compose and must not be used for previewing — see
  `deploy/README.md`.
- `docker/Dockerfile` + `docker/docker-compose.yml` are the upstream release-image setup
  (downloads the published jar) — deliberately not used here, since they cannot reflect source edits.

## Tests

`./gradlew test` inside the container compiles the whole app and starts an H2 test database:

```sh
docker compose -f docker-compose.base44.yml exec -T streama ./gradlew --no-daemon test
```
