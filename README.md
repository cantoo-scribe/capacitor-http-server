# @cantoo-scribe/capacitor-http-server

A generic [Capacitor](https://capacitorjs.com) plugin that exposes a local
HTTP server on iOS and Android with routing delegated to JavaScript.

The plugin has **no business logic**. It only handles the transport layer:
it starts a listener, hands every incoming request to JavaScript via a
`request` event, and writes back whatever JavaScript returns through
`respond()`. This keeps your app code — authentication, routing, payloads,
CORS policy — entirely in TypeScript, where it can be tested, updated from
a hot-reload bundle, and shared across platforms.

## Install

```bash
npm install @cantoo-scribe/capacitor-http-server
npx cap sync
```

### Android configuration

The plugin ships all required permissions in its manifest:

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` (API 34+)
- `POST_NOTIFICATIONS` (API 33+)

The server runs inside a foreground service with type `dataSync`, so it
survives the screen being locked. On Android 13+ the system requires a
visible notification, hence `POST_NOTIFICATIONS`. If the user declines the
permission, the server still starts but the system is free to kill the
process at any moment. The plugin requests the permission automatically
on the first `start()` call.

You **must** supply `android.notificationTitle` and `android.notificationText`
when starting the server:

```ts
await HttpServer.start({
  android: {
    notificationTitle: 'My App',
    notificationText: 'Local server is running.',
    smallIconResourceName: 'ic_notification', // res/drawable/ic_notification.png
    channelId: 'my_app_http',
    channelName: 'Local HTTP server',
  },
});
```

### iOS configuration

Add the following keys to your app's `Info.plist`:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Used to expose a local server for sharing files across devices on this network.</string>

<!-- Optional. Only needed if you broadcast your server over Bonjour. -->
<key>NSBonjourServices</key>
<array>
  <string>_myservice._tcp</string>
</array>
```

Without `NSLocalNetworkUsageDescription`, iOS 14+ silently drops every
incoming connection from other devices.

> **Note:** iOS does not allow long-running HTTP servers in background.
> The plugin requests a short background task on app backgrounding, then
> emits a `server-error` event with `fatal: true` when the window expires.
> Restart the server on `UIApplication.didBecomeActive`.

## Usage

### Echo server

A complete, 20-line echo server that returns exactly what it received:

```ts
import { HttpServer } from '@cantoo-scribe/capacitor-http-server';

await HttpServer.addListener('request', async (req) => {
  await HttpServer.respond({
    requestId: req.requestId,
    status: 200,
    headers: { 'content-type': req.headers['content-type'] ?? 'text/plain' },
    ...(req.bodyText !== undefined && { bodyText: req.bodyText }),
    ...(req.bodyBase64 !== undefined && { bodyBase64: req.bodyBase64 }),
    ...(req.bodyFilePath !== undefined && { bodyFilePath: req.bodyFilePath }),
  });
});

const { url } = await HttpServer.start({
  android: {
    notificationTitle: 'Echo server',
    notificationText: 'Listening for requests',
  },
});
console.log('Listening at', url);
```

Test it from a laptop on the same network:

```bash
curl http://<device-ip>:<port>/ -d 'hello'
# hello
```

### Handling errors

```ts
HttpServer.addListener('server-error', ({ message, fatal }) => {
  console.warn('Server error:', message, 'fatal:', fatal);
  if (fatal) {
    // On iOS the server dies in background; restart on foreground.
  }
});
```

### Streaming a large file out

```ts
HttpServer.addListener('request', async (req) => {
  if (req.path === '/big.bin') {
    await HttpServer.respond({
      requestId: req.requestId,
      status: 200,
      headers: { 'content-type': 'application/octet-stream' },
      bodyFilePath: '/absolute/path/to/file.bin', // streamed, no RAM spike
    });
  } else {
    await HttpServer.respond({ requestId: req.requestId, status: 404 });
  }
});
```

### Receiving a large upload

Bodies above `fileBodyThresholdBytes` (default 1 MB) are streamed to a
temp file managed by the plugin. JavaScript receives the absolute path
via `bodyFilePath` and is free to move, hash, or re-stream it. The plugin
deletes the temp file automatically once `respond()` returns.

## API

<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [`respond(...)`](#respond)
* [`addListener('request', ...)`](#addlistenerrequest-)
* [`addListener('server-error', ...)`](#addlistenerserver-error-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### start(...)

```typescript
start(options?: StartOptions | undefined) => any
```

Start the local HTTP server. Picks a free port if `port` is omitted.
On Android, starts a foreground service so the server survives screen lock.
Throws if the server is already running with a different configuration,
or if required permissions are missing.

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#startoptions">StartOptions</a></code> |

**Returns:** <code>any</code>

--------------------


### stop()

```typescript
stop() => any
```

Stop the server and release all resources (port, threads, temp files, service).
Safe to call when already stopped.

**Returns:** <code>any</code>

--------------------


### respond(...)

```typescript
respond(response: HttpResponse) => any
```

Reply to a request previously received via the `request` event.
The plugin looks up `requestId`, writes the response, and cleans up any
temporary body file. Calling respond twice for the same requestId is a
no-op on the second call. Unanswered requests time out (504) after 60 s.

| Param          | Type                                                  |
| -------------- | ----------------------------------------------------- |
| **`response`** | <code><a href="#httpresponse">HttpResponse</a></code> |

**Returns:** <code>any</code>

--------------------


### addListener('request', ...)

```typescript
addListener(eventName: 'request', listener: (event: HttpRequestEvent) => void) => Promise<PluginListenerHandle> & PluginListenerHandle
```

Subscribe to incoming HTTP requests. Every accepted request fires exactly
one `request` event; JS is expected to call `respond` exactly once per
event.

| Param           | Type                                                                              |
| --------------- | --------------------------------------------------------------------------------- |
| **`eventName`** | <code>'request'</code>                                                            |
| **`listener`**  | <code>(event: <a href="#httprequestevent">HttpRequestEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### addListener('server-error', ...)

```typescript
addListener(eventName: 'server-error', listener: (event: ServerErrorEvent) => void) => Promise<PluginListenerHandle> & PluginListenerHandle
```

Subscribe to server-level errors that are not tied to a specific request
(e.g. the underlying socket died, port was stolen, permission revoked).
The server is considered dead when this fires with `fatal: true`.

| Param           | Type                                                                              |
| --------------- | --------------------------------------------------------------------------------- |
| **`eventName`** | <code>'server-error'</code>                                                       |
| **`listener`**  | <code>(event: <a href="#servererrorevent">ServerErrorEvent</a>) =&gt; void</code> |

**Returns:** <code>any</code>

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => any
```

Remove every listener registered on this plugin.

**Returns:** <code>any</code>

--------------------


### Interfaces


#### StartOptions

| Prop                         | Type                                                                | Description                                                                                                                                                 |
| ---------------------------- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`port`**                   | <code>number</code>                                                 | If omitted or 0, the OS picks a free port in the dynamic range.                                                                                             |
| **`maxBodyBytes`**           | <code>number</code>                                                 | Max request body size in bytes. Bodies above this return 413. Default: 50 * 1024 * 1024 (50 MB).                                                            |
| **`fileBodyThresholdBytes`** | <code>number</code>                                                 | Threshold above which the plugin stores the body in a temp file and exposes it via `bodyFilePath` instead of `bodyBase64`. Default: 1 * 1024 * 1024 (1 MB). |
| **`android`**                | <code><a href="#startoptionsandroid">StartOptionsAndroid</a></code> | Android only. Title / text / small icon used by the foreground service notification (mandatory on Android 13+). Ignored on iOS.                             |


#### StartOptionsAndroid

| Prop                        | Type                | Description                                                     |
| --------------------------- | ------------------- | --------------------------------------------------------------- |
| **`notificationTitle`**     | <code>string</code> | Foreground-service notification title. Required on Android 13+. |
| **`notificationText`**      | <code>string</code> | Foreground-service notification body text.                      |
| **`smallIconResourceName`** | <code>string</code> | Android drawable resource name, e.g. "ic_notification".         |
| **`channelId`**             | <code>string</code> | Notification channel ID. Plugin creates the channel if missing. |
| **`channelName`**           | <code>string</code> | Notification channel display name.                              |


#### StartResult

| Prop          | Type                | Description                                                                    |
| ------------- | ------------------- | ------------------------------------------------------------------------------ |
| **`port`**    | <code>number</code> | Chosen port (same as options.port when provided).                              |
| **`url`**     | <code>string</code> | Full URL using the primary LAN IPv4 address, e.g. "http://192.168.1.42:49281". |
| **`localIp`** | <code>string</code> | Primary LAN IPv4, or "127.0.0.1" if none could be detected.                    |


#### HttpResponse

| Prop          | Type                                                | Description                                       |
| ------------- | --------------------------------------------------- | ------------------------------------------------- |
| **`data`**    | <code>any</code>                                    | Additional data received with the Http response.  |
| **`status`**  | <code>number</code>                                 | The status code received from the Http response.  |
| **`headers`** | <code><a href="#httpheaders">HttpHeaders</a></code> | The headers received from the Http response.      |
| **`url`**     | <code>string</code>                                 | The response URL received from the Http response. |


#### HttpHeaders


#### HttpRequestEvent

| Prop               | Type                                              | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------------------ | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`requestId`**    | <code>string</code>                               | Opaque ID. Pass back unchanged in `respond`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **`method`**       | <code><a href="#httpmethod">HttpMethod</a></code> |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **`path`**         | <code>string</code>                               | Decoded pathname, always starts with "/". No querystring.                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **`query`**        | <code>Record&lt;string, string&gt;</code>         | Parsed querystring. Repeated keys keep the last value.                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **`headers`**      | <code>Record&lt;string, string&gt;</code>         | Lower-cased header names.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **`clientIp`**     | <code>string</code>                               | Remote peer IP if available (useful for logging only).                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **`bodyText`**     | <code>string</code>                               | Exactly one of the three body fields is set (undefined when the request has no body). The plugin picks the representation based on content type and size: - UTF-8 body with a text-like content type (text/\*, application/json, application/x-www-form-urlencoded) below threshold -&gt; bodyText - Any other body below threshold -&gt; bodyBase64 - Body above threshold -&gt; bodyFilePath Temp files referenced by bodyFilePath are deleted automatically once `respond` is called for the same requestId (or on timeout). |
| **`bodyBase64`**   | <code>string</code>                               |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| **`bodyFilePath`** | <code>string</code>                               |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |


#### PluginListenerHandle

| Prop         | Type                      |
| ------------ | ------------------------- |
| **`remove`** | <code>() =&gt; any</code> |


#### ServerErrorEvent

| Prop          | Type                 | Description                                                        |
| ------------- | -------------------- | ------------------------------------------------------------------ |
| **`message`** | <code>string</code>  |                                                                    |
| **`fatal`**   | <code>boolean</code> | True when the server is no longer listening and must be restarted. |


### Type Aliases


#### HttpMethod

HTTP methods that the plugin forwards to JavaScript. Any value outside this
list produced by a client is still forwarded as-is (upper-cased) but typed
as <a href="#httpmethod">`HttpMethod`</a> for convenience of the common cases.

<code>'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'OPTIONS' | 'HEAD'</code>

</docgen-api>

## Limitations

- **No HTTPS.** The plugin listens on plain HTTP. Use it over a trusted
  LAN or add a reverse proxy if you need TLS.
- **No chunked transfer encoding** on the request side. Clients must send
  `Content-Length`; otherwise the server replies with 501.
- **No WebSocket support** (tracked separately; out of scope here).
- **iOS background.** The server is suspended when the app leaves the
  foreground. A `server-error` event is emitted with `fatal: true`.

## License

MIT © Cantoo Scribe
