# Building from Source

```sh
git clone https://github.com/anand34577/lekhamint.git
cd lekhamint
```

| Part | Folder | Needs |
|---|---|---|
| Server | `server/` | Go 1.26+ (and `make`) |
| Web app | `web/` | Node.js 20+ |
| Android app | `android/` | JDK 17 and the Android SDK (API 36) |

## Server

```sh
cd server
make demo        # run with a year of sample data: http://localhost:8080, demo@example.com / demo1234
make run         # run with your own data in ./data
make test        # all tests; set LEKHAMINT_TEST_PG_DSN to a PostgreSQL URL to test that too
make build       # dist/lekhamint
make release     # dist/lekhamint-<os>-<arch> for every platform
```

The binary has no C dependencies (the SQLite driver is pure Go), so cross-compiling just works.

## Web app

```sh
cd web
npm ci
npm run dev      # http://localhost:5173, talks to a server on :8080 (run `make demo` in server/)
npm test
```

The server embeds the built web app from `server/internal/webui/dist`. After changing `web/`, run `make web` in `server/` to rebuild and embed it.

**The online demo** is the web app plus the server compiled to WebAssembly: `make demo-site` in `server/` writes it to `web/dist-demo/`. Serve that folder with any static file server.

## Android app

```sh
cd android
./gradlew testDirectDebugUnitTest   # unit tests
./gradlew assembleDirectDebug      # app/build/outputs/apk/direct/debug/app-direct-debug.apk
./gradlew connectedDirectDebugAndroidTest   # on an emulator or device
```

There are two editions (the `store` flavor in `app/build.gradle.kts`):

- **direct**: the APK people download and install themselves (`assembleDirectRelease`). It leaves out reading bank and payment alerts, because Google Play Protect blocks sideloaded apps that ask for notification access in many countries, with no "install anyway" option. Sharing an SMS or alert to the app still works.
- **play**: the Google Play build (`bundlePlayRelease`), with alert reading. Play reviews that permission, so it installs normally from the store.

Release builds are signed with the key from `android/keystore.properties` (ignored by git):

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

or from the environment variables `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Without a key the build stops; pass `-PallowDebugSigning=true` for a local test build. Play Protect warns about, and may block, apps signed with a debug key, so share only APKs signed with your release key.

## Docker image

```sh
docker build -t lekhamint .
```

## Releases

Pushing a tag such as `v1.2.0` makes GitHub Actions build the signed APK, the server for every platform and the multi-arch Docker image, and publish them with release notes from `CHANGELOG.md`. The repository needs the secrets `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.

## Design documents

The [`docs/`](https://github.com/anand34577/lekhamint/tree/main/docs) folder explains the product, the money rules, the architecture, the data model, the sync protocol and the API in depth. Contributions are welcome: see [CONTRIBUTING.md](https://github.com/anand34577/lekhamint/blob/main/CONTRIBUTING.md).
