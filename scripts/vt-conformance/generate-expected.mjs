#!/usr/bin/env node
/**
 * Differential VT conformance: reference output generator.
 *
 * Replays every case in
 *   android/app/src/test/resources/vt-conformance/cases.json
 * through @xterm/headless (the pinned version in node_modules, with the
 * Unicode 11 width provider active, as src/terminal/terminalBuffer.ts
 * configures it) and writes
 *   android/app/src/test/resources/vt-conformance/expected.json
 * which VtConformanceTest.kt compares against NativeTerminalEngine.
 *
 * Usage (from the repository root):
 *   node scripts/vt-conformance/generate-expected.mjs
 *
 * Case format (cases.json):
 *   { "id": string, "rows": int, "cols": int, "input": string,
 *     "splitAt"?: [int], "note"?: string }
 *   Inputs must not contain CSI 5 n: the Kotlin test uses its reply as a
 *   completion sentinel.
 *   `input` is encoded as UTF-8 and fed as bytes. `splitAt` lists ascending
 *   UTF-8 byte offsets at which the input is cut into separate writes
 *   (separate engine.enqueue() chunks), e.g. to split a multibyte character.
 *
 * Output mapping (per case, keyed by id). Everything is expressed in the
 * NativeTerminalEngine.Frame coordinate system:
 *   input/splitAt  copied from the case so a stale expected.json is detected
 *   alternate      buffer.active.type === 'alternate'  (Frame.alternate)
 *   cursorVisible  DECTCEM as observed through a non-consuming CSI ?h/?l
 *                  handler plus RIS (Frame.cursorVisible)
 *   lines          one entry per Frame.lines row. On the normal screen this
 *                  is every xterm buffer line 0..length-1, i.e. scrollback
 *                  (0..baseY-1) followed by the screen rows; on the alternate
 *                  screen it is the screen rows only (no scrollback), exactly
 *                  as buildFrame() orders them.
 *   lines[r].text  human-readable row: cells joined, trailing blanks trimmed.
 *   lines[r].cells one string per column, as Frame.lines[r].text[c]:
 *                  - a wide char's continuation cell (xterm width 0) -> ""
 *                  - an empty/null xterm cell (getChars() === "") -> " "
 *                  - otherwise getChars() (base char plus combining marks)
 *   lines[r].widths one digit per column, xterm getWidth() (0, 1 or 2),
 *                  as Frame.lines[r].width.
 *   lines[r].attrs run-length list of cells whose attributes are not the
 *                  default: [{ from, to (exclusive), fg, bg, flags }].
 *                  Cells not covered by a run have fg = bg = "default" and
 *                  no flags. fg/bg are the RAW (pre-inverse) colours:
 *                  "default", or an opaque ARGB int (signed 32-bit, as a
 *                  Kotlin Int) resolved through the standard xterm 256
 *                  colour palette with the same 16-colour base table as
 *                  NativeTerminalEngine.ANSI_COLORS (the base table is a
 *                  theme choice, not parser behaviour). flags is a subset of
 *                  bold, italic, dim, underline, inverse, strikethrough,
 *                  invisible. The Kotlin test resolves inverse (swaps fg/bg,
 *                  mapping "default" to DEFAULT_FOREGROUND/BACKGROUND) before
 *                  comparing, because the engine publishes it pre-resolved.
 *   cursorRow      baseY + cursorY on the normal screen, cursorY on the
 *                  alternate screen (Frame.cursorRow includes scrollback).
 *   cursorColumn   min(cursorX, cols - 1). xterm reports a pending wrap as
 *                  cursorX === cols; Frame.cursorColumn is clamped the same
 *                  way, so the pending-wrap flag itself is not compared.
 *                  `pendingWrap` records it for the report only.
 *
 * OSC 8 is consumed by a handler (as terminalBuffer.ts does), so hyperlink
 * cells carry only their SGR attributes; xterm would otherwise report them
 * as underlined.
 *
 * Known reference quirks deliberately kept out of the corpus:
 * - xterm.js relative cursor moves (CUU/CUD/CUF/CUB/...) under DECOM add
 *   scrollTop twice (_moveCursor -> _setCursor), so no case uses relative
 *   moves with origin mode set.
 * - xterm.js reads SGR `38:2:r:g:b` (colon form without the colour-space
 *   slot) as `38:2:<cs>:r:g` -- ambiguous per ITU T.416, not tested.
 * - xterm.js gives a zero-width code point that does not directly follow a
 *   printed character (column 0, or after any control/escape) its own
 *   zero-width cell and advances the cursor; the engine joins it to the
 *   previous cell instead (or drops it at column 0). Not tested.
 * - xterm.js dispatches an over-long CSI with its first 32 parameters; the
 *   engine skips a CSI longer than 128 characters. Not tested.
 * - xterm.js answers DSR 6 during a pending wrap with column cols + 1; the
 *   engine reports the last column (as real xterm does). Replies are not
 *   compared.
 *
 * Scrollback is set to 200 rows to match MAX_SCROLLBACK_ROWS; cases stay
 * far below it.
 */
