import test from 'node:test';
import assert from 'node:assert/strict';
import { loadModule } from './helpers/modules.mjs';

const { validateImageFile, MAX_IMAGE_BYTES } = loadModule('images');
const file = (type, size) => new File([new Uint8Array(size)], 'image', { type });

test('Given the accepted formats, When validating, Then PNG and JPEG pass', () => {
  assert.doesNotThrow(() => validateImageFile(file('image/png', 1)));
  assert.doesNotThrow(() => validateImageFile(file('image/jpeg', 1)));
});

test('Given an unsupported type, When validating, Then only PNG and JPEG are mentioned', () => {
  assert.throws(() => validateImageFile(file('image/gif', 10)),
    { message: 'Only PNG and JPEG images are supported.' });
});

test('Given an empty image, When validating, Then the size limit is reported', () => {
  assert.throws(() => validateImageFile(file('image/png', 0)),
    { message: 'The image must be between 1 byte and 5 MiB.' });
});

test('Given an image over the limit, When validating, Then the size limit is reported', () => {
  assert.equal(MAX_IMAGE_BYTES, 5 * 1024 * 1024);
  assert.throws(() => validateImageFile({ type: 'image/png', size: MAX_IMAGE_BYTES + 1 }),
    { message: 'The image must be between 1 byte and 5 MiB.' });
});
