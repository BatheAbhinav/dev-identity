# Developer Identity Graph

Turns a developer's GitHub activity into an interactive graph. It pulls data
from the GitHub API, stores it as nodes and edges, and serves a web page where
you can explore the graph, see language stats, and browse the raw data.

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-green)

## What it builds

**Nodes:** users, repositories, languages, organizations.

**Edges:**

| Edge | Meaning | Weight |
|---|---|---|
| `OWNS` | user → their repo | — |
| `WRITTEN_IN` | repo → language | bytes of code |
| `COLLABORATES_WITH` | user → user | shared commits across common repos |
| `CONTRIBUTED_TO` | user → repo they don't own | — |
| `MEMBER_OF` | user → organization | — |

## Running it

Requires Java 21. No other setup — the database (H2) is embedded.

```bash
./mvnw spring-boot:run
```

Then open **http://localhost:8080**, enter a GitHub username and/or a token,
and click **Refresh from GitHub**.

### Username vs. token

| You provide | You get |
|---|---|
| Username only | That user's public repos (GitHub limits this to 60 API calls/hour) |
| Token only | Your own full graph — private and org repos included, 5000 calls/hour |
| Both (your own username) | Same as token only |
| Both (someone else's username) | Their public graph, at the higher rate limit |

The token is a [personal access token](https://github.com/settings/tokens). It
is sent only to your own backend, used in memory for one refresh, and never
stored or logged. You can also set `GITHUB_TOKEN` / `GITHUB_USERNAME` as
environment variables instead of typing them in the page.

## The frontend

One page, three views:

- **Graph** — force-directed layout (D3). Drag, zoom, hover for details,
  click a node to highlight its neighborhood.
- **Stats** — most-used languages by share of code, top collaborators,
  summary counts, and (in token mode) repo traffic for the last 14 days:
  views, clones, and unique visitors/cloners per repo.
- **Table** — the raw nodes and edges.

## API

| Endpoint | What it does |
|---|---|
| `GET /graph` | Full graph as JSON (`nodes` + `edges`) |
| `GET /nodes/{id}/neighbors` | A node and everything connected to it |
| `GET /stats` | Aggregates: language shares, top collaborators, counts, traffic |
| `POST /refresh` | Re-ingest from GitHub (body: `{"username": "...", "token": "..."}`, both optional) |
| `GET /status` | Whether a refresh is running, and the last error if one failed |

A refresh rebuilds the graph in a single transaction — if anything fails
(bad token, rate limit), the previous graph is left untouched.

## How it's put together

```
GitHub API  →  ingestion (GitHubClient, IngestionService)
            →  graph store (H2, two tables: nodes / edges)
            →  REST API (GraphController)
            →  static frontend (src/main/resources/static/index.html)
```

Data lives in `data/devidentity.mv.db` and survives restarts. Tests use an
in-memory database, so they can run while the app is up.