import {readFileSync, writeFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {Terminal} = require('@xterm/headless');
const {Unicode11Addon} = require('@xterm/addon-unicode11');
const xtermVersion = require('@xterm/headless/package.json').version;
const unicodeAddonVersion = require('@xterm/addon-unicode11/package.json').version;

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const dataDir = resolve(root, 'android/app/src/test/resources/vt-conformance');
const casesPath = resolve(dataDir, 'cases.json');
const expectedPath = resolve(dataDir, 'expected.json');

const ANSI = [
  0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00, 0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
  0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00, 0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
];
const argb = (r, g, b) => ((0xFF << 24) | (r << 16) | (g << 8) | b) | 0;
function palette(index) {
  if (index < 16) return ANSI[index] | 0;
  if (index >= 232) {
    const c = 8 + (index - 232) * 10;
    return argb(c, c, c);
  }
  const levels = [0, 95, 135, 175, 215, 255];
  const i = index - 16;
  return argb(levels[Math.floor(i / 36)], levels[Math.floor(i / 6) % 6], levels[i % 6]);
}

function color(cell, which) {
  const isDefault = which === 'fg' ? cell.isFgDefault() : cell.isBgDefault();
  if (isDefault) return 'default';
  const value = which === 'fg' ? cell.getFgColor() : cell.getBgColor();
  const rgb = which === 'fg' ? cell.isFgRGB() : cell.isBgRGB();
  if (rgb) return argb((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
  return palette(value);
}

function flagsOf(cell) {
  const flags = [];
  if (cell.isBold()) flags.push('bold');
  if (cell.isItalic()) flags.push('italic');
  if (cell.isDim()) flags.push('dim');
  if (cell.isUnderline()) flags.push('underline');
  if (cell.isInverse()) flags.push('inverse');
  if (cell.isStrikethrough()) flags.push('strikethrough');
  if (cell.isInvisible()) flags.push('invisible');
  return flags;
}

function snapshotLine(line, cols) {
  const cells = [];
  let widths = '';
  const attrs = [];
  const cell = line.getCell(0);
  for (let c = 0; c < cols; c++) {
    line.getCell(c, cell);
    const width = cell.getWidth();
    const chars = cell.getChars();
    cells.push(width === 0 ? '' : chars === '' ? ' ' : chars);
    widths += String(width);
    const fg = color(cell, 'fg');
    const bg = color(cell, 'bg');
    const flags = flagsOf(cell);
    if (fg === 'default' && bg === 'default' && flags.length === 0) continue;
    const last = attrs[attrs.length - 1];
    if (last && last.to === c && last.fg === fg && last.bg === bg && last.flags.join() === flags.join()) {
      last.to = c + 1;
    } else {
      attrs.push({from: c, to: c + 1, fg, bg, flags});
    }
  }
  return {text: cells.join('').replace(/ +$/, ''), cells, widths, attrs};
}

function chunksOf(bytes, splitAt = []) {
  const chunks = [];
  let start = 0;
  for (const offset of splitAt) {
    if (offset <= start || offset >= bytes.length) throw new Error(`bad splitAt ${offset}`);
    chunks.push(bytes.subarray(start, offset));
    start = offset;
  }
  chunks.push(bytes.subarray(start));
  return chunks;
}

async function run(testCase) {
  const terminal = new Terminal({
    rows: testCase.rows, cols: testCase.cols, scrollback: 200,
    allowProposedApi: true, logLevel: 'off', convertEol: false,
  });
  terminal.loadAddon(new Unicode11Addon());
  terminal.unicode.activeVersion = '11';
  let cursorVisible = true;
  for (const final of ['h', 'l']) {
    terminal.parser.registerCsiHandler({prefix: '?', final}, params => {
      for (const p of params) {
        if (p === 25 || (Array.isArray(p) && p[0] === 25)) cursorVisible = final === 'h';
      }
      return false;
    });
  }
  terminal.parser.registerEscHandler({final: 'c'}, () => {
    cursorVisible = true;
    return false;
  });
  // DECSTR also re-shows the cursor in xterm.
  terminal.parser.registerCsiHandler({intermediates: '!', final: 'p'}, () => {
    cursorVisible = true;
    return false;
  });
  // Consume OSC 8 as src/terminal/terminalBuffer.ts does. Otherwise xterm
  // tags hyperlinked cells with a link underline, which is presentation (the
  // engine detects links separately), not SGR state.
  terminal.parser.registerOscHandler(8, () => true);
  const bytes = new TextEncoder().encode(testCase.input);
  for (const chunk of chunksOf(bytes, testCase.splitAt)) {
    // write() is asynchronous; the callback fires once this chunk is parsed.
    await new Promise(done => terminal.write(chunk, done));
  }
  const buffer = terminal.buffer.active;
  const alternate = buffer.type === 'alternate';
  const lines = [];
  for (let r = 0; r < buffer.length; r++) lines.push(snapshotLine(buffer.getLine(r), testCase.cols));
  const result = {
    // Copied so the Kotlin test can detect an expected.json that is stale
    // relative to cases.json.
    input: testCase.input,
    splitAt: testCase.splitAt ?? [],
    rows: testCase.rows,
    cols: testCase.cols,
    alternate,
    cursorVisible,
    cursorRow: (alternate ? 0 : buffer.baseY) + buffer.cursorY,
    cursorColumn: Math.min(buffer.cursorX, testCase.cols - 1),
    pendingWrap: buffer.cursorX >= testCase.cols,
    lines,
  };
  terminal.dispose();
  return result;
}

const corpus = JSON.parse(readFileSync(casesPath, 'utf8'));
const seen = new Set();
const out = {};
for (const testCase of corpus.cases) {
  if (seen.has(testCase.id)) throw new Error(`duplicate case id ${testCase.id}`);
  seen.add(testCase.id);
  if (testCase.rows < 2 || testCase.cols < 2) throw new Error(`${testCase.id}: engine bounds rows/cols to >= 2`);
  // VtConformanceTest appends DSR 5 (CSI 5 n) as a completion sentinel.
  if (testCase.input.includes('\x1b[5n')) throw new Error(`${testCase.id}: CSI 5 n is reserved for the test sentinel`);
  out[testCase.id] = await run(testCase);
}
writeFileSync(expectedPath, JSON.stringify({
  generator: 'scripts/vt-conformance/generate-expected.mjs',
  reference: `@xterm/headless ${xtermVersion} + @xterm/addon-unicode11 ${unicodeAddonVersion} (unicode 11)`,
  cases: out,
}, null, 1) + '\n');
console.log(`wrote ${Object.keys(out).length} cases to ${expectedPath}`);
