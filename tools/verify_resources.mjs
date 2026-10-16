import assert from 'node:assert/strict';
import { readFile, readdir, access } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const namespace = 'emulator_i86_crafted';
const assets = resolve(root, 'src/main/resources/assets', namespace);
const vanillaParents = new Set(['minecraft:block/orientable', 'minecraft:item/generated']);
const readJson = async path => JSON.parse(await readFile(path, 'utf8'));
const pngSize = async path => {
  const bytes = await readFile(path);
  assert.deepEqual(bytes.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  assert.equal(bytes.toString('ascii', 12, 16), 'IHDR');
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
};
const localReference = async (reference, folder, suffix) => {
  if (!reference.startsWith(`${namespace}:`)) return;
  const relative = reference.slice(namespace.length + 1);
  assert.ok(!relative.includes('..'), `Invalid resource reference: ${reference}`);
  await access(resolve(assets, folder, relative + suffix));
};

let blockCount = 0;
for (const file of await readdir(resolve(assets, 'blockstates'))) {
  const id = file.replace(/\.json$/, '');
  const { variants } = await readJson(resolve(assets, 'blockstates', file));
  const facings = ['north', 'east', 'south', 'west', ...(['redstone_port', 'sign_port'].includes(id) ? ['up', 'down'] : [])];
  assert.equal(Object.keys(variants).length, facings.length * 2);
  for (const facing of facings) {
    for (const powered of [false, true]) {
      const variant = variants[`facing=${facing},powered=${powered}`];
      assert.ok(variant, `Missing ${id} facing/power variant`);
      await localReference(variant.model, 'models', '.json');
    }
  }
  const off = await pngSize(resolve(assets, 'textures/block', `${id}_off.png`));
  const on = await pngSize(resolve(assets, 'textures/block', `${id}_on.png`));
  assert.equal(off.width, off.height, `${id}: off texture must be square`);
  assert.equal(on.width, off.width);
  assert.equal(on.height, off.height * 7, `${id}: seven powered animation frames`);
  const { animation } = await readJson(resolve(assets, 'textures/block', `${id}_on.png.mcmeta`));
  assert.equal(animation.width, off.width);
  assert.equal(animation.height, off.height);
  assert.ok(animation.frametime > 0);
  await access(resolve(root, 'src/main/resources/data', namespace, 'loot_table/blocks', file));
  blockCount++;
}
for (const folder of ['models/block', 'models/item']) {
  for (const file of await readdir(resolve(assets, folder))) {
    const model = await readJson(resolve(assets, folder, file));
        if (model.parent?.startsWith('minecraft:')) assert.ok(vanillaParents.has(model.parent), `Unknown vanilla model parent: ${model.parent}`);
        if (model.parent) await localReference(model.parent, 'models', '.json');
    for (const texture of Object.values(model.textures ?? {})) {
      await localReference(texture, 'textures', '.png');
    }
  }
}
const names = await readJson(resolve(assets, 'lang/en_us.json'));
const items = await readdir(resolve(assets, 'items'));
for (const file of items) {
  const definition = await readJson(resolve(assets, 'items', file));
  await localReference(definition.model.model, 'models', '.json');
  const id = file.replace(/\.json$/, '');
  assert.ok(names[`item.${namespace}.${id}`] || names[`block.${namespace}.${id}`], `Missing name: ${id}`);
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

console.log(`Resource checks passed: ${blockCount} animated blocks, ${items.length} item definitions, model references and drops`);
