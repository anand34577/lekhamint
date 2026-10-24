# 01 – Product & UX

## Who it is for

| Persona | Needs | What LekhaMint does |
|---|---|---|
| **Daily tracker** (Priya, 27, salaried) | Log a coffee in 3 seconds. See "how much can I still spend this month". | Quick-add, home budget ring, home-screen widget |
| **Household manager** (Rahul & Anita) | Shared expenses, two phones, no arguments about numbers | Shared ledger, sync, per-member view |
| **Multi-currency traveller** | Card spends in EUR, home currency INR | Foreign amount on each transaction, per-currency reports |
| **Privacy-first user** | No cloud, no account, no ads | Fully offline. Server optional and self-hosted. Encrypted backups. |
| **Power user / migrator** | Imports years of bank CSVs, wants detailed reports | CSV import with mapping, splits, tags, filters, export |

## UX principles

1. **Entry speed is the product.** If logging is slow, people stop logging, and every report becomes useless.
2. **No sign-up wall.** The first launch asks for one thing: your currency. A Cash account is created for you. You are in.
3. **Undo instead of "Are you sure?".** Deletes go to Trash and show an Undo snackbar. Only irreversible actions (deleting a ledger, a replace-restore) ask for confirmation, and those make you type the name.
4. **Smart defaults learn from you.** The last-used account. Category suggestions from the payee ("Swiggy" → Food delivery) and from time and place patterns.
5. **Show meaning, not just numbers.** "₹4,200 left for 9 days, about ₹466 a day" beats "Budget: 78%".
6. **One concept per screen.** Progressive disclosure: advanced fields (tags, split, foreign amount, status) sit behind "More".
7. **Works one-handed.** Primary actions sit in the bottom third of the screen. The FAB and the numpad are under your thumb.

## Information architecture (Android & web)

```
Bottom navigation
├── Home          balance card · month spend vs budget ring · upcoming bills · recent activity (grouped by day)
├── Transactions  search · filter chips (account, category, tag, type, date) · list / calendar toggle
├── [ + ]         Quick add (FAB; long-press = transfer)
├── Budgets       budget cards with progress, pace line, rollover
└── More
    ├── Accounts        balances, net worth, reconcile
    ├── Reports         spending by category (donut), trend (bars), income vs expense, 50/30/20, cash flow
    ├── Categories / Tags
    ├── Recurring       subscriptions & bills, upcoming calendar
    ├── Debts           lent / borrowed, settle up
    ├── Trash
    └── Settings        appearance · security (app lock) · sync · backup & restore · import/export · currencies
```

## Quick add: the most important screen

```
┌────────────────────────────────┐
│  Expense   Income   Transfer   │  ← segmented; swipe to switch
│                                │
│          ₹ 1,250               │  ← big amount, numpad below (calculator: 450+800)
│                                │
│  🍔 Dining out  ▾   💳 Amex ▾   │  ← suggested category (top 6 chips) · last account
│  Today ▾   Payee / note…        │
│  + More (tags, split, currency) │
│ ┌──┬──┬──┐                     │
│ │ 7│ 8│ 9│  ⌫                  │
│ │ 4│ 5│ 6│  +−                 │
│ │ 1│ 2│ 3│                     │
│ │ .│ 0│00│  [ Save ✓ ]         │
│ └──┴──┴──┘                     │
└────────────────────────────────┘
```

- The numpad opens straight away. No keyboard pops up.
- **Save** keeps the screen open on long-press, for logging several receipts in a row.
- Entry points: the FAB, a **Quick Settings tile**, a **home-screen widget**, an **app shortcut**, and the share sheet (share an SMS or receipt image to pre-fill the amount).

## Visual design

- **Material 3 with dynamic colour** (Material You) on Android 12+, and a refined default palette elsewhere. Light, dark and **true black (AMOLED)** themes.
- **Typography:** large tabular figures for amounts, so columns line up. Negative amounts are never shown in red alone. They always have a sign and an icon as well (colour-blind safe).
- **Category colours and icons** carry through every chart, list row and chip.
- **Motion:** shared-element transitions from a list row to its detail, and spring animations on the budget rings. All motion respects "Remove animations".
- **Number formatting** follows the locale: ₹1,23,456.78 (Indian grouping), $123,456.78, 123.456,78 €.

## Accessibility (non-negotiable)

- TalkBack and VoiceOver labels on every control, and amounts read out as money ("one thousand two hundred fifty rupees").
- Touch targets ≥ 48 dp. Contrast ≥ 4.5:1. Layouts work at 200% font scale.
- Full keyboard and D-pad navigation on web and Android TV or ChromeOS.

## Security & privacy UX

- Optional **app lock**: biometrics or PIN, with a lock-after timeout. Recent-apps screenshots are blurred.
- **Hide amounts** toggle (eye icon) for using the app in public.
- No analytics or trackers by default. Crash reports are opt-in.

## Onboarding (under 30 seconds)

1. Welcome → pick a currency (detected from the locale) → done. A Cash account and the default categories are created.
2. Optional cards on Home: "Add your bank account", "Set a monthly budget", "Turn on backups", "Connect a sync server".
