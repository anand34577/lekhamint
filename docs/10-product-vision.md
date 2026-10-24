# 10 – Product vision: from expense tracker to personal finance manager

LekhaMint starts as an expense manager, but the goal is a single place where a person can
**understand the past, control the present and plan the future** of their money:

| Layer | Question it answers | LekhaMint features |
|---|---|---|
| 1. Understand the past | Where did my money go? | Transactions, categories, tags, balances, reports, search, export |
| 2. Control the present | Am I on track this month? | Budgets with pace, upcoming bills, recurring commitments, alerts, cash-flow summary |
| 3. Plan the future | Am I better off than last year? | Goals, investments, debt payoff, net-worth history, forecasts |

The guiding principle: **the user should spend less time managing financial data and get
more value from understanding it.** Manual tracking must stay first-class; bank connections
and automation make it better, but are never required.

## 1. What already holds up (design decisions that make the vision possible)

| Requirement from the vision | How LekhaMint handles it today |
|---|---|
| Transfers, card bill payments, investing and loan principal are **not** expenses | `type = transfer` with a source and destination account. Reports exclude transfers. Paying a card bill moves money from bank to card and never counts twice ([02](02-expense-domain.md)). |
| Refunds reduce the original category | Income in an expense category is a refund and nets against that category in every report (server, Android and web share the rule). |
| Available cash, investments, debt and net worth are different numbers | Account types decide where a balance belongs. The Home screen and web dashboard show all four separately. |
| Exact money | Integer minor units everywhere, per-currency totals, no floats. |
| Offline entry, safe multi-device sync | Local-first database on the phone, HLC last-writer-wins sync, idempotent pushes, deterministic IDs for recurring items ([05](05-sync-protocol.md)). |
| Explain why a report changed | Every record carries its HLC and change-feed `seq`; deletes are tombstones; nothing is overwritten silently. Restores and imports are merges by version. |
| Shared household finances with private accounts | A **ledger** (book) is the unit of sharing with owner/editor/viewer roles. A couple keeps personal books private and shares a "Household" book. |
| Business / freelance separation | Another ledger ("Freelance") with its own categories and reports. |
| Multi-currency travel | Accounts have their own currency; foreign spends record `original_amount` + `original_currency` next to the settled amount. |
| Data export, backup, recovery | `.kosh` backups (optionally encrypted), JSON and CSV export, server snapshots and portable dumps ([06](06-backup-restore.md)). |
| Tags ("what was it related to?") | `tags` entity and `tag_ids` on transactions exist in the schema and sync; tag UI is Phase 2. |

### Why not a full double-entry ledger (yet)

Double-entry bookkeeping (every movement is a debit and a credit between accounts) makes
complex flows impossible to get wrong. LekhaMint already stores the essence of it: every transaction
names a **source account**, and transfers also name a **destination account** with its own
amount (so cross-currency transfers are exact). Income and expense flow between an account and
an implicit "outside world" category. This is equivalent to double entry for personal finance
and keeps records small, easy to sync and easy to explain.

When investments and loans arrive (Phase 3) we add the missing pieces without breaking clients:

- **Investment activity** records (buy, sell, dividend, fee) reference an investment account and a
  cash account, so buying a fund is a transfer plus a holding change, not spending.
- **Loan repayments** split into principal (a transfer that reduces the liability) and interest
  (an expense), using the existing split mechanism.
- If a future feature truly needs postings, a derived `postings` view (two rows per transaction)
  can be generated from existing data, because no information is missing.

## 2. Phased roadmap

Each phase ships something useful on its own. Correctness and data safety come before features.

### Phase 1 — Essential money manager (done)

Fast manual entry, accounts and transfers, categories, budgets, recurring bills, reports,
search, reconciliation, Trash, backup and restore, import/export, authentication, sync between
Android and web, app lock. See [09](09-roadmap.md) for the full list.

### Phase 2 — Less typing, more insight

