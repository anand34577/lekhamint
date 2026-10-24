# Contributing

Thanks for helping. Bug reports, ideas, docs fixes and code are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so we can agree on the approach.
- Read the design notes in [`docs/`](docs): especially [02 – Expense domain](docs/02-expense-domain.md) for the money rules (transfers are not spending, refunds reduce spending, amounts are integers in minor units) and [05 – Sync protocol](docs/05-sync-protocol.md). Changes must keep the Android app, the web app and the server agreeing on these.

## Setting up

See [Building from Source](https://github.com/anand34577/lekhamint/wiki/Building-from-Source). The quickest loop:

```sh
cd server && make demo          # server with sample data on :8080
cd web && npm ci && npm run dev # web app with hot reload on :5173
```

## Pull requests

- Keep each pull request to one change, with tests for behaviour changes.
- Run the checks for the parts you touched:
  - `cd server && gofmt -l . && go vet ./... && go test ./...`
  - `cd web && npm test && npm run build`
  - `cd android && ./gradlew testDebugUnitTest`
- User-facing changes get a line in `CHANGELOG.md` under *Unreleased*, written for people who use the app.
- Docs for people running LekhaMint live in `docs/wiki/` and are published to the wiki automatically.

CI runs all of the above, plus PostgreSQL tests, the Docker image, and the Linux installer.

## Licence

By contributing you agree that your work is released under the [MIT licence](LICENSE).
