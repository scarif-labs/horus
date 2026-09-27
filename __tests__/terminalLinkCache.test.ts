import {positionTerminalLinks, type TerminalLinkScanCache} from '../src/terminal/TerminalGrid';
import {
  TERMINAL_BACKGROUND, TERMINAL_FOREGROUND,
  type TerminalCell, type TerminalFrame, type TerminalRow,
} from '../src/terminal/terminalBuffer';

function makeRow(text: string, wrapped: boolean): TerminalRow {
  const cells: TerminalCell[] = [];
  for (let column = 0; column < text.length; column += 1) {
    cells.push({
      text: text.charAt(column), column, width: 1,
      foreground: TERMINAL_FOREGROUND, background: TERMINAL_BACKGROUND,
      bold: false, italic: false, dim: false, underline: false, strikethrough: false, invisible: false,
    });
  }
  return {text, cells, wrapped};
}

function makeFrame(lines: readonly TerminalRow[]): TerminalFrame {
  return {
    rows: Math.max(lines.length, 1), columns: 40, lines,
    alternate: false, mouseTrackingMode: 'none', mouseEncoding: 'default', cursor: {row: 0, column: 0, visible: false},
  };
}

test('reuses identical link arrays when an unchanged frame is rescanned', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  const lines = [
    makeRow('open https://github.com/login/device now', false),
    makeRow('no links here', false),
    makeRow('sign in at https://claude.ai/oauth', false),
    makeRow('/authorize to continue', true),
  ];
  const first = positionTerminalLinks(makeFrame(lines), cache);
  const second = positionTerminalLinks(makeFrame(lines), cache);
  expect(second.get(0)).toBe(first.get(0));
  expect(second.get(2)).toBe(first.get(2));
  expect(second.get(3)).toBe(first.get(3));
  expect(first.get(0)).toEqual([
    {url: 'https://github.com/login/device', startIndex: 5, endIndex: 36, startColumn: 5, endColumn: 36},
  ]);
  expect(first.get(2)).toEqual([
    {url: 'https://claude.ai/oauth/authorize', startIndex: 11, endIndex: 44, startColumn: 11, endColumn: 34},
  ]);
  expect(first.get(3)).toEqual([
    {url: 'https://claude.ai/oauth/authorize', startIndex: 11, endIndex: 44, startColumn: 0, endColumn: 10},
  ]);
});

test('rescans only the wrapped group whose member row object was replaced', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  const stable = makeRow('see https://opencode.ai/docs today', false);
  const head = makeRow('track https://github.com/octocat/H', false);
  const tail = makeRow('ello-World for details', true);
  const before = positionTerminalLinks(makeFrame([stable, head, tail]), cache);
  expect(before.get(0)).toEqual([
    {url: 'https://opencode.ai/docs', startIndex: 4, endIndex: 28, startColumn: 4, endColumn: 28},
  ]);
  expect(before.get(2)).toEqual([
    {url: 'https://github.com/octocat/Hello-World', startIndex: 6, endIndex: 44, startColumn: 0, endColumn: 10},
  ]);

  const replacementTail = makeRow('ello-World.git now', true);
  const after = positionTerminalLinks(makeFrame([stable, head, replacementTail]), cache);
  expect(after.get(0)).toBe(before.get(0));
  expect(after.get(1)).not.toBe(before.get(1));
  expect(after.get(2)).not.toBe(before.get(2));
  expect(after.get(1)).toEqual([
    {url: 'https://github.com/octocat/Hello-World.git', startIndex: 6, endIndex: 48, startColumn: 6, endColumn: 34},
  ]);
  expect(after.get(2)).toEqual([
    {url: 'https://github.com/octocat/Hello-World.git', startIndex: 6, endIndex: 48, startColumn: 0, endColumn: 14},
  ]);
});

test('does not reuse the continuation row links after the group head is rewritten', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  const head = makeRow('push https://github.com/octocat/H', false);
  const tail = makeRow('ello-World.git ok', true);
  const before = positionTerminalLinks(makeFrame([head, tail]), cache);
  expect(before.get(0)).toEqual([
    {url: 'https://github.com/octocat/Hello-World.git', startIndex: 5, endIndex: 47, startColumn: 5, endColumn: 33},
  ]);
  expect(before.get(1)).toEqual([
    {url: 'https://github.com/octocat/Hello-World.git', startIndex: 5, endIndex: 47, startColumn: 0, endColumn: 14},
  ]);

  const rewrittenHead = makeRow('push https://opencode.ai/docs x', false);
  const after = positionTerminalLinks(makeFrame([rewrittenHead, tail]), cache);
  expect(after.get(0)).not.toBe(before.get(0));
  expect(after.get(0)).toEqual([
    {url: 'https://opencode.ai/docs', startIndex: 5, endIndex: 29, startColumn: 5, endColumn: 29},
  ]);
  expect(after.has(1)).toBe(false);
  expect(after).toEqual(positionTerminalLinks(makeFrame([rewrittenHead, tail]), new WeakMap()));
});

