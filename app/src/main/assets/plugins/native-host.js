(function () {
  'use strict';
  var jobs = Object.create(null), parts = Object.create(null), waiters = Object.create(null);
  var pool = JotvPluginPool.create({ url: 'worker.js', workers: 2, capacity: 16 });
  function send(data) { JotvPluginBridge.postMessage(JSON.stringify(data)); }
  window.JotvPluginHost = {
    run: function (id, input) {
      var task = pool.run(input, { fetch: function (request, requestId) { return new Promise(function (resolve, reject) { waiters[id + ':' + requestId] = { resolve: resolve, reject: reject }; send({ job: id, type: 'fetch', id: requestId, request: request }); }); } });
      jobs[id] = task;
      task.then(function (value) { if (jobs[id] === task) { delete jobs[id]; send({ job: id, type: 'result', value: value }); } }, function (error) { if (jobs[id] === task) { delete jobs[id]; send({ job: id, type: 'error', error: error.message }); } });
    },
    part: function (job, id, piece, last) {
      var key = job + ':' + id;
      parts[key] = (parts[key] || '') + piece;
      if (last) { var value = parts[key], waiter = waiters[key]; delete parts[key]; delete waiters[key]; if (waiter && jobs[job]) { try { var response = JSON.parse(value); if (response.error) waiter.reject(new Error('A rede do plugin não respondeu.')); else waiter.resolve(response.value); } catch (e) { waiter.reject(e); } } }
    },
    cancel: function (id) { if (jobs[id]) { var task = jobs[id]; delete jobs[id]; task.cancel(); } Object.keys(parts).forEach(function (key) { if (key.indexOf(id + ':') === 0) delete parts[key]; }); Object.keys(waiters).forEach(function (key) { if (key.indexOf(id + ':') === 0) { waiters[key].reject(new Error('Pedido cancelado.')); delete waiters[key]; } }); },
    clear: function () { Object.keys(jobs).forEach(function (id) { JotvPluginHost.cancel(id); }); pool.cancelAll(); },
    stats: function () { return pool.stats(); }
  };
}());
