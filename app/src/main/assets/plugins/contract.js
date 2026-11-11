(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.JotvPluginContract = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  var BAD_KEY = /^(?:__proto__|prototype|constructor)$/;
  function text(value, limit) { return typeof value === 'string' ? value.replace(/[\x00-\x08\x0b\x0c\x0e-\x1f]/g, '').slice(0, limit || 300) : ''; }
  function fail(message) { throw new Error(message); }
  function publicUrl(value) {
    if (typeof value !== 'string' || value.length > 8192 || /[\s\x00-\x1f]/.test(value)) return '';
    var m = /^https?:\/\/([^/?#]+)(?:[/?#]|$)/i.exec(value);
    if (!m || /@/.test(m[1])) return '';
    var host = m[1].replace(/:\d+$/, '').toLowerCase().replace(/\.$/, '');
    if (!host || /[:\[\]\\]/.test(host) || /(^localhost$|\.localhost$|\.local$|\.internal$)/.test(host)) return '';
    if (/^(0x[\da-f]+|\d+)$/i.test(host)) return '';
    if (/^\d+\./.test(host)) {
      var p = host.split('.').map(Number);
      if (p.length !== 4 || p.some(function (x) { return x < 0 || x > 255 || Math.floor(x) !== x; })) return '';
      if (p[0] === 0 || p[0] === 10 || p[0] === 127 || p[0] >= 224 ||
          (p[0] === 169 && p[1] === 254) || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) ||
          (p[0] === 192 && (p[1] === 168 || p[1] === 0 || p[1] === 2)) ||
          (p[0] === 100 && p[1] >= 64 && p[1] <= 127) ||
          (p[0] === 198 && (p[1] === 18 || p[1] === 19 || p[1] === 51)) || (p[0] === 203 && p[1] === 0 && p[2] === 113)) return '';
    }
    return value;
  }
  function normalize(input) {
    var value = text(input, 8192).trim();
    if (/^stremio:\/\//i.test(value)) value = value.replace(/^stremio:/i, 'https:');
    if (!/^[a-z][a-z\d+.-]*:\/\//i.test(value)) value = 'https://' + value;
    var gh = /^https?:\/\/(?:www\.)?github\.com\/([^/?#]+)\/([^/?#]+)(.*)$/i.exec(value);
    if (gh) {
      var tail = gh[3].split(/[?#]/)[0].replace(/\/+$/, '');
      if (!tail || tail === '/') tail = '/main/manifest.json';
      else if (/^\/(blob|raw)\//.test(tail)) tail = tail.replace(/^\/(blob|raw)\//, '/');
      else if (/^\/tree\//.test(tail)) tail = tail.replace(/^\/tree\//, '/') + '/manifest.json';
      else fail('Cole o link do repositório ou de um arquivo JSON ou JavaScript.');
      value = 'https://raw.githubusercontent.com/' + gh[1] + '/' + gh[2].replace(/\.git$/, '') + tail;
    } else {
      value = value.replace(/#.*$/, '');
      if (!/\.(?:json|js)(?:\?|$)/i.test(value)) value = value.replace(/\/+$/, '') + '/manifest.json';
    }
    return publicUrl(value) || fail('Use um endereço HTTP ou HTTPS público válido.');
  }
  function headers(raw) {
    var out = Object.create(null);
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out;
    Object.keys(raw).slice(0, 32).forEach(function (key) {
      if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$/.test(key) || BAD_KEY.test(key) || /^(host|content-length|connection|transfer-encoding|accept-encoding)$/i.test(key)) return;
      var v = raw[key];
      if (typeof v === 'string' && v.length <= 8192 && !/[\r\n\x00]/.test(v)) out[key] = v;
    });
    return out;
  }
  function strings(values, fallback) {
    return (Array.isArray(values) ? values : fallback || []).map(function (x) { return text(x, 80); }).filter(Boolean).slice(0, 24);
  }
  function resolve(base, path) {
    if (publicUrl(path)) return path;
    if (typeof path !== 'string' || !path || /^(?:[a-z]+:|\/\/)/i.test(path) || /[\\\x00-\x20]/.test(path)) return '';
    var origin = /^(https?:\/\/[^/]+)\//i.exec(base);
    if (!origin) return '';
    var joined = path.charAt(0) === '/' ? origin[1] + path : base.split(/[?#]/)[0].replace(/[^/]*$/, '') + path;
    var parts = joined.slice(origin[1].length).split('/'), clean = [];
    parts.forEach(function (p) { if (p === '..') clean.pop(); else if (p !== '.') clean.push(p); });
    return publicUrl(origin[1] + clean.join('/'));
  }
  function repository(raw, url) {
    url = normalize(url);
    if (/\.js(?:\?|$)/i.test(url)) return { name: decodeURIComponent(url.split('/').pop().split('?')[0]).replace(/\.js$/i, ''), version: '', description: 'Provedor JavaScript individual', kind: 'script', url: url, providers: [provider({ id: 'script', name: url.split('/').pop().split('?')[0], filename: url }, url, 0)] };
    if (typeof raw === 'string') { if (raw.length > 1024 * 1024) fail('O manifesto ultrapassou o tamanho permitido.'); try { raw = JSON.parse(raw); } catch (e) { fail('O endereço não retornou um manifesto JSON válido.'); } }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) fail('O manifesto não descreve um repositório compatível.');
    var entries = raw.scrapers || raw.providers;
    if (!Array.isArray(entries) && Array.isArray(raw.plugins)) {
      entries = raw.plugins;
      if (entries.some(function (p) { return /\.cs3(?:[?#]|$)/i.test(String(p.url || '')); })) fail('Este repositório usa extensões nativas CS3. Use um repositório Nuvio JavaScript ou um addon Stremio.');
    }
    if (Array.isArray(entries)) {
      if (!entries.length || entries.length > 300) fail('O repositório deve ter entre 1 e 300 provedores.');
      var seen = Object.create(null);
      var providers = entries.map(function (p, i) {
        var item = provider(p, url, i);
        if (seen[item.id]) fail('O manifesto repete um identificador de provedor.');
        seen[item.id] = true; return item;
      });
      return { name: text(raw.name, 160) || 'Repositório de plugins', version: text(raw.version, 80), description: text(raw.description, 1400), author: text(raw.author, 160), logo: publicUrl(raw.logo || raw.icon || ''), kind: 'nuvio', url: url, providers: providers };
    }
    if (text(raw.id, 200) && Array.isArray(raw.resources) && raw.resources.length) return { name: text(raw.name, 160) || text(raw.id, 160), version: text(raw.version, 80), description: text(raw.description, 1400), kind: 'stremio', url: url, id: text(raw.id, 200), types: strings(raw.types), configurable: !!(raw.behaviorHints || {}).configurable, providers: [] };
    fail('Formato não reconhecido. Use um manifesto Nuvio, um addon Stremio ou um arquivo JavaScript.');
  }
  function provider(raw, base, index) {
    if (!raw || typeof raw !== 'object') fail('O manifesto contém um provedor inválido.');
    var id = text(raw.id || raw.internalName || ('provider' + index), 180);
    if (!id || BAD_KEY.test(id) || /[\x00-\x20]/.test(id)) fail('Identificador de provedor inválido.');
    var url = resolve(base, raw.filename || raw.script || raw.url || raw.src || '');
    if (!url || !/\.js(?:[?#]|$)/i.test(url)) fail('O provedor ' + id + ' não tem um arquivo JavaScript válido.');
    var integrity = text(raw.sha256 || raw.integrity, 200).replace(/^sha256[:-]/i, '').toLowerCase();
    if (integrity && !/^[a-f\d]{64}$/.test(integrity)) fail('O checksum do provedor ' + id + ' não é SHA-256 válido.');
    return { id: id, name: text(raw.name, 160) || id, description: text(raw.description, 1400), version: text(String(raw.version || ''), 80), url: url,
      types: strings(raw.supportedTypes || raw.types, ['movie', 'tv']), languages: strings(raw.contentLanguage || raw.languages), formats: strings(raw.formats),
      available: raw.enabled !== false && raw.status !== 0, hasSettings: raw.hasSettings !== false, logo: publicUrl(raw.logo || raw.icon || ''), integrity: integrity };
  }
  function fields(raw) {
    var list = Array.isArray(raw) ? raw : raw && (raw.fields || raw.settings || raw.options);
    if (!Array.isArray(list)) return [];
    return list.slice(0, 100).map(function (f) {
      if (!f || typeof f !== 'object') return null;
      var type = String(f.type || 'text').toLowerCase(), key = text(f.key || f.id || '', 100);
      if (type === 'boolean' || type === 'checkbox' || type === 'switch') type = 'toggle';
      if (type === 'input' || type === 'string') type = 'text';
      if (type === 'password') { type = 'text'; f = Object.assign({}, f, { isPassword: true }); }
      if (type === 'separator' || type === 'label' || type === 'description') type = 'header';
      if (type !== 'header' && (!key || BAD_KEY.test(key))) return null;
      return { type: type, key: key, label: text(f.label || f.title || key, 240), description: text(f.description || f.help, 1000), secret: !!(f.isPassword || f.secret || /password|token|secret|session|apikey|api_key/i.test(key)),
        required: !!f.required, defaultValue: f.defaultValue === undefined ? f.default : f.defaultValue,
        min: isFinite(f.min) ? Number(f.min) : undefined, max: isFinite(f.max) ? Number(f.max) : undefined, step: Number(f.step) || 1,
        options: (Array.isArray(f.options) ? f.options : []).slice(0, 150).map(function (o) { return typeof o === 'object' && o ? { label: text(o.label || o.title || String(o.value), 240), value: o.value } : { label: text(String(o), 240), value: o }; }) };
    }).filter(Boolean);
  }
  function settings(schema, values) {
    var out = Object.create(null);
    schema.forEach(function (f) {
      if (!f.key || f.type === 'header') return;
      var v = values[f.key] === undefined ? f.defaultValue : values[f.key];
      if (f.type === 'toggle') v = v === true || v === 'true';
      else if (f.type === 'number' || f.type === 'slider') {
        if (v === '' || v === undefined) v = f.min === undefined ? 0 : f.min;
        v = Number(v); if (!isFinite(v) || (f.min !== undefined && v < f.min) || (f.max !== undefined && v > f.max)) fail('Revise o valor de ' + f.label + '.');
      } else if (f.type === 'select') {
        if (v === undefined && f.options.length) v = f.options[0].value;
        if (!f.options.some(function (o) { return String(o.value) === String(v); })) fail('Escolha uma opção em ' + f.label + '.');
      } else if (f.type === 'multiselect') {
        v = Array.isArray(v) ? v : []; if (v.some(function (x) { return !f.options.some(function (o) { return String(o.value) === String(x); }); })) fail('Revise as opções de ' + f.label + '.');
      } else { v = v === undefined || v === null ? '' : String(v); if (v.length > 16384) fail('O campo ' + f.label + ' está muito longo.'); }
      if (f.required && (v === '' || v === undefined)) fail('Preencha ' + f.label + '.');
      out[f.key] = v;
    });
    if (JSON.stringify(out).length > 32768) fail('As configurações ultrapassaram o tamanho permitido.');
    return out;
  }
  function streams(raw, name) {
    var list = Array.isArray(raw) ? raw : raw && raw.streams;
    if (!Array.isArray(list)) return [];
    var seen = Object.create(null);
    return list.slice(0, 160).map(function (s) {
      if (!s || typeof s !== 'object') return null;
      var url = publicUrl(s.url || s.file || ''); if (!url) return null;
      var requestHeaders = headers(s.headers || (((s.behaviorHints || {}).proxyHeaders || {}).request));
      var key = url + '\n' + JSON.stringify(requestHeaders); if (seen[key]) return null; seen[key] = true;
      var label = text(s.name || s.title || name, 240), desc = text([s.description, s.quality, s.language].filter(Boolean).join(' · '), 1400);
      var match = /\b(2160|1440|1080|720|480|360|240)p?\b/i.exec(String(s.quality || '') + ' ' + label + ' ' + desc);
      var rank = match ? Number(match[1]) : /\b4k\b/i.test(label + desc) ? 2160 : /\b(?:fhd|full.?hd)\b/i.test(label + desc) ? 1080 : 0;
      return { url: url, label: label || name, description: desc, qualityRank: rank, headers: requestHeaders, type: text(s.type, 32), language: text(s.language, 80),
        subtitles: (Array.isArray(s.subtitles) ? s.subtitles : []).slice(0, 40).map(function (sub) { var u = publicUrl(sub.url || sub.file || ''); return u ? { url: u, language: text(sub.language || sub.lang, 80), label: text(sub.name || sub.label || sub.language || 'Legenda', 160), headers: headers(sub.headers) } : null; }).filter(Boolean) };
    }).filter(Boolean).slice(0, 80).sort(function (a, b) { return b.qualityRank - a.qualityRank; });
  }
  function subtitles(body) {
    if (typeof body !== 'string' || body.length > 2 * 1024 * 1024) return [];
    function seconds(value) { var parts = value.replace(',', '.').split(':').map(Number); return parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : parts.length === 2 ? parts[0] * 60 + parts[1] : NaN; }
    return body.replace(/^\ufeff/, '').split(/\r?\n\s*\r?\n/).slice(0, 25000).map(function (block) {
      var lines = block.split(/\r?\n/), index = -1;
      for (var i = 0; i < Math.min(lines.length, 3); i++) if (lines[i].indexOf('-->') >= 0) { index = i; break; }
      if (index < 0) return null;
      var range = /^\s*([\d:.,]+)\s*-->\s*([\d:.,]+)/.exec(lines[index]);
      if (!range) return null;
      var start = seconds(range[1]), end = seconds(range[2]);
      var value = lines.slice(index + 1).join('\n').replace(/<[^>]*>/g, '').replace(/\{\\[^}]*\}/g, '').slice(0, 4000).trim();
      return isFinite(start) && isFinite(end) && start >= 0 && end > start && value ? { start: start, end: end, text: value } : null;
    }).filter(Boolean).sort(function (a, b) { return a.start - b.start; });
  }
  return { text: text, publicUrl: publicUrl, normalize: normalize, resolve: resolve, headers: headers, repository: repository, fields: fields, settings: settings, streams: streams, subtitles: subtitles };
}));
