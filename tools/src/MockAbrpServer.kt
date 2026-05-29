import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors

// ── Data model ───────────────────────────────────────────────────────────────

data class TelemetryEntry(
    val receivedAt: Instant,
    val token: String?,
    val soc: String?,
    val speed: String?,
    val power: String?,
    val isCharging: String?,
    val battTemp: String?,
    val lat: String?,
    val lon: String?,
    val rawParams: Map<String, String>
)

// ── Globals ───────────────────────────────────────────────────────────────────

val history = ArrayBlockingQueue<TelemetryEntry>(50)
var totalRequests = 0L

val tsFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
    val server = HttpServer.create(InetSocketAddress(8088), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/1/tlm/send", ::handleTlmSend)
    server.createContext("/data",        ::handleData)
    server.createContext("/",            ::handleDashboard)

    server.start()

    println("╔══════════════════════════════════════════════════════╗")
    println("║        Mock ABRP server running on port 8088         ║")
    println("╠══════════════════════════════════════════════════════╣")
    println("║  Dashboard: http://localhost:8088                    ║")
    println("║  Endpoint:  http://localhost:8088/1/tlm/send?token=test ║")
    println("╚══════════════════════════════════════════════════════╝")
    println()
    println("Waiting for telemetry... (Ctrl+C to stop)")
}

// ── Handlers ──────────────────────────────────────────────────────────────────

fun handleTlmSend(exchange: HttpExchange) {
    if (exchange.requestMethod != "POST") {
        sendResponse(exchange, 405, "text/plain", "Method Not Allowed")
        return
    }

    // Token comes as a URL query param: ?token=xxx
    val query = exchange.requestURI.query ?: ""
    val queryParams = parseUrlEncoded(query)

    // Body is URL-encoded telemetry
    val bodyBytes = exchange.requestBody.readBytes()
    val bodyStr = bodyBytes.toString(Charsets.UTF_8)
    val bodyParams = parseUrlEncoded(bodyStr)

    // Merge: body wins over query for duplicates
    val allParams = queryParams + bodyParams

    val entry = TelemetryEntry(
        receivedAt  = Instant.now(),
        token       = queryParams["token"] ?: allParams["token"],
        soc         = allParams["soc"],
        speed       = allParams["speed"],
        power       = allParams["power"],
        isCharging  = allParams["is_charging"],
        battTemp    = allParams["batt_temp"],
        lat         = allParams["lat"],
        lon         = allParams["lon"],
        rawParams   = allParams
    )

    synchronized(history) {
        if (history.remainingCapacity() == 0) history.poll()
        history.offer(entry)
        totalRequests++
    }

    val displayTs = tsFormatter.format(entry.receivedAt)
    println("[$displayTs] POST /1/tlm/send — SOC=${entry.soc}% speed=${entry.speed} power=${entry.power} charging=${entry.isCharging}")

    sendResponse(exchange, 200, "application/json", """{"status":"ok","result":"OK"}""")
}

fun handleData(exchange: HttpExchange) {
    val snapshot: List<TelemetryEntry>
    val total: Long
    synchronized(history) {
        snapshot = history.toList().reversed()   // most recent first
        total = totalRequests
    }

    val sb = StringBuilder()
    sb.append("""{"total":$total,"entries":[""")
    snapshot.forEachIndexed { i, e ->
        if (i > 0) sb.append(',')
        sb.append(entryToJson(e))
    }
    sb.append("]}")

    sendResponse(exchange, 200, "application/json", sb.toString())
}

fun handleDashboard(exchange: HttpExchange) {
    sendResponse(exchange, 200, "text/html; charset=utf-8", DASHBOARD_HTML)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fun parseUrlEncoded(input: String): Map<String, String> {
    if (input.isBlank()) return emptyMap()
    return input.split('&')
        .mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null
            else {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                key to value
            }
        }
        .toMap()
}

fun entryToJson(e: TelemetryEntry): String {
    fun String?.jsonVal() = if (this == null) "null" else "\"${this.replace("\"", "\\\"")}\""
    return """{
        "ts":"${tsFormatter.format(e.receivedAt)}",
        "token":${e.token.jsonVal()},
        "soc":${e.soc.jsonVal()},
        "speed":${e.speed.jsonVal()},
        "power":${e.power.jsonVal()},
        "is_charging":${e.isCharging.jsonVal()},
        "batt_temp":${e.battTemp.jsonVal()},
        "lat":${e.lat.jsonVal()},
        "lon":${e.lon.jsonVal()}
    }""".trimIndent().replace("\n", "").replace("  ", " ")
}

