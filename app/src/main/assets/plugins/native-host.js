(function () {
  'use strict';
  var jobs = Object.create(null), parts = Object.create(null);
  function send(data) { JotvPluginBridge.postMessage(JSON.stringify(data)); }
  window.JotvPluginHost = {
    run: function (id, input) {
      var worker = new Worker('worker.js'); jobs[id] = worker;
      worker.onmessage = function (event) {
        var data = event.data || {};
        if (data.type === 'fetch') send({ job: id, type: 'fetch', id: data.id, request: data.request });
        else if (data.type === 'result' || data.type === 'error') { send({ job: id, type: data.type, value: data.value, error: data.error }); worker.terminate(); delete jobs[id]; }
      };
      worker.onerror = function () { send({ job: id, type: 'error', error: 'O motor de plugins foi interrompido.' }); worker.terminate(); delete jobs[id]; };
      worker.postMessage({ type: 'run', input: input });
    },
    part: function (job, id, piece, last) {
      var key = job + ':' + id;
      parts[key] = (parts[key] || '') + piece;
      if (last) { var value = parts[key]; delete parts[key]; if (jobs[job]) { try { jobs[job].postMessage(JSON.parse(value)); } catch (e) {} } }
    },
    cancel: function (id) { if (jobs[id]) { jobs[id].terminate(); delete jobs[id]; } Object.keys(parts).forEach(function (key) { if (key.indexOf(id + ':') === 0) delete parts[key]; }); }
  };
}());
