# Changelog

## 1.5.0

**Sign-in and account security**
- Two-step sign-in with any authenticator app (Google Authenticator, Aegis, 1Password…): scan a QR code or type the key, verify one code, and keep ten one-time recovery codes that you can regenerate. Turning it on signs out other devices; each code works once. Web and Android.
- "Forgot password" on the web emails a one-hour, single-use reset link (needs mail and the public address configured on the server); resetting signs out every device. The Android sign-in screen links to it.
- Every record keeps its change history: who changed it, from which device, when, and exactly which fields went from what to what — shown on transactions ("Show change history") and kept for a year by default (`LEKHAMINT_HISTORY_DAYS`).
- The Trash can empty itself: set `LEKHAMINT_TRASH_DAYS` (30 or more) and deleted items are removed for good after that long; a device that last synced before the purge is told to re-download the book instead of silently missing the deletes.

**Goals, investments and the tax year**
- Savings goals (web and Android): name, target amount and date, accounts whose balances count towards it, and money already set aside elsewhere. Each goal shows progress, a status (ahead of plan, on track, behind, past the date) and how much to put aside each month; the dashboard shows the goals nearest their date.
- Investment valuations (web and Android): record what an investment, savings or "other" account is worth on a date — fund statement, broker, gold rate — with a note. The app shows the gain or loss against what you put in, and once a valuation exists, net worth and the accounts list count the account at market value.
- Year & tax (web and Android): a financial-year summary of income, spending and the share of income kept, month by month, plus a tax summary that totals spending in categories marked with a section (80C, 80D, HRA, 24(b)…) with limit meters; home-loan principal and interest are pulled from Loans on the web. Any category can be tagged with a section; a record for proof-gathering, not tax advice. The year's summary exports to CSV.

**Receipts and quicker entry**
- Attach receipts and bills to any transaction — photos or PDFs, from a file or the phone camera — on the web and Android. Files are stored on your own server, deduplicated by content, synced between devices, and open in your device's viewer; removing one goes to Trash. `LEKHAMINT_MAX_ATTACHMENT_MB` caps the size (default 10).
- Android: share text, a photo or a PDF from any app to LekhaMint. A bank or payment SMS is parsed into a prefilled transaction (amount, direction, payee, note); a shared image or PDF becomes that transaction's receipt.
- Web: import OFX, QFX and QIF statement files directly, with no column mapping; statement IDs become fingerprints, so re-importing an overlapping statement creates no duplicates.
- Web: pick a category by hand for a payee no rule covers, and a toast offers to make that the rule for next time.
- Web: Activity gains a calendar view — per-day in and out, heat shading by spend, tap a day to filter the list; list or calendar is remembered.

**Insights and staying on top**
- New Insights page (web): a 30/60/90-day cash-flow forecast from known bills, EMIs and your recent everyday spending, with likely, cautious and hopeful lines, a "could run short around …" warning and a plain-words explainer of how it is worked out.
- Subscriptions (web, on Insights): repeat payments are found in your history — same payee, steady interval, steady amount — with their yearly cost, price-rise flags ("was ₹499"), a "stopped?" nudge for stale ones, and one tap to track one as a recurring bill.
- Weekly summary email: Monday morning, last week's spending against your usual week, the biggest categories, and what is due. On by default for each person; turn it off in Settings → Notifications.
- Budgets (web): a "plan your month" card compares what the budgets plan against income, warns when every rupee is not given a job or too much is planned, and offers suggested budgets for your biggest uncovered categories based on the last three months.
- Debts (web): "Send a reminder" on a person who owes you opens an editable, friendly message with the amount and date, ready for WhatsApp, SMS or copy. LekhaMint itself sends nothing.
- Settings (web): "Fetch today's rates" pulls European Central Bank reference rates for the currencies your accounts use (`LEKHAMINT_FX_URL`; `off` hides the button).
- The dashboard's getting-started checklist walks a new book through accounts, a first transaction, regular income, a budget, a goal and two-step sign-in, ticking itself off as you go.

