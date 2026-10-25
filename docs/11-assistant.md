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
- Models that cannot call tools get a compact summary instead, and the answer says so.
- The assistant cannot create, change or delete anything.

## Connecting

| Mode | Where the key lives | Notes |
|---|---|---|
| Web: from this browser | Your LekhaMint server (per person), held in memory for the tab | The page may only call origins in its CSP: OpenAI, OpenRouter and `localhost` by default; add more with `LEKHAMINT_AI_CONNECT_ORIGINS`. Local servers need CORS (`OLLAMA_ORIGINS`). |
| Web: through the server | Server environment | Set `LEKHAMINT_AI_BASE_URL` (e.g. `http://localhost:11434/v1`), `LEKHAMINT_AI_API_KEY`, `LEKHAMINT_AI_MODEL`, optional `LEKHAMINT_AI_TIMEOUT`. Endpoint: `POST /api/v1/ai/chat/completions` (signed in). Bodies are never logged. |
| Android | Android Keystore (encrypted) | Base URL, model and key in Assistant settings. |

## Privacy

Nothing is sent until the user agrees for that destination; the prompt names the host.
Payee names, notes and people's names can be left out (setting "Include payee names…").
