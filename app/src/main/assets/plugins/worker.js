'use strict';
importScripts('runtime.js');
var requests = Object.create(null), jobs = Object.create(null), nextRequest = 0;
self.onmessage = function (event) {
  var message = event.data || {};
  if (message.type === 'response') {
    var waiter = requests[message.id];
    if (waiter && waiter.job === message.job) { delete requests[message.id]; if (message.error) waiter.reject(new Error('A rede do plugin não respondeu.')); else waiter.resolve(message.value); }
    return;
  }
  if (message.type === 'cancel') { var pending = jobs[message.job]; if (pending) { pending.cancelled = true; if (pending.cancel) pending.cancel(); } return; }
  if (message.type !== 'run' || jobs[message.job]) return;
  var job = { cancelled: false, cancel: null }, jobId = String(message.job); jobs[jobId] = job;
  function cleanup() { delete jobs[jobId]; Object.keys(requests).forEach(function (id) { if (requests[id].job === jobId) delete requests[id]; }); }
  JotvPluginRuntime.run(message.input, {
    fetch: function (request) { return new Promise(function (resolve, reject) { var id = String(++nextRequest); requests[id] = { job: jobId, resolve: resolve, reject: reject }; self.postMessage({ type: 'fetch', job: jobId, id: id, request: request }); }); },
    onCancel: function (callback) { job.cancel = callback; if (job.cancelled) callback(); },
    cancel: cleanup
  }).then(function (value) { cleanup(); self.postMessage({ type: 'result', job: jobId, value: value }); }, function (error) { cleanup(); self.postMessage({ type: 'error', job: jobId, cancelled: job.cancelled, error: String(error.message || 'O plugin falhou.').slice(0, 240) }); });
};