**Web: assistant, charts and explanations**
- The assistant works like a chat app: it fills the screen with the question box pinned at the bottom, keeps a list of conversations (on this device, cleared on sign-out) that can be reopened or deleted, streams each answer as it is written, shows each lookup as a step while it works, and offers Edit, Regenerate, Copy and suggested follow-up questions. Answers draw the figures they used as charts (spending by category, monthly trend, net worth, loans, deposits, budgets, what is coming up). The server proxy relays streamed answers.
- Every calculator explains its result “in simple words”, step by step with your own numbers, and shows it as a chart: EMI principal against interest and a year-by-year split, how much of your income a loan would take, how a deposit grows, a savings path to the goal, months of essentials covered, total debt over time for each payoff order, each person's share of a bill and who pays whom. Tools are grouped into borrowing, saving and everyday money.
- Charts on Loans (repaid, interest paid, still owed, interest to come), Deposits (when each deposit pays back), Accounts (what you have against what you owe). Line charts gain axis values, gridlines and hover dots.
- Loading: a thin progress bar under the top bar while anything loads or saves, spinners on long actions (export, import, change password, saving), and labelled loading states.
- Printing and “Save as PDF” no longer show black page margins with a dark theme; share columns in printed reports get small bars.
- Settings: the account card's buttons are laid out properly (Change password with the form; Sign out and Delete account in their own row).
- Light theme: stronger field borders and placeholder text, so suggested values in calculators are easy to read.
- With no AI model configured, the assistant still answers common questions — "how much did I spend this month?", "what is coming up?", "spent on Swiggy" — straight from the app's own look-ups, with a chart and a note that no model was used; the welcome screen offers tappable quick questions.
- Groq and Google Gemini (both with a free tier) join the assistant's provider list, and the Ollama note now gives step-by-step setup instructions.
- Calculators lead into the app: an EMI result offers "took this loan? Track it in Loans & EMIs" with the form pre-filled, and the goal calculator offers "save as a goal".

**Offline and sync**
- The web app queues changes made while offline and sends them in order when the connection returns ("Saved N changes made while offline"), with an "N waiting" banner and a clear error if the server refuses one. Signing out discards the queue.
- Android rides out a server-side Trash purge: instead of failing, the sync re-downloads the book and marks records the server no longer has as deleted.

**Server**
- The assistant proxy relays streamed answers word by word instead of waiting for the whole reply.
- `/api/v1/meta` now tells apps which features the server offers (two-step sign-in, password reset, attachments, rate fetching, history), so the matching buttons hide themselves where a feature is absent.
- Backups include attachments and two-step settings (dump format 2); older dump files still restore. Schema v8 adds goals, valuations, attachments, change history and two-step sign-in.
- The demo book seeds goals, mutual-fund valuations, insurance premiums with tax sections and a rising subscription, so every new feature shows something.

## 1.4.0

**Audit round 3 (the gaps left over)**
- Two devices editing the same record at once no longer lose one edit: the app tells the server which version an edit started from, and the server keeps each device's changed fields (the same field changed on both still goes to the newer write). Android DB v6.
- Swipe a transaction left in Activity to move it to Trash, with Undo. Debts, loans and deposits entries are not swipeable.
- Forms with several fields (lent or borrowed, repayments, loans, deposits, balance check, backup password) open as bottom sheets that scroll with the keyboard instead of cramped dialogs.
- Reports can show every currency together ("All in INR") using your exchange rates; currencies without a rate are named and left out.

**Audit round 2 (all platforms)**
- Credit cards: optional statement day and payment due day (schema v7); a reminder goes out three days before the due date while the card owes money; due countdown on the card and account list; a "Pay card bill" button opens a transfer with the amount and paying account filled in (web and Android).
- Lent and borrowed on Android now groups by person with a running statement, "lent more", and repayments spread over the oldest debts first; editing a debt's amount or date keeps its account entry in step (web and Android).
- Amounts typed with a decimal comma ("12,50") are read correctly instead of as 1,250 (web and Android).
- Android: exchange rates screen and "all currencies together" totals on Home and Accounts; this-month totals include other currencies when rates exist; a scrubbable spending-pace chart against last month; tap a bar in the monthly chart for exact amounts; long-press to select transactions for bulk delete or re-categorise, with Undo.
- Android: Home and budgets roll over at midnight or a month end instead of staying on the old day; account history and search load in pages; transactions that belong to a debt, loan or deposit keep their type; deleting an account that recurring rules, loans or deposits use now offers archiving instead.
- Indexes for large ledgers: schema v6 on the server, database version 5 on Android.

