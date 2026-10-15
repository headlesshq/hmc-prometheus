# Prometheus, Loki and Grafana for HMC-Prometheus

Runs Prometheus, Loki and Grafana with Docker Compose. Every Minecraft client with HMC-Prometheus is discovered
automatically, and Grafana comes with two dashboards:

* **Minecraft Clients** (home dashboard): all clients in one table (status, version, player, server, dimension,
  FPS, TPS, ping, health, players, heap, main-thread latency), graphs that compare the clients,
  warnings and errors from all logs, and a collapsed *Screens* row with a screenshot of every client that renders.
  Click a client to open its dashboard.
* **Minecraft Client**: everything about one client: a screenshot of the game, reloaded with every dashboard refresh
  (pick 5s at the top right), next to the log with chat colors and a console for hmc-specifics commands below it,
  whose output also appears in the log,
  then performance, server, network, player, world, tab list and JVM. Pick the client at the top.

## Setup (Linux)

```shell
# Create the discovery directory yourself, otherwise Docker creates it owned by root and the clients can't write to it
mkdir -p ~/.hmc-prometheus/targets
docker compose up -d
```

Start your clients with HMC-Specifics and HMC-Prometheus, then open <http://localhost:3000>.
To see the logs, tell the clients where Loki is, once per game directory or with `-Dhmc.prometheus.lokiUrl=...`:
```
prometheus set lokiUrl http://127.0.0.1:3100
```
The console needs `prometheus set console true` (or `-Dhmc.prometheus.console=true`) in the client.
Grafana is started with `GF_PANELS_DISABLE_SANITIZE_HTML`, because it would otherwise sandbox the console's iframe.
The screenshots need a game that renders, e.g. with a display or `xvfb-run`. For a live view at about one frame per
second, open `http://127.0.0.1:<port>/live` of the client in a browser tab.
In HeadlessMc's headless mode (`launch --headless`) they show that nothing is rendered.
Anonymous users can view the dashboards; to edit, log in as `admin` / `admin`
(or set `GRAFANA_ADMIN_PASSWORD` before the first start).

Without Minecraft, run `./gradlew runDemo -PlokiUrl=http://127.0.0.1:3100` in the project root to start four
simulated clients: a multiplayer client, a singleplayer client, a headless client that is in the main menu most of the
time, and a laggy client on an old version whose main thread freezes every two minutes.

## How it works

```
prometheus/prometheus.yml          scrape every 5s, targets from /targets/*.json (honor_labels: true)
loki/loki.yml                      a single Loki process, stores the logs for 30 days on the filesystem
grafana/provisioning/datasources   Prometheus and Loki datasources with the uids "prometheus" and "loki"
grafana/provisioning/dashboards    loads the dashboards from grafana/dashboards into the folder "Minecraft"
grafana/dashboards                 the dashboards as JSON
```

Each client writes `~/.hmc-prometheus/targets/hmc-prometheus-<port>.json` when it starts and deletes it when it exits.
Prometheus watches that directory, so clients appear and disappear without restarting anything.
A client that crashed keeps its file, so it is shown as `DOWN` until another client starts and cleans up.

Prometheus, Loki and Grafana use the host network, because the clients only listen on `127.0.0.1` by default.
They only listen on `127.0.0.1` too, so they aren't reachable from other machines.
Screenshots are loaded by your browser directly from the clients, e.g. `http://127.0.0.1:9400/live`,
so they only show up in a browser on the same machine, or one that can reach the clients.

## Docker Desktop (macOS, Windows) or Prometheus on another machine

Without host networking, Prometheus can't reach `127.0.0.1` of the host. Let the clients listen on all interfaces
and write an address that Prometheus can reach into the discovery file, e.g. in `config/hmc-prometheus.properties`:

```properties
host=0.0.0.0
discoveryAddress=host.docker.internal
```

Then replace `network_mode: host` in `docker-compose.yml` with port mappings (`9090:9090`, `3100:3100`, `3000:3000`),
remove `--web.listen-address` and `GF_SERVER_HTTP_ADDR`, let Loki listen on `0.0.0.0` in `loki/loki.yml`,
change the datasource URLs to `http://prometheus:9090` and `http://loki:3100`,
and set `lokiUrl` in the clients to `http://localhost:3100`.
With `host=0.0.0.0` the metrics are reachable from your network, so only do this on networks you trust.
The live view uses the address from the discovery file, which your browser can't resolve as `host.docker.internal`.
Add `127.0.0.1 host.docker.internal` to your hosts file if your system does not do that already.

For clients on other machines, use the `minecraft-static` job in `prometheus/prometheus.yml`
or share the discovery directory.

## Changing the dashboards

The dashboards can be edited in Grafana. To keep a change, export the dashboard as JSON
(Share, Export, without "Export for sharing externally") and replace the file in `grafana/dashboards`.
