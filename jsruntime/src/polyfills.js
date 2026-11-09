// Runtime features that old engines lack and modern bundles assume (checked against solvely.ai).
import 'core-js/actual/global-this';
import 'core-js/actual/promise/finally';
import 'core-js/actual/promise/all-settled';
import 'core-js/actual/promise/any';
import 'core-js/actual/object/from-entries';
import 'core-js/actual/object/has-own';
import 'core-js/actual/array/flat';
import 'core-js/actual/array/flat-map';
import 'core-js/actual/array/at';
import 'core-js/actual/array/find-last';
import 'core-js/actual/array/find-last-index';
import 'core-js/actual/string/match-all';
import 'core-js/actual/string/replace-all';
import 'core-js/actual/string/trim-start';
import 'core-js/actual/string/trim-end';
import 'core-js/actual/string/at';
import 'core-js/actual/symbol/async-iterator';
import 'core-js/actual/queue-microtask';
import 'core-js/actual/structured-clone';
import 'abortcontroller-polyfill/dist/abortcontroller-polyfill-only';
import { ResizeObserver } from '@juggle/resize-observer';
import './intl.js';

if (!self.ResizeObserver) self.ResizeObserver = ResizeObserver;
