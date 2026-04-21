import type { PluginListenerHandle } from '@capacitor/core';

/**
 * HTTP methods that the plugin forwards to JavaScript. Any value outside this
 * list produced by a client is still forwarded as-is (upper-cased) but typed
 * as `HttpMethod` for convenience of the common cases.
 */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' | 'OPTIONS' | 'HEAD';

export interface StartOptionsAndroid {
  /** Foreground-service notification title. Required on Android 13+. */
  notificationTitle: string;
  /** Foreground-service notification body text. */
  notificationText: string;
  /** Android drawable resource name, e.g. "ic_notification". */
  smallIconResourceName?: string;
  /** Notification channel ID. Plugin creates the channel if missing. */
  channelId?: string;
  /** Notification channel display name. */
  channelName?: string;
}

export interface StartOptions {
  /** If omitted or 0, the OS picks a free port in the dynamic range. */
  port?: number;
  /**
   * Max request body size in bytes. Bodies above this return 413.
   * Default: 50 * 1024 * 1024 (50 MB).
   */
  maxBodyBytes?: number;
  /**
   * Threshold above which the plugin stores the body in a temp file and
   * exposes it via `bodyFilePath` instead of `bodyBase64`.
   * Default: 1 * 1024 * 1024 (1 MB).
   */
  fileBodyThresholdBytes?: number;
  /**
   * Android only. Title / text / small icon used by the foreground service
   * notification (mandatory on Android 13+). Ignored on iOS.
   */
  android?: StartOptionsAndroid;
}

export interface StartResult {
  /** Chosen port (same as options.port when provided). */
  port: number;
  /** Full URL using the primary LAN IPv4 address, e.g. "http://192.168.1.42:49281". */
  url: string;
  /** Primary LAN IPv4, or "127.0.0.1" if none could be detected. */
  localIp: string;
}

export interface HttpRequestEvent {
  /** Opaque ID. Pass back unchanged in `respond`. */
  requestId: string;
  method: HttpMethod;
  /** Decoded pathname, always starts with "/". No querystring. */
  path: string;
  /** Parsed querystring. Repeated keys keep the last value. */
  query: Record<string, string>;
  /** Lower-cased header names. */
  headers: Record<string, string>;
  /** Remote peer IP if available (useful for logging only). */
  clientIp?: string;
  /**
   * Exactly one of the three body fields is set (undefined when the request
   * has no body). The plugin picks the representation based on content type
   * and size:
   *   - UTF-8 body with a text-like content type (text/\*, application/json,
   *     application/x-www-form-urlencoded) below threshold  -> bodyText
   *   - Any other body below threshold                       -> bodyBase64
   *   - Body above threshold                                 -> bodyFilePath
   * Temp files referenced by bodyFilePath are deleted automatically once
   * `respond` is called for the same requestId (or on timeout).
   */
  bodyText?: string;
  bodyBase64?: string;
  bodyFilePath?: string;
}

export interface HttpResponse {
  requestId: string;
  status: number;
  /** Header names are sent as-is. Content-Length is computed by the plugin. */
  headers?: Record<string, string>;
  /** Exactly one of these (or none for an empty body). */
  bodyText?: string;
  bodyBase64?: string;
  /**
   * Absolute path to a file on the device. The plugin streams it directly
   * without passing the bytes through the JS bridge. The file is NOT deleted
   * after streaming; the caller owns its lifecycle.
   */
  bodyFilePath?: string;
}

export interface ServerErrorEvent {
  message: string;
  /** True when the server is no longer listening and must be restarted. */
  fatal: boolean;
}

export interface HttpServerPlugin {
  /**
   * Start the local HTTP server. Picks a free port if `port` is omitted.
   * On Android, starts a foreground service so the server survives screen lock.
   * Throws if the server is already running with a different configuration,
   * or if required permissions are missing.
   */
  start(options?: StartOptions): Promise<StartResult>;

  /**
   * Stop the server and release all resources (port, threads, temp files, service).
   * Safe to call when already stopped.
   */
  stop(): Promise<void>;

  /**
   * Reply to a request previously received via the `request` event.
   * The plugin looks up `requestId`, writes the response, and cleans up any
   * temporary body file. Calling respond twice for the same requestId is a
   * no-op on the second call. Unanswered requests time out (504) after 60 s.
   */
  respond(response: HttpResponse): Promise<void>;

  /**
   * Subscribe to incoming HTTP requests. Every accepted request fires exactly
   * one `request` event; JS is expected to call `respond` exactly once per
   * event.
   */
  addListener(
    eventName: 'request',
    listener: (event: HttpRequestEvent) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  /**
   * Subscribe to server-level errors that are not tied to a specific request
   * (e.g. the underlying socket died, port was stolen, permission revoked).
   * The server is considered dead when this fires with `fatal: true`.
   */
  addListener(
    eventName: 'server-error',
    listener: (event: ServerErrorEvent) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  /** Remove every listener registered on this plugin. */
  removeAllListeners(): Promise<void>;
}
