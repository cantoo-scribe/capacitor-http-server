import { registerPlugin } from '@capacitor/core';

import type { HttpServerPlugin } from './definitions';

const HttpServer = registerPlugin<HttpServerPlugin>('HttpServer', {
  web: () => import('./web').then((m) => new m.HttpServerWeb()),
});

export * from './definitions';
export { HttpServer };
