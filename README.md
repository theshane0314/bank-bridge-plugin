# Bank Bridge

A RuneLite plugin that serves your **bank, inventory, worn equipment and real levels** to a
gear-planning website over a **local-only WebSocket**. Nothing is uploaded anywhere. There is no
server, no account, no token, no telemetry. The data goes from RuneLite to a page on your own
machine and stops there.

Built for [osrs.plaincandle.dev](https://osrs.plaincandle.dev), but the protocol is generic.

## Why this exists

The site previously tried to use the Plugin Hub plugin `osrs-bank-sync`, which pushes your bank to
an HTTP endpoint. **That plugin cannot work at all**: it calls OkHttp's blocking `execute()`
directly from its RuneLite event handlers, and RuneLite installs an interceptor that throws
`IOException: Blocking network calls are not allowed on the client thread` unconditionally. Both
its automatic and manual paths do it, so no configuration helps.

This plugin is built so that failure mode is impossible by construction (see *Threading* below).

## How it works

The plugin binds a WebSocket server to **127.0.0.1**, first free port in **37767-37776**. A page
connects to `ws://127.0.0.1:<port>`. Browsers permit this from an `https://` page because loopback
counts as a *potentially trustworthy origin*; this is the same mechanism the OSRS Wiki's own DPS
calculator uses with WikiSync in production.

The port range is shared with WikiSync deliberately, so a page can scan one range and find whichever
plugin is installed. If WikiSync already holds 37767, this plugin takes the next free port.

### No dependencies

The WebSocket server is written directly against the JDK: `ServerSocket`, the RFC 6455 handshake,
and frame coding live in `ws/WSConnection.java` and `ws/WSWebsocketServer.java`. **The plugin ships
zero third-party libraries**, which is what lets `runelite-plugin.properties` declare
`build=standard` and be reviewed automatically by the Plugin Hub rather than by hand.

The protocol surface is small enough to justify this: text frames in and out, ping, close, all of
it from a browser on loopback. What is implemented, because a browser or a hostile local process
can produce all of it:

* fragmented messages reassembled across continuation frames
* the 7-bit, 16-bit and 64-bit payload length forms in both directions
* unmasked client frames rejected with close 1002, as the RFC requires of a server
* messages over 64 KiB rejected with close 1009, so an unauthenticated peer cannot grow our heap
* binary frames rejected with 1003, since the protocol is JSON text
* ping answered with a matching pong

Because this code is ours rather than a library's, the harness drives it **at the byte level** with
its own raw client for exactly those cases. See *Socket test* below.

### Protocol

Server → client on connect, unprompted:

```json
{ "_wsType": "Hello", "pluginVersion": "1.1.0", "schemaVersion": 1,
  "username": "Zezima", "bankAvailable": true, "capturedAt": 1754082800000 }
```

Client → server:

```json
{ "_wsType": "GetBank", "sequenceId": 42 }
```

Server → client:

```json
{ "_wsType": "GetBank", "sequenceId": 42, "payload": {
    "version": 1,
    "capturedAt": 1754082800000,
    "username": "Zezima",
    "bankAvailable": true,
    "bankSource": "live",
    "bank":      [ { "id": 995, "qty": 1000000 } ],
    "inventory": [ { "id": 385, "qty": 12 } ],
    "equipment": [ { "id": 1163, "qty": 1 } ],
    "levels":    { "attack": 99, "ranged": 95 }
} }
```

Server → all clients when the bank changes (a notification only, interested pages re-issue
`GetBank`, so a page that does not care is not sent an unsolicited bank):

```json
{ "_wsType": "BankUpdated", "capturedAt": 1754082800000 }
```

On a bad request the server replies `{"_wsType":"Error","sequenceId":N,"error":"..."}` rather than
dropping the connection.

`bankSource` is `"live"` if the bank was read this session, or `"cache"` if it was restored from
disk. `bankAvailable` is `false` until the bank has been opened at least once and no cache exists.

## Security

**A WebSocket is not subject to the same-origin policy.** Without a check, any website you happened
to have open could read your bank. So:

- The server binds **127.0.0.1 only**, not a wildcard bind plus a firewall rule. Nothing off your
  machine can reach it.
- Every handshake's `Origin` is checked against an allowlist: `osrs.plaincandle.dev`, `localhost`,
  `127.0.0.1`, plus anything you add under *Extra allowed sites*. Browsers set `Origin` on every
  WebSocket handshake and page JavaScript cannot forge it. A missing `Origin` is rejected.
- Anything not on the list is closed immediately, before a single byte of data is sent.

Known limit, stated plainly: a **native program already running on your machine** can forge an
`Origin` header and read this data. It could also just read your RuneLite directory, so this is not
a meaningful escalation, but it is not a boundary against local malware, and it is not claimed to
be. The boundary is against *websites*.

The cached bank on disk (`.runelite/bank-bridge/bank-<accountHash>.json`) contains item ids and
quantities only: no display name, no account hash inside the file, no credentials. Turn the cache
off with *Remember bank between sessions*.

### It cannot see your bank PIN

Not "does not": **cannot**. A bank PIN is typed into a widget, so observing one needs a widget
read, a key or mouse listener, a varbit/varclient read, or a script hook. This plugin references
**none of them**. Its entire contact with the game is five calls: game state, local player, real
skill levels, account hash (used only to name the cache file), and the item containers that
`ItemContainerChanged` hands it. It never even calls `getItemContainer` itself.

That is enforced, not just claimed. `./gradlew accountSafetyCheck` scans the source and **fails the
build** on any reference to those APIs, gating `build`, `check`, `run`, `runHarness` and
`shadowJar`. It is tested by planting a deliberate violation and confirming the build breaks.

Scope of that guarantee, stated precisely: it blocks every local build, the dev client and any jar
produced here. It is **not** run by the Plugin Hub's own packager, which builds from source with its
own Gradle invocation. The property it protects is a fact about this source tree (the APIs are not
referenced, which anyone can check) and the task is what stops that changing by accident.

## Threading

This is the part that matters, and the reason the plugin it replaces was unusable.

- Every game read happens on the **client thread**, inside `ItemContainerChanged` / `StatChanged` /
  `GameTick` handlers, where reading the client is legal and cheap.
- Results are stored as **immutable lists behind volatile references** in `SnapshotStore`.
- `WebSocketManager` **has no reference to `Client` or `ClientThread` at all**. A socket request is
  answered straight out of the cached snapshot.

So a socket request never reaches the game client, and therefore can never block it, not on a slow
consumer, not on a hung page, not under any load. Sends go out on a dedicated daemon executor, and
the disk write is debounced 5s and also runs off the client thread.

## Config

| Setting | Default | Notes |
|---|---|---|
| Share inventory | on | |
| Share worn equipment | on | |
| Share levels | on | Real, unboosted levels |
| Remember bank between sessions | on | Caches to `.runelite/bank-bridge/` |
| Extra allowed sites | *(empty)* | Comma-separated hostnames, for developing your own page |

## Building and testing

Requires JDK 11.

```bash
./gradlew compileJava
```

### Socket test, no game required

Boots the **real** `WebSocketManager` against a seeded store and drives it two ways. Nothing under
test is mocked. 32 checks.

1. With **Java-WebSocket as an independent client implementation** (a test-only dependency, never
   shipped): origin allowlist, protocol, serialisation, config gating, port release, and a payload
   over 64 KiB so the extended length encoding is checked by something that did not write it.
2. With a **raw byte-level client** in the harness itself, for the frames a library client will not
   produce: fragmented messages, unmasked frames, a lying length header, a plain HTTP GET, ping.

```bash
./gradlew runHarness
```

Both mutation-tested: breaking the 64-bit length encoding fails checks 22 and 23, and removing the
mask rejection fails check 29. A protocol check that cannot fail is not a check.

### Plugin Hub parity check

`build=standard` means the Hub **deletes `build.gradle`** and compiles `src/main/java` against its
own template: the RuneLite client, lombok, jetbrains annotations, nothing else. A dependency added
here would compile locally and break there.

```bash
./gradlew standardBuildCheck
```

Compiles main against that exact classpath, so the failure surfaces locally instead of in their CI.
It runs as part of `build` and `check`.

> **Consequence of `build=standard` worth knowing:** the `accountSafetyCheck` gate in `build.gradle`
> (which fails the build on any reference to widgets, key/mouse listeners, varbits, chat, menu or
> script hooks, because a bank PIN is entered on a widget) no longer runs in Hub CI, since the file
> is replaced. It still runs on every local `build` and `check`.

### Browser test

Hold the socket open with seeded data, then serve the test page over `http://localhost` (a
`file://` page has no usable origin and will be rejected):

```bash
./gradlew runHarness -Pserve
```

```bash
bun test-page/serve.js
```

Then open <http://localhost:8787> and click Connect. `test-page/bank-bridge.js` is a dependency-free
client you can lift straight into a site. It handles the port scan, the `Hello` handshake, request
correlation by `sequenceId`, and `BankUpdated` pushes.

### In-game test

```bash
./gradlew run
```

Launches a dev client with the plugin loaded. Enable **Bank Bridge**, log in, open your bank once,
then run the browser test above and confirm the payload matches your actual bank.

> Requires Jagex account credentials for the dev client. Set `--insecure-write-credentials` in
> *RuneLite (configure)* → Client arguments, launch once via the Jagex Launcher to generate
> `~/.runelite/credentials.properties`. Note this will use your live account, so don't run it while
> logged in elsewhere.

## Verification status

| Area | Status |
|---|---|
| Compiles against `net.runelite:client:latest.release`, Java 11 | verified |
| Compiles against the Plugin Hub `build=standard` classpath | verified (`standardBuildCheck`) |
| Shipped jar contains no third-party classes | verified: 0 classes outside `com/plaincandle` |
| Socket lifecycle, port cycling, port release | verified (harness) |
| Origin allowlist: accept / reject / missing-origin | verified (harness) |
| Protocol, payload shape, error handling, config gating | verified (harness) |
| Frame coding: fragmentation, 64-bit lengths, mask rejection, ping, oversize | verified (harness, raw byte-level client) |
| Payload past 64 KiB parsed by an independent client | verified: 4,000 item bank round-tripped |
| Real browser connection with a genuine `Origin` header | verified: Chrome connected from `http://localhost:8787`, full `GetBank` round trip, no console errors |
| Falls through to the next port when 37767 is taken | verified: WikiSync held 37767, this took 37768 |
| Bank / inventory / equipment read from a live game | verified: 856 bank · 9 inventory · 10 worn · 24 levels, coin totals matched the game exactly |
| Account safety gate blocks a real violation | verified: planted a `Widget` + `KeyEvent` probe and the build failed |

Note the payload is **per bank slot, not per item**: non-stackables occupy a slot each, so the same
id can appear many times (155 bank fillers is 155 entries). Sum by id if you need totals.
