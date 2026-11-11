(function (root) {
  'use strict';
  function cancelled() { var error = new Error('Pedido cancelado.'); error.code = 'PLUGIN_CANCELLED'; return error; }
  function create(options) {
    var slots = [], next = 0, starts = 0;
    var maximum = options.workers || 2, capacity = options.capacity || 16;
    function close(slot, error) {
      clearTimeout(slot.idle); slot.worker.terminate();
      var index = slots.indexOf(slot); if (index >= 0) slots.splice(index, 1);
      Object.keys(slot.jobs).forEach(function (id) { finish(slot.jobs[id], error || cancelled()); });
    }
    function finish(job, error, value) {
      if (job.done) return; job.done = true; clearTimeout(job.timer);
      delete job.slot.jobs[job.id]; job.slot.count--;
      if (job.host.cancel) job.host.cancel();
      if (error) job.reject(error); else job.resolve(value);
      if (!job.slot.count && slots.indexOf(job.slot) >= 0) job.slot.idle = setTimeout(function () { close(job.slot); }, options.idle || 60000);
    }
    function startSlot() {
      var worker = new Worker(options.url), slot = { worker: worker, count: 0, jobs: Object.create(null), idle: null };
      slots.push(slot); starts++;
      worker.onerror = function () { close(slot, new Error('O motor de plugins foi interrompido.')); };
      worker.onmessage = function (event) {
        var data = event.data || {}, job = slot.jobs[data.job]; if (!job || job.done) return;
        if (data.type === 'fetch') Promise.resolve().then(function () { return job.host.fetch(data.request || {}, data.id); }).then(function (value) {
          if (!job.done) worker.postMessage({ type: 'response', job: job.id, id: data.id, value: value });
        }, function () { if (!job.done) worker.postMessage({ type: 'response', job: job.id, id: data.id, error: true }); });
        else if (data.type === 'result') finish(job, null, data.value);
        else if (data.type === 'error') { var error = new Error(String(data.error || 'O plugin falhou.').slice(0, 240)); if (data.cancelled) error.code = 'PLUGIN_CANCELLED'; finish(job, error); }
      };
      return slot;
    }
    function run(input, host) {
      var job, promise = new Promise(function (resolve, reject) {
        var available = slots.filter(function (slot) { return slot.count < capacity; }).sort(function (a, b) { return a.count - b.count; });
        var slot = available[0];
        if (slots.length < maximum && (!slot || slot.count > 0)) { try { slot = startSlot(); } catch (e) { reject(new Error('O motor de plugins não pôde iniciar.')); return; } }
        if (!slot) { reject(new Error('O limite de execuções de plugins foi atingido.')); return; }
        clearTimeout(slot.idle);
        var id = String(++next);
        job = { id: id, slot: slot, host: host, resolve: resolve, reject: reject, done: false };
        slot.jobs[id] = job; slot.count++;
        job.timer = setTimeout(function () { close(slot, new Error('O plugin excedeu o tempo de execução.')); }, Math.max(1000, Math.min(120000, Number(input.timeout) || 45000)) + 10000);
        slot.worker.postMessage({ type: 'run', job: id, input: input });
      });
      promise.cancel = function () { if (job && !job.done) { job.slot.worker.postMessage({ type: 'cancel', job: job.id }); finish(job, cancelled()); } };
      return promise;
    }
    return { run: run, cancelAll: function () { slots.slice().forEach(function (slot) { close(slot, cancelled()); }); }, stats: function () { return { workers: slots.length, starts: starts, jobs: slots.reduce(function (sum, slot) { return sum + slot.count; }, 0) }; } };
  }
  root.JotvPluginPool = { create: create };
}(typeof self !== 'undefined' ? self : this));