fun sendResponse(exchange: HttpExchange, code: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

// ── Dashboard HTML ─────────────────────────────────────────────────────────────

val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Mock ABRP Server</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: #0f1117;
      color: #e2e8f0;
      padding: 24px;
      min-height: 100vh;
    }

    header {
      display: flex;
      align-items: baseline;
      gap: 16px;
      margin-bottom: 24px;
    }

    h1 {
      font-size: 1.4rem;
      font-weight: 700;
      color: #60a5fa;
      letter-spacing: -0.02em;
    }

    .badge {
      font-size: 0.75rem;
      padding: 2px 8px;
      border-radius: 999px;
      background: #1e293b;
      color: #94a3b8;
    }

    .stats {
      display: flex;
      gap: 16px;
      margin-bottom: 20px;
    }

    .stat-card {
      background: #1e293b;
      border: 1px solid #334155;
      border-radius: 8px;
      padding: 12px 20px;
    }

    .stat-label {
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: #64748b;
      margin-bottom: 4px;
    }

    .stat-value {
      font-size: 1.5rem;
      font-weight: 700;
      color: #f1f5f9;
    }

    .endpoint-box {
      background: #1e293b;
      border: 1px solid #334155;
      border-radius: 8px;
      padding: 10px 16px;
      font-family: monospace;
      font-size: 0.85rem;
      color: #86efac;
      margin-bottom: 24px;
    }

    .endpoint-box span { color: #64748b; }

    #status {
      font-size: 0.8rem;
      color: #64748b;
      margin-bottom: 12px;
    }

    .dot {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #22c55e;
      margin-right: 6px;
      animation: pulse 2s infinite;
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.3; }
    }

    .table-wrap {
      overflow-x: auto;
      border-radius: 8px;
      border: 1px solid #334155;
    }

    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.85rem;
    }

    thead th {
      background: #1e293b;
      color: #64748b;
      font-weight: 600;
      text-transform: uppercase;
      font-size: 0.7rem;
      letter-spacing: 0.06em;
      padding: 10px 14px;
      text-align: left;
      white-space: nowrap;
      border-bottom: 1px solid #334155;
    }

    tbody tr {
      border-bottom: 1px solid #1e293b;
      transition: background 0.15s;
    }

    tbody tr:hover { background: #1e2535; }

    tbody tr.latest {
      background: #0f2d1a;
    }

    tbody tr.latest td { color: #86efac; }

    tbody td {
      padding: 9px 14px;
      white-space: nowrap;
      color: #cbd5e1;
    }

    td.ts { color: #475569; font-family: monospace; font-size: 0.78rem; }
    td.num { font-variant-numeric: tabular-nums; font-family: monospace; }

    .charging-yes {
      display: inline-block;
      background: #14532d;
      color: #86efac;
      border-radius: 4px;
      padding: 1px 7px;
      font-size: 0.75rem;
      font-weight: 600;
    }

    .charging-no {
      display: inline-block;
      background: #1e293b;
      color: #64748b;
      border-radius: 4px;
      padding: 1px 7px;
      font-size: 0.75rem;
    }

    .empty-msg {
      text-align: center;
      color: #475569;
      padding: 48px;
      font-size: 0.9rem;
    }
  </style>
</head>
<body>

<header>
  <h1>Mock ABRP Server</h1>
  <span class="badge">localhost:8088</span>
</header>

<div class="endpoint-box">
  <span>Endpoint: </span>http://localhost:8088/1/tlm/send?token=test
  &nbsp;&nbsp;|&nbsp;&nbsp;
  <span>Emulator: </span>http://10.0.2.2:8088/1/tlm/send?token=test
</div>

<div class="stats">
  <div class="stat-card">
    <div class="stat-label">Total Requests</div>
    <div class="stat-value" id="total-count">0</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Latest SOC</div>
    <div class="stat-value" id="latest-soc">—</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Latest Speed</div>
    <div class="stat-value" id="latest-speed">—</div>
  </div>
  <div class="stat-card">
    <div class="stat-label">Latest Power</div>
    <div class="stat-value" id="latest-power">—</div>
  </div>
</div>

<div id="status"><span class="dot"></span>Polling every 2 seconds…</div>

<div class="table-wrap">
  <table>
    <thead>
      <tr>
        <th>Timestamp</th>
        <th>Token</th>
        <th>SOC %</th>
        <th>Speed km/h</th>
        <th>Power kW</th>
        <th>Charging</th>
        <th>Batt Temp °C</th>
        <th>Lat</th>
        <th>Lon</th>
      </tr>
    </thead>
    <tbody id="rows">
      <tr><td colspan="9" class="empty-msg">No data yet. Send a POST to /1/tlm/send</td></tr>
    </tbody>
  </table>
</div>

<script>
  function fmt(v, suffix) {
    return (v !== null && v !== undefined && v !== '') ? v + (suffix || '') : '—';
  }

  function chargingBadge(v) {
    if (v === '1' || v === 'true') return '<span class="charging-yes">YES</span>';
    if (v === '0' || v === 'false') return '<span class="charging-no">no</span>';
    return '—';
  }

  async function poll() {
    try {
      const res = await fetch('/data');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();

      document.getElementById('total-count').textContent = data.total;

      const entries = data.entries || [];
      if (entries.length > 0) {
        const latest = entries[0];
        document.getElementById('latest-soc').textContent   = fmt(latest.soc, '%');
        document.getElementById('latest-speed').textContent = fmt(latest.speed, ' km/h');
        document.getElementById('latest-power').textContent = fmt(latest.power, ' kW');
      }

      const tbody = document.getElementById('rows');
      if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" class="empty-msg">No data yet. Send a POST to /1/tlm/send</td></tr>';
        return;
      }

      tbody.innerHTML = entries.map((e, i) => {
        const rowClass = i === 0 ? 'latest' : '';
        return [
          '<tr class="' + rowClass + '">',
          '<td class="ts">'  + e.ts + '</td>',
          '<td>'             + fmt(e.token) + '</td>',
          '<td class="num">' + fmt(e.soc, '%') + '</td>',
          '<td class="num">' + fmt(e.speed) + '</td>',
          '<td class="num">' + fmt(e.power) + '</td>',
          '<td>'             + chargingBadge(e.is_charging) + '</td>',
          '<td class="num">' + fmt(e.batt_temp) + '</td>',
          '<td class="num">' + fmt(e.lat) + '</td>',
          '<td class="num">' + fmt(e.lon) + '</td>',
          '</tr>'
        ].join('');
      }).join('');

    } catch (err) {
      document.getElementById('status').innerHTML =
        '<span style="color:#ef4444">&#9888; Poll error: ' + err.message + '</span>';
    }
  }

  poll();
  setInterval(poll, 2000);
</script>
</body>
</html>
""".trimIndent()
