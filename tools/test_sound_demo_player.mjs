import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const script = readFileSync(new URL('./sound_demo_player.js', import.meta.url), 'utf8');

function fixture(ok = true) {
  const button = {
    dataset: { src: 'fan-loop-test.wav?v=test' }, disabled: false,
    setAttribute(name, value) { this[name] = value; },
    addEventListener(_name, callback) { this.click = callback; },
  };
  const status = {};
  const sources = [];
  let requests = 0, decodes = 0;
  class AudioContext {
    destination = {};
    async resume() {}
    async decodeAudioData() { decodes++; return { duration: 20 }; }
    createBufferSource() {
      const source = {
        connect() {}, start() { this.started = true; },
        stop() { this.stopped = true; }, disconnect() {},
      };
      sources.push(source);
      return source;
    }
  }
  vm.runInNewContext(script, {
    AudioContext,
    document: { getElementById: id => id === 'fan-loop-button' ? button : status },
    fetch: async (_url, options) => {
      assert.equal(options.cache, 'no-store');
      requests++;
      return { ok, status: ok ? 200 : 404, arrayBuffer: async () => new ArrayBuffer(8) };
    },
  });
  return { button, status, sources, counts: () => ({ requests, decodes }) };
}

test('fan loops one decoded buffer and supports stop/restart without another request', async () => {
  const f = fixture();
  await f.button.click();
  assert.equal(f.sources[0].loop, true);
  assert.equal(f.sources[0].started, true);
  assert.equal(f.button['aria-pressed'], 'true');
  await f.button.click();
  assert.equal(f.sources[0].stopped, true);
  assert.equal(f.button['aria-pressed'], 'false');
  await f.button.click();
  assert.equal(f.sources[1].loop, true);
  assert.equal(f.sources[1].buffer, f.sources[0].buffer);
  assert.deepEqual(f.counts(), { requests: 1, decodes: 1 });
});

test('failed audio request leaves the player enabled with an error message', async () => {
  const f = fixture(false);
  await f.button.click();
  assert.equal(f.button.disabled, false);
  assert.equal(f.sources.length, 0);
  assert.match(f.status.textContent, /HTTP 404/);
});
