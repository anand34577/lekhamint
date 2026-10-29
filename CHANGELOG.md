# Changelog

## 1.10.0

**Security**
- Single sign-on now asks for your two-step code when your account has two-step sign-in on. The identity provider's sign-in replaces the password, not the code. The sign-in link from the provider is now usable once, for five minutes, and no session exists until the code is checked.
- Administrators choose which assistant models the server's API key pays for. With `LEKHAMINT_AI_MODEL` set, a request for any model not in the new `LEKHAMINT_AI_MODELS` list uses the default model instead.
- Each book's attachments are limited to `LEKHAMINT_MAX_LEDGER_STORAGE_MB` (5 GB by default; 0 for no limit), so one account cannot fill the server's disk. A phone keeps a receipt the server refused and tries again later; the rest of its sync carries on.

**Sync**
- Web: an edit saved while offline and sent later no longer overwrites changes someone else made to the same fields after you made yours. Fields nobody touched still take your edit.

**Fixes**
- Restoring the server from an older backup no longer leaves phones out of step. A phone that had already synced past the backup used to skip new changes and never send back what the server lost; it now sends everything it has again and downloads the book afresh. Needs this server and Android app version together.
- Android: a `lekhamint://pair` link opened from outside the app (a camera app, a message, a web page) now fills in the server address and code for you to check and tap Connect, instead of connecting at once. Anyone can make such a link, so it could otherwise connect your phone to a server you don't know. Scanning the code from inside the app still connects straight away.
- Wrong two-step codes typed when turning two-step sign-in off or making new recovery codes now count towards the same 15-minute lock as wrong codes at sign-in.
- Asking the server to create a book with an ID that belongs to someone else's book now answers "not found", like every other request about a book you can't see, instead of "no permission", which revealed that it exists.
- Docker: `LEKHAMINT_MAX_ATTACHMENT_MB`, `LEKHAMINT_HISTORY_DAYS`, `LEKHAMINT_TRASH_DAYS` and `LEKHAMINT_FX_URL` set in `.env` are now passed to the container by all three compose files; before, they were silently ignored.
- The transaction list's tag filter matches only that tag; a value with `%` or `_` used to match every tagged transaction.

## 1.9.0

**Time of day on every money entry**
- Lent & borrowed, loans, deposits and split bills now record the time as well as the date, on the web and on Android. A Time field sits next to the date, filled in with the current time; change it to when the money actually moved. A time later today is refused, since these record what already happened.
  - Lent & borrowed: lending or borrowing, and every repayment (also when repaying a person across several debts).
  - Loans: EMI payments, prepayments and foreclosure.
  - Deposits: maturity or early closure, interest credited, and RD instalments.
  - Split a bill (web): the shares lent to others are also dated when the bill was paid, not when it was saved.
- Transaction lists show each entry's time on both apps, and Lent & borrowed shows it in a debt's history and in each person's statement (also in the copied statement).
- Importing a bank statement keeps the time when the file has one, from the date cell ("24/10/2026 14:35") or a separate Time column, and from OFX files. Statements with only dates are saved as before.
- CSV and Excel exports have a Time column.
- Older entries saved with only a date show no time rather than a made-up "12:00 PM".

