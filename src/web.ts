import { WebPlugin } from '@capacitor/core';
import type { PluginListenerHandle } from '@capacitor/core';

import type {
  HttpRequestEvent,
  HttpResponse,
  HttpServerPlugin,
  ServerErrorEvent,
  StartOptions,
  StartResult,
} from './definitions';

const UNAVAILABLE_MESSAGE = 'HTTP server is only available on iOS and Android.';

export class HttpServerWeb extends WebPlugin implements HttpServerPlugin {
  async start(_options?: StartOptions): Promise<StartResult> {
    throw this.unavailable(UNAVAILABLE_MESSAGE);
  }

  async stop(): Promise<void> {
    throw this.unavailable(UNAVAILABLE_MESSAGE);
  }

  async respond(_response: HttpResponse): Promise<void> {
    throw this.unavailable(UNAVAILABLE_MESSAGE);
  }

  addListener(
    eventName: 'request',
    listener: (event: HttpRequestEvent) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(
    eventName: 'server-error',
    listener: (event: ServerErrorEvent) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
  addListener(
    eventName: string,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    listener: (event: any) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle {
    // Capacitor 6 returns a bare `Promise<PluginListenerHandle>` from the
    // base WebPlugin, while the public interface (specified in definitions)
    // uses the legacy `Promise & PluginListenerHandle` intersection. Cast
    // explicitly so the web stub is interchangeable with the native
    // implementations regardless of the installed Capacitor version.
    return super.addListener(eventName, listener) as Promise<PluginListenerHandle> & PluginListenerHandle;
  }
}
