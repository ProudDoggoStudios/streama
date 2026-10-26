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
- After login the app forces the first-run **Settings** page and the dashboard redirects there
  until `Upload Directory` has a value. `Save Settings` stays disabled while any required
  setting is empty — the **TheMovieDB API key** is required, so a TMDb key is needed to get
  past setup. That key is stored in the database via the Settings UI; it is **not** an
  environment variable, so it is not part of the compose/secrets wiring.
- `/data/streama` exists and is writable (container runs as root), so it validates as an
  upload directory.
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
