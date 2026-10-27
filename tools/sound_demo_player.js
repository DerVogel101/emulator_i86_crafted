const button = document.getElementById('fan-loop-button');
const status = document.getElementById('fan-loop-status');
let context, buffer, source;

button.addEventListener('click', async () => {
  if (source) {
    source.stop();
    source.disconnect();
    source = null;
    button.textContent = 'Play fan continuously';
    button.setAttribute('aria-pressed', 'false');
    status.textContent = 'Stopped.';
    return;
  }
  button.disabled = true;
  try {
    context ??= new AudioContext();
    await context.resume();
    if (!buffer) {
      const response = await fetch(button.dataset.src, { cache: 'no-store' });
      if (!response.ok) throw new Error(`Audio request failed: HTTP ${response.status}`);
      buffer = await context.decodeAudioData(await response.arrayBuffer());
    }
    source = context.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    source.connect(context.destination);
    source.start();
    button.textContent = 'Stop fan';
    button.setAttribute('aria-pressed', 'true');
    status.textContent = 'Playing continuously from one decoded buffer.';
  } catch (error) {
    source?.disconnect();
    source = null;
    status.textContent = `Cannot play fan: ${error.message}`;
  } finally {
    button.disabled = false;
  }
});