**Real-life gaps**
- Lent and borrowed: enter older loans with their real date (web and Android); Android also gets due date, currency and a date for repayments; repayments can be edited from the debt's history on both.
- Transactions have a time of day field on the web and Android (empty means now).
- Credit cards: a card paid beyond what is owed shows as in credit, adds to the available limit, can be entered as an opening credit (negative amount), and an overpaying transfer gets a heads-up.
- New transactions default to a spending account instead of a deposit or loan account; fixed a clipped amount on the dashboard and the narrow date field.
- Email: SMTP relays work with security `none`, with or without a login; EHLO uses the sender's domain; LOGIN authentication when PLAIN is not offered; `LEKHAMINT_SMTP_SKIP_VERIFY` for self-signed relays.
- `.env.example`, all compose files and the wiki now cover every setting (single sign-on, mail, Gotify, assistant, reminders); new wiki pages on debts and cards, sign-in and notifications.
- Exported backup files are named `lekhamint-backup-…kosh`; older `kosh-backup-…` files are still found.

**Security hardening (full audit)**
- Single sign-on accounts are now bound to the identity provider's subject: the first SSO sign-in links the account, and a different identity bearing the same email can no longer sign into it. Providers that never send `email_verified` are refused unless the admin sets `LEKHAMINT_OIDC_ALLOW_UNVERIFIED_EMAIL=true`. Schema v5 adds `users.sso_sub` (kept in dumps).
- The web app signs in with an HttpOnly `SameSite=Strict` cookie instead of a token in localStorage, so a script on the page can no longer steal the session; cookie-authenticated writes additionally require the `X-Kosh-Client` header (CSRF defence). The Android app keeps using the bearer token.
- The assistant's browser-mode API key is stored on your own server (per person) instead of the browser's localStorage; the owner's session can read it back. Schema v5 adds `user_prefs.ai_api_key`.
- Sessions have an absolute one-year cap on top of the sliding expiry; expired-absolute sessions are refused even if refreshed.
- `lekhamint demo` now listens on 127.0.0.1 only unless `--addr` says otherwise (the demo password is public).
- The assistant proxy is rate-limited per signed-in person (30/minute); login/register/SSO stay at 10/minute per IP, and `X-Forwarded-For` now trusts only the entry added by your own proxy (rightmost), so the limiter cannot be spoofed.
- Currency conversion saturates instead of wrapping when a user-entered rate overflows int64; the first-user "becomes admin" decision is race-free; the in-memory rate limiter cannot grow without bound under spoofed floods.

**Android**
- Plain `http://` sync servers are refused unless the address is local (loopback, RFC1918, `.local`, `.lan`); everything else must use https.
- A push conflict can no longer overwrite a local edit made while the push was in flight; the newer local row stays dirty and retries.
- Backup restore bounds the Argon2 cost a crafted `.kosh` manifest can demand; derived keys and passphrases are zeroed after use; the decrypted restore temp file is delete-on-exit; automatic and safety backups are encrypted with your saved passphrase when one is set.
- Release builds fail fast when no release keystore is configured (pass `-PallowDebugSigning=true` for a local test build) instead of silently producing a debug-signed "release".
- Budget alert thresholds compare in integers, so the exact-95% day is not missed to float rounding.

**Web**
- Reports use the local date for "today" (overdue loans, deposit valuation, debt flags) instead of UTC, matching the rest of the app.
- Assistant rebuilt as a proper chat: your question appears immediately, lookups stream as live chips while the model works, answers can be copied, failed questions can be retried, the conversation survives navigation (per book, per tab), the composer grows with input, and the whole pane scrolls on its own with the input pinned — on phones too.
- Design refresh across the app: elevated cards with hover states, animated navigation and tab bar, springier buttons, modals and toasts, richer empty states, markdown tables in assistant answers, and consistent focus rings.
- Toolchain updates: vite 6.4.5, vitest 5 (all known dev-dependency advisories cleared; runtime dependencies unchanged).

