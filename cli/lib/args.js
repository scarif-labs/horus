'use strict';

const VALUE_FLAGS = new Set(['name']);

/**
 * Minimal parser: `--flag`, `--flag value` for known value flags, `-r`, and
 * everything after `--` passed through untouched as `rest`.
 */
function parseArgs(argv) {
  const positional = [];
  const flags = {};
  let rest = [];
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--') {
      rest = argv.slice(index + 1);
      break;
    }
    if (arg.startsWith('--')) {
      const [name, inline] = arg.slice(2).split(/[=](.*)/s, 2);
      if (VALUE_FLAGS.has(name)) {
        const value = inline ?? argv[index + 1];
        if (value === undefined) throw new Error(`--${name} needs a value`);
        if (inline === undefined) index += 1;
        flags[name] = value;
      } else {
        flags[name] = true;
      }
    } else if (/^-[a-zA-Z]+$/.test(arg)) {
      for (const letter of arg.slice(1)) flags[letter] = true;
    } else {
      positional.push(arg);
    }
  }
  return {positional, flags, rest};
}

module.exports = {parseArgs};