| Feature | Notes |
|---|---|
| Tags UI and tag reports | Schema and sync already exist |
| **Rules engine** for categorisation | User rules first ("payee contains IRCTC → Travel, #train"), then the user's own history (already used: the last category for a payee), then an optional on-device model. Rules are a synced entity, so they apply on every device. |
| CSV / bank-statement import | Column mapping, preview, **duplicate detection** (same date ± 2 days, amount and account), idempotent re-import using a hash of the source row |
| Receipt photos and attachments | Content-addressed blobs synced separately from records |
| Subscription and recurring-payment **detection** | Find repeating payee + amount patterns; suggest a recurring rule; flag price changes |
| Insights | "Dining is 18% above your 3-month average." Every insight shows how it was calculated and can be dismissed; no false alarms |
| Splits UI | Split one bill across categories; the schema already supports it |
| Home-screen widget | Quick add and month-to-date spending |
| Web: bulk editing, multi-currency conversion in reports using `exchange_rates` | Bulk recategorise and delete already exist |

### Phase 3 — Goals, investments and net worth

| Feature | Data model addition |
|---|---|
| Savings goals (emergency fund, trip, house) with target and date | `goals` (target, deadline, linked accounts); progress = linked balances or tagged contributions |
| Investment holdings | `holdings` (instrument, quantity, cost basis), `investment_activity` (buy, sell, dividend, interest, fee, contribution, withdrawal). Track **contributions**, **current value**, **performance** (with the method stated) and **withdrawals** separately |
| Asset valuations | `valuations` (account or holding, date, value, source, `estimated` flag) so estimates are never shown as confirmed balances |
| Net-worth history | Monthly snapshots derived from balances and valuations: "Am I better off than six months ago?" |
| Debt payoff planner | Avalanche vs snowball, interest saved, payoff date |
| Cash-flow forecast | Balance projection from recurring items and spending history, shown with a range, not a single false-precision line |
| Country-specific asset types | India: EPF, PPF, NPS, FD, RD, SGB, mutual funds; configurable per region |

### Phase 4 — Connections and intelligence

| Feature | Approach |
|---|---|
| **Account Aggregator** (India) | Integrate as a Financial Information User through a licensed AA. Consent-based, revocable, no bank passwords. Imported data goes through the same rules engine and duplicate detection as CSV. Disconnecting keeps all data and manual tracking working |
| Statement forwarding | A per-user inbox address or a local Gmail filter; PDF statements parsed into the import pipeline |
| Market prices | Optional price feeds for listed holdings, always showing source and last-updated time |
| Conversational questions | "How much did my Himachal trip cost?" answered by a model that queries the user's own data through the existing report and search APIs. Prefer on-device models; a server-side model only on the user's own server, never a third party by default |
| Line-item receipt OCR and bill splitting | On-device vision model; the user confirms before anything is saved |
| What-if simulator | Scenario projections (an EMI now vs later) based on the user's real cash-flow volatility, labelled as estimates |
| Personal API keys and webhooks | Scoped tokens for automation (n8n, Home Assistant, bots) |
| Household workspaces with per-account sharing | Extend ledger membership to share selected accounts and budgets |

Things LekhaMint will **not** do: move money on the user's behalf without explicit confirmation,
give personalised investment advice, or sell or mine transaction data.

## 3. Privacy and security as product features

- Data stays on the user's device and their own server. No analytics by default.
- Encrypted backups (Argon2id + AES-256-GCM), Keystore-protected secrets, app lock, hidden amounts.
- Strict per-ledger isolation in every query; non-members get 404.
- **End-to-end encrypted ledgers** (roadmap): the server stores ciphertext only. This means
  reports run on the client, which the local-first design already supports.
- India's DPDP Act: consent, purpose limitation, export and deletion are built in because the
  user owns the server and the data.

## 4. Measuring success

| Measure | What it tells us | Target |
|---|---|---|
| Time to record an expense | Daily tracking feels effortless | < 5 s median |
| Transactions corrected after entry | Suggestions and imports are trustworthy | Falling month over month |
| Reconciliation differences | Balances can be trusted | Rare and small |
| Months with at least one budget in use | Users plan, not just record | Rising |
| Users still logging after 6 and 12 months | Long-term value | The real goal |