test('rescans when group boundaries change and when a group grows back', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  const head = makeRow('visit https://claude.ai/oauth', false);
  const tail = makeRow('/authorize here', true);

  const wrapped = positionTerminalLinks(makeFrame([head, tail]), cache);
  expect(wrapped.get(0)).toEqual([
    {url: 'https://claude.ai/oauth/authorize', startIndex: 6, endIndex: 39, startColumn: 6, endColumn: 29},
  ]);
  expect(wrapped.get(1)).toEqual([
    {url: 'https://claude.ai/oauth/authorize', startIndex: 6, endIndex: 39, startColumn: 0, endColumn: 10},
  ]);

  // Same text, but no longer a wrapped continuation: the row now forms its own
  // group and was never a group head, so the cache misses and it rescans.
  const splitTail = makeRow('/authorize here', false);
  const split = positionTerminalLinks(makeFrame([head, splitTail]), cache);
  expect(split.get(0)).not.toBe(wrapped.get(0));
  expect(split.get(0)).toEqual([
    {url: 'https://claude.ai/oauth', startIndex: 6, endIndex: 29, startColumn: 6, endColumn: 29},
  ]);
  expect(split.has(1)).toBe(false);
  expect(split).toEqual(positionTerminalLinks(makeFrame([head, splitTail]), new WeakMap()));

  // Growing the head group with a new wrapped member must also invalidate it.
  const grownTail = makeRow('/authorize/again', true);
  const grown = positionTerminalLinks(makeFrame([head, grownTail]), cache);
  expect(grown.get(0)).not.toBe(split.get(0));
  expect(grown.get(0)).toEqual([
    {url: 'https://claude.ai/oauth/authorize/again', startIndex: 6, endIndex: 45, startColumn: 6, endColumn: 29},
  ]);
  expect(grown.get(1)).toEqual([
    {url: 'https://claude.ai/oauth/authorize/again', startIndex: 6, endIndex: 45, startColumn: 0, endColumn: 16},
  ]);
  expect(grown).toEqual(positionTerminalLinks(makeFrame([head, grownTail]), new WeakMap()));
});

test('leaves no map entry for rows without links on cold and warm scans', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  const lines = [
    makeRow('plain start', false),
    makeRow('also plain ', true),
    makeRow('end https://anthropic.com/wait', false),
  ];
  const first = positionTerminalLinks(makeFrame(lines), cache);
  expect(first.has(0)).toBe(false);
  expect(first.has(1)).toBe(false);
  expect(first.get(2)).toEqual([
    {url: 'https://anthropic.com/wait', startIndex: 4, endIndex: 30, startColumn: 4, endColumn: 30},
  ]);
  const second = positionTerminalLinks(makeFrame(lines), cache);
  expect(second.has(0)).toBe(false);
  expect(second.has(1)).toBe(false);
  expect(second.size).toBe(1);
});

test('warmed cache output matches a cold scan across a frame sequence', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  const r0 = makeRow('alpha https://github.com/one/a', false);
  const r1 = makeRow('plain row', false);
  const r2 = makeRow('beta https://claude.ai/x', false);
  const r3 = makeRow('/y tail', true);
  const r2b = makeRow('beta https://claude.ai/zz', false);
  const r3b = makeRow('/zzz tail2', true);
  const r0c = makeRow('alpha https://github.com/one/changed', false);
  const sequence: readonly (readonly TerminalRow[])[] = [
    [],
    [r0, r1],
    [r0, r1, r2, r3],
    [r0, r1, r2b, r3],
    [r0, r1, r2b, r3b],
    [r0c, r1, r2b, r3b],
  ];
  for (const lines of sequence) {
    const warmed = positionTerminalLinks(makeFrame(lines), cache);
    expect(warmed).toEqual(positionTerminalLinks(makeFrame(lines), new WeakMap()));
  }
});

test('returns an empty map for an undefined frame', () => {
  const cache: TerminalLinkScanCache = new WeakMap();
  expect(positionTerminalLinks(undefined, cache).size).toBe(0);
});
