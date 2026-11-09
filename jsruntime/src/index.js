// Ghostbrowser JavaScript runtime: lets a Chrome 61-era WebView run sites built for modern
// browsers. Ghostbrowser inserts this at the top of pages whose module scripts the engine
// can't run, and marks those scripts type="module-shim".
import './polyfills.js';
import './options.js';
import 'es-module-shims';

self.__ghostJs = true;
