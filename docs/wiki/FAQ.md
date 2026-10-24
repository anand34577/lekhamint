# FAQ

**Is it free?** Yes, and open source under the MIT licence. There is no paid tier, no ads and no tracking.

**Does my data go anywhere?** Only to the server you run. The apps talk to nothing else: no analytics, no crash reporting, no cloud.

**Do I need a server?** No. The Android app works on its own. The server adds sync, the web app and sharing.

**Will it connect to my bank?** Not directly; bank APIs are closed or require paid aggregators that would see your data. Import your bank's CSV statement instead (web app → *Import statement*); auto-categorise rules then file most rows for you, and importing the same file twice adds nothing.

**Which currencies are supported?** Any. Each account has its own currency; transfers between currencies record both amounts; reports convert to your book's main currency with the exchange rates you enter under *Settings*.

**How are transfers, credit cards and loans counted?** Moving money between your own accounts (including paying a card bill) is a transfer, not spending. Card purchases count when you make them. Money lent or borrowed changes balances but is never income or spending. Refunds reduce spending.

**How big can it get?** SQLite handles decades of a household's transactions comfortably on a Raspberry Pi. For many users on one server, use PostgreSQL.

**Is there an iPhone app?** Not yet. Use the web app on iPhone and add it to the home screen; it works like an app.

**Can I move from another app?** Export a CSV from it and use *Import statement*, mapping the columns once.

**Can I host it for friends?** Yes. Each person's books are private unless shared. Keep sign-ups off and create accounts with `lekhamint user add`, or turn sign-ups on behind HTTPS.

**How do I get rid of it?** Run the installer with `--uninstall` (`-Uninstall` on Windows) or `docker compose down`. Your data folder is left in place until you delete it yourself.
