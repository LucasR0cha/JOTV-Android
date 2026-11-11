'use strict';
importScripts('runtime.js');
var requests = Object.create(null), nextRequest = 0, busy = false;
self.onmessage = function (event) {
  var message = event.data || {};
  if (message.type === 'response') {
    var waiter = requests[message.id];
    if (waiter) { delete requests[message.id]; if (message.error) waiter.reject(new Error('A rede do plugin não respondeu.')); else waiter.resolve(message.value); }
    return;
  }
  if (message.type !== 'run' || busy) return;
  busy = true;
  JotvPluginRuntime.run(message.input, {
    fetch: function (request) { return new Promise(function (resolve, reject) { var id = String(++nextRequest); requests[id] = { resolve: resolve, reject: reject }; self.postMessage({ type: 'fetch', id: id, request: request }); }); },
    cancel: function () { requests = Object.create(null); }
  }).then(function (value) { busy = false; self.postMessage({ type: 'result', value: value }); }, function (error) { busy = false; self.postMessage({ type: 'error', error: String(error.message || 'O plugin falhou.').slice(0, 240) }); });
};
