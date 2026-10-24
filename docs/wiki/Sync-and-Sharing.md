# Sync and Sharing

## Books

Everything you record belongs to a **book** (ledger): one for the household, another for a side business, and so on. Each book has its own accounts, categories, budgets and main currency. Switch books from the menu at the top of the sidebar.

## Several devices

Sign in with the same account on each phone and browser. Every device keeps a full copy and syncs changes through your server:

- Changes made offline are saved on the device and sent when it reconnects.
- If the same item is changed on two devices, the most recent change wins. Nothing is ever duplicated.
- Deleted items go to the Trash first and can be restored on any device.
- Recurring bills create each occurrence only once, no matter how many devices are online when it falls due.

## Sharing with family

In the web app, open **Settings → Sharing**, enter the other person's email and pick a role:

| Role | Can |
|---|---|
| **Viewer** | See everything in the book |
| **Editor** | Add and change transactions, budgets, accounts… |
| **Owner** | Everything, plus sharing and deleting the book |

The person needs an account on your server first. Either turn on sign-ups for a moment (`LEKHAMINT_ALLOW_SIGNUP=true`), or create it for them:

```sh
lekhamint user add --email priya@example.com --name Priya       # asks for a password
```

(With Docker: `docker compose exec -it lekhamint /lekhamint user add --email … --name …`.)

## Signing out a lost device

**Settings → Signed-in devices** in the web app lists every signed-in device with when it was last used. Sign out the one you lost; changing your password signs out all other devices too.