**Deployment**
- Docker Compose binds to 127.0.0.1 by default (`LEKHAMINT_BIND=0.0.0.0` restores LAN access); sign-ins no longer cross the network in plain HTTP unless you opt in. The installers generate a random `POSTGRES_PASSWORD` instead of shipping a placeholder, pin downloads to a release tag, refuse plain-`http://` mirrors, and pin the installed image version.
- The systemd unit gains `CapabilityBoundingSet=`, `RestrictSUIDSGID`, `RestrictNamespaces`, `ProtectProc`, `ProtectClock`, `ProtectKernelLogs`, `ProtectHostname` and a `@system-service` syscall filter. The Windows installer restricts `settings.env` to Administrators and SYSTEM.
- Every GitHub Actions step is pinned to a commit SHA, so a moved tag can no longer run different code in CI or the release pipeline.

## 1.3.0

**Web app redesign**
- New design system: richer palette with seven accent colours (Settings > Appearance), light, dark and black themes, slim themed scrollbars, a collapsible sidebar and a top bar with search, theme and privacy toggles.
- Dropdowns, date pickers, number fields, password fields, checkboxes, switches, sliders and file pickers are drawn by the app instead of the browser. Dates can be typed (`25/10/2026`, `today`) or picked; dropdowns are searchable and become bottom sheets on phones.
- Inputs only accept what makes sense (digits for amounts, one decimal point), and form errors appear under the field in plain words instead of browser pop-ups.
- Categories show their own icons; the sign-in page has a new layout; cards and tables no longer clip or scroll sideways on narrow screens.

**Server**
- Single sign-on with any OpenID Connect provider (Keycloak, Authentik, Authelia, Google...): `LEKHAMINT_OIDC_*`, `LEKHAMINT_PUBLIC_URL`. Optional password-less mode with `LEKHAMINT_OIDC_ONLY`.
- Email (SMTP) and Gotify notifications: security alerts, bills due and budget limits, with per-person choices in Settings. See `docs/13-sso-and-notifications.md`.
- Schema v4 adds `user_prefs` and `notify_log`.

## 1.2.0

**Fixes**
- Net worth now counts money others owe you and money you owe people (web, Android and a new server `reports/position`). Lending to a friend no longer lowers net worth.
- Saves can no longer create duplicates on double click or a retried request: forms keep one record ID and create with `create_if_missing`; Android does the same.
- Repayments above what is owed are refused with an explanation; overpaid debts show clearly and count on the other side. Web and Android now agree.
- Reminders no longer show amounts on the lock screen.
- The web app no longer signs you out when you type a wrong current password in *Change password*.
- A wrong AI key on the server's assistant proxy is reported as a server problem (502) instead of a 401 that looked like an expired session.
- Android: release lint is clean (Quick Settings tile on older Android, app lock activity lookup, CSV byte-order mark); a version tag like `v1.3.0-rc1` no longer breaks the build.

**New**
- Loans & EMIs: new or running loans, EMI split into principal and interest, schedules, prepayment (keep EMI or keep tenure), rate changes, foreclosure, reminders.
- Fixed and recurring deposits: maturity estimates, payouts, RD instalments, maturity calendar, recording maturity or early closure.
- Lent & borrowed by person, with statements, oldest-first repayment, interest, write-off confirmation.
- Calculators: EMI, affordability, FD/RD, bill splitter (saves your share and what others owe), household & budget planner, savings goal, emergency fund, debt payoff, subscriptions, net worth over time.
- Statements & export: income & spending, account statement, transactions, loans, deposits, person, net worth, budgets, as PDF (print view on web, native on Android) and Excel; full-data Excel export.
- Dashboard: everything due (bills, EMIs, RD, maturities, debts), "safe to spend", shortfall warnings. Transaction form warns before overdrawing, passing a card limit or a likely duplicate.
- Assistant with any OpenAI-compatible model (cloud, home server, on-phone), read-only tools, consent prompt and optional server proxy. See docs/11-assistant.md.
- Delete account: *More → Sync* on Android, *Settings → Your account* on the web, `DELETE /api/v1/auth/me` on the server. Deletes the account and the books only you own.
- Google Play readiness: release builds also produce an App Bundle (`LekhaMint-play.aab`), a privacy policy ([PRIVACY.md](PRIVACY.md)) linked in the app, a *Report* action on assistant answers, and a publishing guide (docs/12-google-play.md).
- Schema 3: `loans`, `deposits`, and `transactions.loan_id / deposit_id / part`; account type `deposit`.