**Web: hover and dark mode**
- Selected options keep their look on hover: segmented switches (List / Calendar, I lent / I borrowed), date chips, choice cards, the selected month or year in the date picker, and the spending calendar's coloured days used to lose their highlight, which in dark mode read as the selection going dark.
- Browsers that force a dark mode on websites (Chrome's "dark theme for websites", Edge, Samsung Internet) no longer recolour LekhaMint; it has its own light and dark themes, and the forced colours turned hovered text dark on dark.
- The hover highlight is clearly visible in dark and black themes.
- A date picker near the bottom of a dialog opens upwards instead of being cut off.
- The loading bar under the top bar no longer keeps animating while hidden, which used power all the time.

**Fixes**
- Transactions calendar: "today" was the UTC day, so in India it marked the previous day until 5:30 am; weeks now start on your region's first day, like the date picker.
- Android: Activity no longer flashes "$0" in the wrong currency while it loads.

## 1.8.0

**Sync**
- The phone now syncs live while the app is open: it syncs when you open it, sends your edits within a second, and changes made on the web or another phone appear within seconds. Before, it only synced every 15 minutes in the background, so web edits seemed not to arrive. Pull down on Home or Activity to sync by hand.
- Server: `GET …/sync/pull` takes `wait=N` (up to 25 s) and answers as soon as the book changes. Older apps are unaffected; the phone falls back to polling an older server every 20 s.
- Home says "Not synced · saved on this phone" when sync fails, instead of only a small cloud icon.
- Pull down to sync on Home, Activity, Accounts, Budgets, Reports, Insights, Goals, Year & tax, Loans, Deposits, Bills & recurring, Lent & borrowed and the weekly check-in. It shows only when a server is connected, the spinner stays until the sync is done, and a short message says "Up to date", how many changes came in, or why it couldn't sync.

**Connect a phone without typing a password**
- Web: Settings → Signed-in devices → Connect a phone shows a QR code and a 10-character code, valid for 5 minutes and once. It says when the phone has connected.
- Android: Sync (and "Sign in to server" when setting up) has Scan code and Enter code. Scanning takes a photo with the camera app and reads the code on the phone (ZXing), so the app still asks for no camera permission; pointing the phone's own camera at the code opens LekhaMint directly too.
- Server: `POST /auth/pair`, `GET /auth/pair/{code}` and `POST /auth/pair/claim` (docs/07). A "new device connected" security notice goes out like for a sign-in.

**Remove a device from the account**
- Web: Settings → Signed-in devices shows a phone or browser icon for each, and "Sign out" is now **Remove**, with a confirmation that says what happens (it stops syncing at once; its data stays on it). "Remove all other devices" helps after losing a phone.
- Android: Sync lists every device on the account ("this phone" first) with **Remove**, and "Sign out" is now **Disconnect this phone**, with a confirmation that mentions changes not uploaded yet.
- A phone removed on the web or from another device now notices within seconds: sync stops instead of retrying forever, Home says "Removed from the server · Connect again", and Sync explains what happened. Everything recorded on the phone stays; connecting again (server and email filled in) carries on with the same book and uploads anything not yet sent.

**Android: easier to use**
- More has a search box: find a page or setting by name or everyday words ("dark", "password", "export", "emi"), including tools hidden in simple mode.
- Activity offers "Categorise" on entries without a category: pick one in place, with Undo.
- When "Safe to spend" is short, it offers the next step: Bills due, Review budget, or Move money in.
- Sign in: the server box says what to type, the password has a show button, and the keyboard moves between fields and signs in from Done.
- The welcome screen shows the LekhaMint logo, and its header stays readable in dark mode (it was white text on light mint).

**Android: installing**
- Two editions. The downloadable APK no longer includes reading bank and payment alerts: Google Play Protect blocks sideloaded apps that ask for notification access in many countries (India, Brazil, Singapore, Thailand and others), with no "install anyway" option. Share an SMS or alert to LekhaMint instead; the Google Play edition keeps automatic reading.

**Deposits**
- Terms in years, months and days, with quick picks (7 days, 30 days, 91 days, 6 months, 1–5 years), so 7-day, 46-day and "1 year 4 months 15 days" deposits can be entered. The form shows the maturity date and the number of days; the deposit screen shows the term and how long is left.
- Renewing a deposit keeps its exact term. It used to round to whole months, so a 7-day deposit renewed for a month.

**Android: dark theme and presentation**
- Buttons on the coloured cards (receipt import, found payments) were light text on a light button in dark mode; they now use the card's own colour for the label.
- The Expense / Income / Transfer switch and the busiest days in the spending calendar had white text on light green or orange in dark mode; text colour now follows the fill.
- Fixed accent colours (category colours, chart series, Accounts, net-worth bars) are lifted in dark mode so they don't sink into the background.
- Dates follow the phone's region ("Mon, 21 Oct" or "Mon, Oct 21"), and times follow its 12- or 24-hour setting.
- Amounts are never cut short with "…": in tight spots (the Income / Spent / Net tiles in Activity with lakh-sized figures) they get smaller to fit.
- "Safe to spend" on Home no longer shows a bare red "0 a day": it says how much is short and why ("Bills due by Oct 31 (₹4,000) need more than the ₹1,345 left in your budget").
- Closing a new or edited entry with ✕ or Back after typing something asks "Discard this entry?" instead of losing it.
- Reports: "Which days cost the most" uses a typical day per weekday, so one rent or EMI day no longer makes its weekday look like the expensive one.

**Web**
- The calendar button on date fields sometimes did nothing: pressing it moved it, so the click was lost. Clicking anywhere on a date field now opens the calendar, and on phones it opens without the keyboard.
- Deposits take a term in years, months and days with the same quick picks.
- Live updates: while a tab is open, changes saved on the phone or in another browser show up within seconds, with no reload. Hidden tabs stop waiting and catch up when you come back.
- Forms ask "Close without saving?" when you press Escape, click outside or press ✕ after typing something, instead of losing it. Cancel still closes at once.
- Dates follow your region: the date field shows and reads "Oct 25, 2026" and 10/25/2026 where the month comes first, and the calendar starts the week on your region's first day. A typed date that only works one way round (25/10) is read that way anywhere. Month labels on loan, deposit, net-worth and tool charts no longer show the previous month in the Americas.
- Amounts can be typed the way your region writes them: 1.234,56 · 1 234,56 · 1'234.50.
- Two categories with the same name in the same group are no longer allowed (they could not be told apart); a failed category delete shows why.
- Home: "This month" shows how much you saved and what share of income that is; "Where your money went" moved beside Spending pace so the two columns end evenly.
- Light theme: income, spending, warning and button colours are a shade deeper so text on them is easier to read. Dark theme: Delete buttons and done ticks use dark text on their bright fill instead of white.

## 1.7.0

**Android: less typing, fewer taps**
- Found payments can be saved straight from the notification: Save, Edit or Ignore, then Undo or Change for a few minutes after. A payment that is already in the book says so and offers "Add anyway".
- Type or say it in one line: "450 lunch yesterday", "+85000 salary @hdfc", "1.2k groceries #trip". The amount, date, account, tags and category are filled in as you type (everyday words like lunch, cab or petrol find their category), and the mic works offline where the phone supports it. It is on Home and as a long-press shortcut on the app icon.
- Scan a receipt: take or pick a photo and the amount, date and shop fill themselves in, with Undo. Reading happens on the phone with open models (PaddleOCR, run by ncnn): no Google services and no network. It reads Indian receipts (₹, Rs., GST lines, lakh grouping) and most others (decimal commas, SUMA, TOTALE…), and photos come out the right way up.
- Import bank statements on the phone: PDF (including password-protected ones), Excel (.xls and .xlsx), CSV, OFX/QFX and QIF. Columns and the date style are guessed, UPI/NEFT/card narrations become readable payees, categories come from your rules and past entries, and anything already in the book is left out. Open or share a statement file to LekhaMint to start.
- A weekly check-in on Sunday evening: what you spent against last week, entries without a category (one tap each, or "always file it this way"), found payments, budgets to watch, the week's bills, and an account whose balance hasn't been checked lately. It takes about 30 seconds; it can be turned off in More.
- "Does this match your bank?": Home and the check-in ask about an account now and then; type the bank's balance and one tap adds the difference as an adjustment.
- Bill reminders you can act on: Mark paid or Skip this time from the notification; card bills, loan EMIs and deposits open the right screen. Reminders arrive in the daytime only.
- Share a book with family from the phone: add people by email as editors or viewers, remove them, or send an invite.
- Simple mode for new books: More shows the everyday screens, and each tool (goals, loans, tags, rules…) appears once you turn it on in More → Features or once the book uses it.
- Open-source notices in More → About list everything the app ships with and their licences.

**Web**
- Quick add knows everyday words too ("lunch", "uber", "petrol"), the same as the phone.
- Imports recognise dates written 2026-10-24 as year first.

**Fixes**
- Android: photos taken in portrait were saved lying on their side in receipts.

**Android: polish**
- Inter is now the app's typeface (bundled, about 180 KB, trimmed to Latin, currency and maths signs; other scripts fall back to the system font).
- Cards float on a soft shadow instead of a grey outline; the bottom bar is lifted and rounded; screens slide in when you open them and fade between tabs.
- Lists show spending in the normal text colour and income in green, so a month of purchases reads calmly; the sign still says which way money went. Transfers show without a sign.
- Home: the main card says "Total balance" (it read "Available to spend", which contradicted "Safe to spend" below it), shows placeholders instead of ₹0 while the month is added up, and the quick actions are Income, Transfer, Accounts and Reports (spending has the big + button).
- Adding a transaction: a more compact keypad leaves the payee and note fields visible.
- Sync says "Not syncing right now" with the error when it fails, instead of "Connected".
- Dates read day first everywhere ("1 Oct 2025"); counts read "1 day", "1 change" instead of "1 days", "1 changes"; the snackbar's Undo uses the theme colour.
- Local builds show the real version (from this changelog) instead of 1.0.0.

**Web: polish**
- Phones: transaction filters fold behind a Filters button so the list starts on the first screen; the tab bar's last tab is More, which opens every page; the Month / All time switch stays on one line.
- Home: the set-up checklist shrinks to one line with the next step once most of it is done; the main card says "Total balance".
- Lists show spending in the text colour and income in green; transfers show without a sign.
- The add form puts the cursor in Amount (every dialog now focuses the field it asks for, not the Close button), and Today / Yesterday sit under the date.
- Inter is bundled with the app (69 KB) so every browser shows the same type; nothing is fetched from a font service.

**Search**
- Search on Android and the web also finds a category by name and an exact amount: type "486" from a bank statement or "groceries".

**Fixes**
- Web: the transactions table's column header stuck 60px down inside the table, covering the first rows.
- Android: if saving a transaction failed (for example a full disk or an unreadable receipt), Save stopped working with no message; it now says what went wrong. Saves in other forms (loans, deposits, people, budgets…) show a message instead of closing the app. A failure creating the first book no longer leaves "Get started" stuck.
- Android: if a full download of the book (needed after a long time offline) was interrupted, items deleted on other devices could stay on the phone; the download now restarts from the beginning until it completes.

**Server**
- Deleting your account no longer deletes a book other people still use: a book you alone own passes to its longest-standing member (editors first), and only books nobody else uses are deleted.
- The session cookie is marked Secure behind a trusted HTTPS reverse proxy (`X-Forwarded-Proto`), not only on direct TLS.
- Changing the password is rate limited per person; deleting the account also clears the browser's session cookie; cross-origin requests may use `PUT` (settings).
- The server logs a one-time warning when requests come through a reverse proxy that is not trusted, because everyone then shares one sign-in limit.

## 1.6.0

**Android: a new look**
- Six colour themes — Mint (the default), Ocean, Indigo, Graphite, Plum and Sunset — each in light, dark and pure-black, picked from live swatches under More → Theme. Wallpaper colours (Material You) are still there as an option instead of the default.
- Home is rebuilt: a greeting, one clear "available to spend" card with money in and out this month and how much of the income is spent, quick actions (expense, income, transfer, reports), what's coming up, the spending pace chart with your budget line and where the month is heading, budgets with a plain status ("On track", "Ahead of pace", "Over"), a tappable spending donut and a net-worth bar of what you own against what you owe.
- Reports: a "kept" ring for the share of income saved, a tappable category donut, a spending calendar that shades each day by what it cost, which weekdays cost the most, income against spending on a labelled scale, and ranked bars for payees, tags and income.
- Adding a transaction: coloured Expense/Income/Transfer tabs, categories as one row of icons (so the payee and note fields are visible without scrolling), a larger amount and a calmer keypad; Save stays dimmed until there is an amount.
- Budgets open with a ring for the whole month and counts of budgets on track, to watch and over; each budget says its state in words. Activity shows a day-by-day spending strip. Accounts are grouped (cash & bank, cards & loans, savings & investments) with subtotals, and each account shows its 90-day balance trend.
- Calculators work with sliders and explain themselves with pictures: where an EMI goes (loan against interest, "for every ₹100 you pay back ₹208"), how the loan shrinks year by year, how a deposit grows, each person's share of a bill and who pays whom, and where a month's income would go with a new loan.
- More is grouped into cards with coloured icons; onboarding has a proper welcome. Whole amounts show without ".00" on screen (statements and exports keep every digit).

**Android: less typing, more foresight**
- Found payments: turn on *More → Find payments automatically* and bank SMS, card alerts and UPI or wallet app notifications are read on the phone and listed for review, with a notification you can tap. *Add* opens the form already filled in (amount, payee, time, the account whose last digits match, categorised by your rules). OTPs, offers, bill reminders and chat apps are ignored; nothing is uploaded, and nothing is saved until you tap *Add*.
- Insights (*More → Insights & forecast*, and a card on Home): **safe to spend per day** until month end or payday, measured against your overall monthly budget, else this month's income, else your cash, with the sum laid out step by step and your usual daily spending beside it; what stands out against the same days last month; a cash forecast for 30, 60 or 90 days with a likely line and a range you can scrub through; and subscriptions and repeat payments found in your spending, with their yearly cost, price rises and a one-tap reminder.
- Bills & recurring opens with what comes in and goes out each month (and the share of income bills take), a 30-day timeline of what's due, and groups for bills, income, transfers and paused items.
- Goals: a ring for each goal and for all of them together, and a bar that marks where the plan says you should be today; monthly amounts are rounded to whole units.
- Your theme, colour and dark-mode choice follow you to your other phones through your server.
- Payee suggestions no longer repeat a name in different capitals ("Swiggy", "swiggy").

**Assistant**
- Android uses the assistant your server's admin has set up (`LEKHAMINT_AI_BASE_URL`) as soon as the phone is connected — no provider, key or model to enter on the phone. Your own provider remains an option in the assistant's settings. The web app now does the same instead of asking each person to pick it first.

**Web: settings follow you**
- Theme, accent, the book you have open, hidden amounts, the sidebar, the list/calendar view, assistant options, the financial-year start and dismissed cards are saved to your account on the server, so every browser shows the same. Settings an older version kept in a browser move to the server the first time you sign in there. (New API: `GET/PUT/DELETE /api/v1/settings`.)

**Web**
- The app loads faster: pages open as you visit them, and the main file is about half the size (≈310 KB, with React in a separate file that stays cached between updates).

**Fixes**
- Needs, wants and savings (web and Android) now measure against income, as the 50/30/20 guide intends, with "Saved" being what was not spent; before, it showed shares of spending, so someone who kept 39% of their income could see "0% savings".
- Android: long-pressing Save showed "Saved. Add the next one." even when nothing was saved (for example with no amount); the time picker now follows the phone's 12/24-hour setting.
- Android release builds in CI read the key password from `ANDROID_KEY_PASSWORD` (it used the keystore password, so signing failed whenever the two differed).
- Web sign-in no longer offers "Create an account" on a server where sign-up is closed.
- Full-server dumps (format v3) now include each person's notification choices and personal assistant key, and the new settings; restoring an older dump still works.

**Security**
- Wrong two-step codes at sign-in are now limited per account as well as per IP address: after five, that account's code check pauses for 15 minutes.

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
