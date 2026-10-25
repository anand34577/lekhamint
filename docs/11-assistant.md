# 11 – Assistant

The assistant answers questions about your own money in plain language. It works with
any OpenAI-compatible chat API: OpenAI, OpenRouter, Ollama, LM Studio, vLLM, llama.cpp
server, or a small model served on the phone itself at a `localhost` address.

## How it stays grounded

- The model never reads the database. It calls read-only tools (overview, spending
  summary, transaction search, monthly trend, accounts, loans, deposits, personal debts,
  upcoming obligations, budgets, net-worth history). LekhaMint runs them and returns exact
  figures; the model only explains them.
- Every answer lists the lookups behind it, with links to the screens that show the data.
- On the web, the figures an answer used are also drawn as charts (spending by category,
  monthly trend, net worth, loans, deposits, budgets, what is coming up). The charts are built
  from the lookup results, not from the model's text, so they are exact.
- Models that cannot call tools get a compact summary instead, and the answer says so.
- The assistant cannot create, change or delete anything.

## Connecting

| Mode | Where the key lives | Notes |
|---|---|---|
| Web: from this browser | Your LekhaMint server (per person), held in memory for the tab | The page may only call origins in its CSP: OpenAI, OpenRouter and `localhost` by default; add more with `LEKHAMINT_AI_CONNECT_ORIGINS`. Local servers need CORS (`OLLAMA_ORIGINS`). |
| Web: through the server | Server environment | Set `LEKHAMINT_AI_BASE_URL` (e.g. `http://localhost:11434/v1`), `LEKHAMINT_AI_API_KEY`, `LEKHAMINT_AI_MODEL`, optional `LEKHAMINT_AI_TIMEOUT`. Endpoint: `POST /api/v1/ai/chat/completions` (signed in). Bodies are never logged. |
| Android | Android Keystore (encrypted) | Base URL, model and key in Assistant settings. |

## Using it (web)

- Answers stream in as the model writes them; while it works, each lookup shows as a step.
  The server proxy relays the stream when the browser asks for one (`"stream": true`) and
  the model sends `text/event-stream`; otherwise it returns one JSON response as before.
  Servers that refuse streaming are asked again without it.
- Conversations are listed at the side and can be reopened or deleted. They are kept in
  this browser only (localStorage, per book, the latest 30) and removed on sign-out.
- The last question can be edited or asked again; follow-up questions are suggested from
  the lookups an answer used.

## Privacy

Nothing is sent until the user agrees for that destination; the prompt names the host.
Payee names, notes and people's names can be left out (setting "Include payee names…").