## 1.1.0

LekhaMint is now open source, with a live demo, one-line installers and Docker images.

**Try it and install it**
- Online demo at https://anand34577.github.io/lekhamint/ with a year of sample data. It runs entirely in your browser; sign in with `demo@example.com` / `demo1234`.
- One-line server install for Linux and macOS (`curl … | sh`) and Windows (`irm … | iex`). The installers verify the download checksum, set up a service that starts on boot, and upgrade in place when run again. `--docker` runs it in Docker instead.
- Docker images for amd64, arm64 and Raspberry Pi at `ghcr.io/anand34577/lekhamint`, with Compose files for SQLite, PostgreSQL and automatic HTTPS.
- `lekhamint demo` starts a throwaway server with sample data, to try the apps without entering anything.
- A wiki with guides for installation, Docker, HTTPS, configuration, the apps, backups, upgrades and troubleshooting.

**Improvements**
- The server stops with a clear message naming every bad setting, instead of quietly using a default.
- Optional log file with rotation (`LEKHAMINT_LOG_FILE`), used by the Windows and macOS installers.
- Server errors in the web app show a reference that matches the server log; a crash on one page no longer blanks the app.
- `lekhamint user add` and `user passwd` hide the password as you type.

**Fixes**
- Split transactions showed as "Uncategorised" in the Home page's recent activity.
- Money lent, borrowed or repaid was counted as income or spending in the Transactions totals (Reports were already right).
- Exported books were named `kosh-…json`.

**Upgrading from 1.0.0**
- The server is now called `lekhamint` and reads `LEKHAMINT_*` settings instead of `KOSH_*`. Its default database file is `lekhamint.db`: rename `kosh.db` (with its `-wal` and `-shm` files) or point `LEKHAMINT_DB_DSN` at it.
- Release files no longer carry the version in their name (`lekhamint-linux-amd64`), so "latest" download links always work. The Android APK is `LekhaMint.apk`.

## 1.0.0

First public release of LekhaMint: a local-first money manager with an Android app, a web app,
and an optional self-hosted server for sync and backups.

**Everywhere**
- Expenses, income and transfers (including between currencies), with exact balances.
- Budgets with rollover, per-category and per-account limits, and a daily allowance.
- Splits across categories, tags, and auto-categorise rules that also clean up payee names.
- Bills and recurring items that remind you or record themselves, without duplicates across devices.
- Lent & borrowed: lending takes money from an account, repayments bring it back, and you always
  see what is still owed. None of it counts as spending.
- Reports: category breakdown, 12-month trend, needs/wants/savings, top payees and tags.

**Web app**
- Quick add in plain words: `450 swiggy dinner yesterday #work @hdfc`.
- Command palette (Ctrl/⌘ K) and keyboard shortcuts (press ?).
- Home screen insights, a spending-pace chart and a list of transactions that need a category.
- Import bank statements (CSV); importing the same file again adds nothing.
- Share a book with family as editor or viewer; see and sign out other devices.
- Exchange rates for totals across currencies; light, dark and true-black themes.
- Installs like an app and opens offline.

**Android**
- Works fully offline with no account; encrypted automatic backups.
- Home-screen widget, quick settings tile and app shortcuts for instant entry.
- App lock, hide amounts, Material You and AMOLED themes.

**Server**
- One small binary (Linux, macOS, Windows, FreeBSD) with SQLite or PostgreSQL that also serves the web app.
