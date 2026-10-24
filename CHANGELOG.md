# Changelog

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
- Quick add in plain words: "450 swiggy dinner yesterday #work @hdfc".
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
