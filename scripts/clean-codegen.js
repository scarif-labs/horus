const fs = require('node:fs');
const path = require('node:path');

const projectRoot = path.resolve(__dirname, '..');
const generatedRoot = path.resolve(projectRoot, 'android/app/build/generated/source/codegen');
const expectedParent = path.resolve(projectRoot, 'android/app/build/generated/source');

if (path.dirname(generatedRoot) !== expectedParent || !generatedRoot.endsWith(`${path.sep}codegen`)) {
  throw new Error('Refusing to clean an unexpected Codegen output path');
}

fs.rmSync(generatedRoot, {recursive: true, force: true});
console.log(`Cleaned generated Codegen output: ${path.relative(projectRoot, generatedRoot)}`);
