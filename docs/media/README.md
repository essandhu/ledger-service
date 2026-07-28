# README media — how it is made

Everything in this directory is generated from a **running stack**. Nothing is mocked, staged in
an image editor, or hand-drawn: the GIF is a recording of assertion-bearing HTTP calls, and both
screenshots are of live pages served by the compose services. If an invariant regressed, the
capture fails instead of producing a pretty lie.

| File | What it is | Made by |
|---|---|---|
| `demo.gif` | ~21 s terminal recording of `tour.sh` | `demo.tape` via [VHS](https://github.com/charmbracelet/vhs) |
| `openapi.png` | Swagger UI rendering the generated `/v3/api-docs` | `capture.mjs` (Playwright) |
| `grafana.png` | The provisioned dashboard with the drift gauges firing | `capture.mjs` (Playwright) |
| `console.png` | The read-only console explaining that same drifted run | `capture.mjs` (Playwright) |

The architecture and write-path diagrams in the README are Mermaid, inline in `README.md` —
GitHub renders them natively, so there is no image to regenerate.

## Regenerating

Start the stack first; both capture steps assume it is already up and healthy. `capture.mjs`
needs BOTH opt-in profiles — Grafana for the dashboard shot, the console for its own:

```sh
docker compose --profile observability --profile console up -d --build --wait
```

It also needs a ledger with something posted in it: the corruption it induces matches on a
non-zero balance, so against an empty stack it corrupts nothing and the sweep is honestly
`CLEAN`. Run `docs/media/tour.sh` (or `scripts/demo.sh`) first — the script fails naming this.

### demo.gif

`tour.sh` is a ~20-second cut of `scripts/demo.sh` — same guarantees, same assertions, minus the
rebuild. It is safe to run directly (`docs/media/tour.sh`) and safe to re-run: account names and
idempotency keys are namespaced per run.

VHS needs `ttyd` and `ffmpeg`, which rules out a native Windows run, so the recorder is a
container on this repo's own compose network, reaching the services by name:

```sh
docker build -f docs/media/Dockerfile.vhs -t ledger-vhs docs/media
docker run --rm --network ledger_default -v "$PWD:/vhs" \
  -e BASE=http://app:8080 -e KC=http://keycloak:8080 -e PGHOST=postgres \
  ledger-vhs docs/media/demo.tape
```

On Linux/macOS with `vhs`, `curl`, `jq` and `psql` on `PATH`, `vhs docs/media/demo.tape` works
directly — the tape's defaults point at `localhost`.

### openapi.png, grafana.png and console.png

```sh
cd docs/media
npm install && npx playwright install chromium
node capture.mjs
```

Three things worth knowing about `capture.mjs`:

- The whole API sits behind a bearer token (only `/actuator/health` is anonymous), so the browser
  context carries a real `client_credentials` token minted from the compose Keycloak. The console
  has no anonymous surface either, so its shot is taken after a real authorization-code login
  driven through Keycloak's own form — the same stock-theme selectors the e2e lane uses.
- A dashboard screenshot reading zero would say nothing about I15, so the script induces the same
  out-of-band corruption `scripts/demo.sh` does — a superuser `UPDATE` that the application's own
  role is not granted — waits for Prometheus to scrape the gauges, shoots, then repairs the
  snapshot by recomputation and re-runs the sweep. **The stack is left `CLEAN`**; the script fails
  loudly if the repair does not verify.
- `console.png` is shot **inside that same drift window**, on the run Grafana just charted: one
  induced corruption, two images of one true event, so the delta on the console page is the number
  the gauges are reading. Sequence it anywhere else and the two would tell different stories.

## Conventions

- Keep `demo.gif` under 10 MB so GitHub renders it inline (it is ~240 KB today).
- `demo.tape`'s geometry is sized so the whole tour fits without wrapping or scrolling — the last
  frame is a readable still. Widening a `tour.sh` line past 105 columns means resizing the tape.
- `node_modules/` is git-ignored; `package-lock.json` is committed so a regenerated screenshot
  comes from the same Playwright.
