import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import ts from 'typescript';

// The tests run the real TypeScript sources rather than copies, so a test failure means production behaviour broke.
// Each source file is transpiled to CommonJS and evaluated in this realm, which keeps instanceof checks (ApiError, Error)
// consistent between the test file and the loaded module without a browser or bundler.
const srcDirectory = new URL('../../src/', import.meta.url);
const nodeRequire = createRequire(new URL('api.ts', srcDirectory));
const cache = new Map();

function resolvePath(name) {
  for (const extension of ['.tsx', '.ts']) {
    const file = new URL(name + extension, srcDirectory);
    try {
      return readFileSync(file, 'utf8');
    } catch {
      // Try the next extension.
    }
  }
  throw new Error(`Unknown module: ${name}`);
}

export function loadModule(name, apiBaseUrl) {
  const useCache = apiBaseUrl === undefined;
  if (useCache && cache.has(name)) return cache.get(name);
  const output = ts.transpileModule(resolvePath(name), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const module = { exports: {} };
  // Cache before evaluating so modules that import each other do not recurse.
  if (useCache) cache.set(name, module.exports);
  const localRequire = specifier =>
    specifier.startsWith('./') ? loadModule(specifier.slice(2)) : nodeRequire(specifier);
  vm.runInThisContext('(function (exports, module, require, __STORYTELLER_API_BASE_URL__) {"use strict";' + output + '})')
    (module.exports, module, localRequire, apiBaseUrl ?? '');
  if (useCache) cache.set(name, module.exports);
  return module.exports;
}

export function loadDefault(name) {
  return loadModule(name).default;
}
