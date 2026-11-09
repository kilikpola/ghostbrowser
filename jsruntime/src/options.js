// Must evaluate before es-module-shims, which reads window.esmsInitOptions once at startup.
import { convertModule } from './convert.js';

self.esmsInitOptions = {
  shimMode: true,              // only <script type="module-shim"> (rewritten by Ghostbrowser) is handled
  noLoadEventRetriggers: true,
  fetch: convertModule,
  onerror: function (e) { console.error('Ghostbrowser: module failed', e && (e.stack || e.message || e)); }
};
