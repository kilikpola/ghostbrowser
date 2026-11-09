// Small Intl polyfills for APIs newer than Chrome 61 that i18n libraries (vue-i18n etc.) call.
// Plural categories follow CLDR for the common language families; others fall back to one/other.

function lang(locales) {
  var l = Array.isArray(locales) ? locales[0] : locales;
  return String(l || (navigator.language || 'en')).toLowerCase().split(/[-_]/)[0];
}

function cardinal(lg, n) {
  var i = Math.floor(Math.abs(n)), int = n === i, m10 = i % 10, m100 = i % 100;
  switch (lg) {
    case 'ja': case 'zh': case 'ko': case 'vi': case 'th': case 'id': case 'ms': case 'tr': case 'fa': case 'my': case 'km': case 'lo':
      return 'other';
    case 'fr': case 'hy': case 'kab':
      return i === 0 || i === 1 ? 'one' : 'other';
    case 'ru': case 'uk': case 'be': case 'sr': case 'hr': case 'bs':
      if (!int) return 'other';
      if (m10 === 1 && m100 !== 11) return 'one';
      if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return 'few';
      return 'many';
    case 'pl':
      if (!int) return 'other';
      if (i === 1) return 'one';
      if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return 'few';
      return 'many';
    case 'cs': case 'sk':
      if (!int) return 'many';
      return i === 1 ? 'one' : i >= 2 && i <= 4 ? 'few' : 'other';
    case 'ar':
      if (n === 0) return 'zero';
      if (n === 1) return 'one';
      if (n === 2) return 'two';
      if (m100 >= 3 && m100 <= 10) return 'few';
      if (m100 >= 11) return 'many';
      return 'other';
    case 'he':
      return n === 1 ? 'one' : n === 2 ? 'two' : 'other';
    default:
      return n === 1 && int ? 'one' : 'other';
  }
}

function ordinal(lg, n) {
  if (lg !== 'en') return 'other';
  var m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return 'one';
  if (m10 === 2 && m100 !== 12) return 'two';
  if (m10 === 3 && m100 !== 13) return 'few';
  return 'other';
}

if (typeof Intl !== 'undefined' && !Intl.PluralRules) {
  var PluralRules = function (locales, options) {
    if (!(this instanceof PluralRules)) throw new TypeError("Constructor Intl.PluralRules requires 'new'");
    this._lang = lang(locales);
    this._type = (options && options.type) || 'cardinal';
    this._locale = Array.isArray(locales) ? locales[0] || 'en' : locales || 'en';
  };
  PluralRules.prototype.select = function (n) {
    n = Number(n);
    return this._type === 'ordinal' ? ordinal(this._lang, n) : cardinal(this._lang, n);
  };
  PluralRules.prototype.resolvedOptions = function () {
    return { locale: this._locale, type: this._type, pluralCategories: ['zero', 'one', 'two', 'few', 'many', 'other'] };
  };
  PluralRules.supportedLocalesOf = function (l) { return [].concat(l || []); };
  Intl.PluralRules = PluralRules;
}

if (typeof Intl !== 'undefined' && !Intl.RelativeTimeFormat) {
  var RelativeTimeFormat = function (locales, options) {
    this._numeric = (options && options.numeric) || 'always';
    this._locale = Array.isArray(locales) ? locales[0] || 'en' : locales || 'en';
  };
  RelativeTimeFormat.prototype.format = function (value, unit) {
    unit = String(unit).replace(/s$/, '');
    var v = Number(value), abs = Math.abs(v);
    if (this._numeric === 'auto') {
      if (v === 0 && unit === 'day') return 'today';
      if (v === 1 && unit === 'day') return 'tomorrow';
      if (v === -1 && unit === 'day') return 'yesterday';
      if (v === 0) return 'this ' + unit;
    }
    var s = abs + ' ' + unit + (abs === 1 ? '' : 's');
    return v < 0 || (v === 0 && 1 / v < 0) ? s + ' ago' : 'in ' + s;
  };
  RelativeTimeFormat.prototype.formatToParts = function (value, unit) { return [{ type: 'literal', value: this.format(value, unit) }]; };
  RelativeTimeFormat.prototype.resolvedOptions = function () { return { locale: this._locale, numeric: this._numeric, style: 'long' }; };
  RelativeTimeFormat.supportedLocalesOf = function (l) { return [].concat(l || []); };
  Intl.RelativeTimeFormat = RelativeTimeFormat;
}

if (typeof Intl !== 'undefined' && !Intl.ListFormat) {
  var ListFormat = function (locales, options) { this._type = (options && options.type) || 'conjunction'; };
  ListFormat.prototype.format = function (list) {
    var a = Array.prototype.slice.call(list).map(String), word = this._type === 'disjunction' ? 'or' : 'and';
    if (a.length < 2) return a.join('');
    if (a.length === 2) return a[0] + ' ' + word + ' ' + a[1];
    return a.slice(0, -1).join(', ') + ', ' + word + ' ' + a[a.length - 1];
  };
  ListFormat.supportedLocalesOf = function (l) { return [].concat(l || []); };
  Intl.ListFormat = ListFormat;
}
