import assert from 'node:assert/strict';
import { readFile, readdir, access } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const namespace = 'emulator_i86_crafted';
const assets = resolve(root, 'src/main/resources/assets', namespace);
const readJson = async path => JSON.parse(await readFile(path, 'utf8'));
const localReference = async (reference, folder, suffix) => {
  assert.equal(typeof reference, 'string');
  if (!reference.startsWith(namespace + ':')) return;
  const relative = reference.slice(namespace.length + 1);
  assert.ok(!relative.includes('..'), 'Invalid reference: ' + reference);
  await access(resolve(assets, folder, relative + suffix));
};
const models = new Map();
for (const folder of ['models/block', 'models/item']) {
  for (const file of await readdir(resolve(assets, folder))) {
    const id = namespace + ':' + folder.slice(7) + '/' + file.replace(/\.json$/, '');
    models.set(id, await readJson(resolve(assets, folder, file)));
  }
}
function inherited(id, visiting = new Set()) {
  assert.ok(!visiting.has(id), 'Model parent cycle: ' + id);
  visiting.add(id);
  const model = models.get(id);
  assert.ok(model, 'Missing model: ' + id);
  const parent = model.parent?.startsWith(namespace + ':') ? inherited(model.parent, visiting) : {};
  return { ...parent, ...model, textures: { ...parent.textures, ...model.textures } };
}
for (const [id, model] of models) {
  if (model.parent) {
    if (model.parent.startsWith('minecraft:')) assert.equal(model.parent, 'minecraft:block/block');
    else await localReference(model.parent, 'models', '.json');
  }
  const resolved = inherited(id);
  const signPort = id.includes(':block/sign_port');
  const elements = (resolved.elements ?? []).map(element => {
    const [a, b] = [element.from, element.to];
    if (signPort && id.includes('sign_port_up'))
      return { ...element, from: [a[0], 16 - b[2], a[1]], to: [b[0], 16 - a[2], b[1]] };
    if (signPort && id.includes('sign_port_down'))
      return { ...element, from: [a[0], a[2], 16 - b[1]], to: [b[0], b[2], 16 - a[1]] };
    return element;
  });
  if (signPort) {
    assert.equal(resolved.textures.display, undefined, id + ': sign port has no screen');
    if (id.endsWith('_on')) {
      assert.equal(elements.length, 2, id + ': powered lights stay on the attachment face');
      assert.ok(elements.every(element => element.from[2] === 15.8 && element.to[2] === 15.95));
    }
  }
  if (/(reset_port|nmi_port|test_port)_active$/.test(id)) {
    assert.equal(resolved.elements?.length, 1, id + ': physical pin uses one steady segment');
    assert.equal(resolved.textures.light, namespace + ':block/signal_cyan');
    const pin = resolved.elements[0];
    assert.ok(Math.abs(pin.to[0] - pin.from[0] - 0.8) < 0.000001 && Math.abs(pin.to[1] - pin.from[1] - 0.8) < 0.000001, id + ': physical pin LED must match the 0.8-pixel segments');
  } else if (id.endsWith('_active') || id.endsWith('/super_structure_on'))
    assert.equal(resolved.elements?.length, 0, id + ': LEDs must use client activity rendering');
  else if (!id.endsWith('_on')) assert.ok(resolved.elements?.length, id + ': must use real geometry');
  if (id.includes(':block/') && id.endsWith('_off')) {
    const lamps = elements.filter(element => element.from[2] === (signPort ? 15.8 : 0.26) && element.to[2] === (signPort ? 15.95 : 0.4)
      && Math.abs(element.to[0] - element.from[0] - 0.8) < 0.000001
      && Math.abs(element.to[1] - element.from[1] - 0.8) < 0.000001);
    assert.equal(lamps.length, id.endsWith('/super_structure_off') ? 0 : 4, id + ': four 0.8x0.8 segments required, except filler');
    if (lamps.length) {
      const expected = signPort ? [6.4, 7.2, 8, 8.8] : [3, 3.8, 4.6, 5.4];
      assert.ok(lamps.map(element => element.from[0]).sort((a, b) => a - b).every((x, index) => Math.abs(x - expected[index]) < 0.000001), id + ': strip must be contiguous at its intended position');
      assert.ok(lamps.every(element => Math.abs(element.from[1] - (signPort ? 2.1 : 2.4)) < 0.000001));
    }
    if (signPort) {
      assert.deepEqual(resolved.elements[0].from, [0, 0, 0], id + ': plain casing replaces the display front');
      assert.deepEqual(resolved.elements[0].to, [16, 16, 16]);
      assert.ok(elements.slice(1).every(element => element.from[2] >= 15), id + ': details belong on the attachment face');
    }
  }
  for (const texture of Object.values(resolved.textures ?? {})) await localReference(texture, 'textures', '.png');
  for (const element of resolved.elements) {
    for (let axis = 0; axis < 3; axis++) {
      assert.ok(element.from[axis] >= 0 && element.to[axis] <= 16 && element.from[axis] < element.to[axis],
        id + ': invalid cuboid');
    }
    assert.ok(!element.light_emission || element.light_emission <= 15);
    for (const face of Object.values(element.faces)) {
      assert.ok(face.texture.startsWith('#') && resolved.textures[face.texture.slice(1)], id + ': missing face texture');
      assert.ok(face.uv.length === 4 && face.uv.every(value => value >= 0 && value <= 16), id + ': invalid UV');
    }
  }
}
let blockCount = 0;
for (const file of await readdir(resolve(assets, 'blockstates'))) {
  const id = file.replace(/\.json$/, '');
  const { multipart } = await readJson(resolve(assets, 'blockstates', file));
  assert.ok(multipart?.length, id + ': multipart model required');
  const facings = ['north', 'east', 'south', 'west', ...(['redstone_port', 'sign_port', 'keyboard'].includes(id) ? ['up', 'down'] : [])];
  for (const part of multipart) {
    await localReference(part.apply.model, 'models', '.json');
    assert.ok(Object.keys(part.when).every(key => ['facing', 'powered', 'active', 'input'].includes(key)));
    assert.ok(!part.apply.uvlock, id + ': front details rotate with the face');
  }
  for (const facing of facings) for (const powered of [false, true]) for (const active of [false, true]) {
    for (const input of (id === 'redstone_port' ? [false, true] : [true])) {
      const state = { facing, powered: String(powered), active: String(active), input: String(input) };
      const selected = multipart.filter(part => Object.entries(part.when).every(([key, value]) => state[key] === value));
      assert.equal(selected.length, 1 + Number(powered) + Number(active), id + ': incomplete/duplicate state ' + JSON.stringify(state));
      if (id !== 'sign_port') assert.ok(inherited(selected[0].apply.model).elements.length >= 16, id + ': missing housing/device geometry');
      const horizontal = ['north', 'east', 'south', 'west'].indexOf(facing) * 90;
      const rotation = id === 'sign_port'
        ? { y: facing === 'up' || facing === 'down' ? 0 : (horizontal + 180) % 360 }
        : facing === 'up' ? { x: 270 } : facing === 'down' ? { x: 90 } : { y: horizontal };
      if (id === 'sign_port') {
        const mount = facing === 'up' || facing === 'down' ? facing : 'wall';
        assert.ok(selected.every(part => part.apply.model.includes('sign_port_' + mount + '_')), 'Incorrect sign mount');
      }
      for (const part of selected) for (const [axis, degrees] of Object.entries(rotation)) {
        assert.equal(part.apply[axis], degrees, id + ': incorrect ' + facing + ' rotation');
      }
    }
  }
  await access(resolve(root, 'src/main/resources/data', namespace, 'loot_table/blocks', file));
  blockCount++;
}
const ledTexture = await readFile(resolve(assets, 'textures/block/led.png'));
assert.equal(ledTexture.readUInt32BE(16), 16, 'LED texture must retain atlas mipmaps');
assert.equal(ledTexture.readUInt32BE(20), 16, 'LED texture must retain atlas mipmaps');
let animations = 0;
for (const file of await readdir(resolve(assets, 'textures/block'))) {
  if (!file.endsWith('.png.mcmeta')) continue;
  const { animation } = await readJson(resolve(assets, 'textures/block', file));
  const bytes = await readFile(resolve(assets, 'textures/block', file.replace(/\.mcmeta$/, '')));
  assert.deepEqual(bytes.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  assert.equal(bytes.readUInt32BE(16), animation.width);
  assert.equal(bytes.readUInt32BE(20), animation.height * 16);
  assert.equal(animation.interpolate, true, file + ': must interpolate smoothly');
  assert.ok(animation.frametime >= 3, file + ': animation should be restrained');
  animations++;
}
assert.equal(animations, 10);
const controller = inherited(namespace + ':block/mainframe_controller_on');
const terminal = inherited(namespace + ':block/access_terminal_on');
assert.notEqual(controller.textures.display, terminal.textures.display, 'CPU core must differ from terminal glass');
const screenArea = model => Math.max(...model.elements
  .filter(element => element.faces.north?.texture === '#display')
  .map(element => (element.to[0] - element.from[0]) * (element.to[1] - element.from[1])));
assert.ok(screenArea(terminal) >= screenArea(controller) * 3, 'Terminal and controller silhouettes must differ');
const names = await readJson(resolve(assets, 'lang/en_us.json'));
const items = await readdir(resolve(assets, 'items'));
async function itemReference(definition) {
  if (definition.type === 'minecraft:condition') {
    assert.equal(definition.property, 'minecraft:has_component');
    assert.equal(definition.component, namespace + ':rom_id');
    await itemReference(definition.on_true);
    await itemReference(definition.on_false);
  } else {
    assert.equal(definition.type, 'minecraft:model');
    await localReference(definition.model, 'models', '.json');
    assert.ok(inherited(definition.model).elements.length, 'Item must be three dimensional');
  }
}
for (const file of items) {
  await itemReference((await readJson(resolve(assets, 'items', file))).model);
  const id = file.replace(/\.json$/, '');
  assert.ok(names['item.' + namespace + '.' + id] || names['block.' + namespace + '.' + id], 'Missing name: ' + id);
}
const carried = new Map([
  ['mainframe_controller', ['emulator_i86_crafted:mainframe_id', 'minecraft:container', 'minecraft:custom_name']],
  ['harddisk_array', ['emulator_i86_crafted:disk_id', 'emulator_i86_crafted:disk_contents', 'minecraft:custom_name']],
  ['ender_tunnel_array', ['emulator_i86_crafted:memory_id', 'minecraft:custom_name']],
]);
for (const [id, expected] of carried) {
  const loot = await readJson(resolve(root, 'src/main/resources/data', namespace, 'loot_table/blocks', id + '.json'));
  const copy = loot.pools[0].entries[0].functions?.find(entry => entry.function === 'minecraft:copy_components');
  assert.ok(copy, id + ': missing portable block data');
  assert.equal(copy.source, 'block_entity');
  assert.deepEqual(new Set(copy.include), new Set(expected), id + ': portable components');
}
console.log('Resource checks passed: ' + blockCount + ' 3D blocks, ' + items.length + ' 3D items, ' + animations
  + ' interpolated textures; all states, model references and portable drops');
