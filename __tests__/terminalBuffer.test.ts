import {
  TerminalCellBuffer, terminalSize, TERMINAL_BACKGROUND, TERMINAL_FOREGROUND,
  TERMINAL_MAX_COLUMNS, TERMINAL_MAX_ROWS, TERMINAL_SCROLLBACK_ROWS,
  TERMINAL_PENDING_BYTES, TERMINAL_REPLAY_HISTORY_BYTES, TERMINAL_WRITE_BYTES, TERMINAL_CELL_CODE_UNITS,
} from '../src/terminal/terminalBuffer';
import {TERMINAL_SESSION_MAX_OUTPUT_BYTES, TERMINAL_SESSION_OUTPUT_WINDOW} from '../src/terminal/session/sessionContract';

const encode = (text: string) => new TextEncoder().encode(text);
const instances: TerminalCellBuffer[] = [];
function setup(rows = 6, columns = 40) {
  const onChange = jest.fn();
  const onError = jest.fn();
  const onReply = jest.fn(async (_data: string) => {});
  const buffer = new TerminalCellBuffer({rows, columns}, {onChange, onError, onReply});
  instances.push(buffer);
  const output = async (...chunks: string[]) => {
    for (const chunk of chunks) buffer.append(encode(chunk));
    await jest.runAllTimersAsync();
  };
  const lines = () => buffer.snapshot().lines.map(line => line.text.trimEnd());
  return {buffer, output, lines, onChange, onError, onReply};
}

beforeEach(() => jest.useFakeTimers());
afterEach(async () => {
  for (const buffer of instances.splice(0)) buffer.dispose();
  await jest.runAllTimersAsync();
  expect(jest.getTimerCount()).toBe(0);
  jest.useRealTimers();
});

test('keeps explicitly positioned spaces and independent TUI columns', async () => {
  const {output, lines, buffer} = setup();
  await output('\x1b[2J\x1b[HWelcome', '\x1b[1;9Hto', '\x1b[1;12HCodex', '\x1b[3;2H1. Start', '\x1b[3;24HEnter');
  expect(lines()).toEqual(['Welcome to Codex', '', ' 1. Start              Enter', '', '', '']);
  expect(buffer.snapshot().cursor).toEqual({row: 2, column: 28, visible: true});
  expect(buffer.snapshot().lines.every(line => line.text.length === 40)).toBe(true);
});

test('publishes the active xterm mouse tracking mode', async () => {
  const {buffer, output} = setup();
  expect(buffer.snapshot().mouseTrackingMode).toBe('none');
  expect(buffer.snapshot().mouseEncoding).toBe('default');
  await output('\x1b[?1000h');
  expect(buffer.snapshot().mouseTrackingMode).toBe('vt200');
  await output('\x1b[?1006h');
  expect(buffer.snapshot().mouseEncoding).toBe('sgr');
  await output('\x1b[?1006l');
  expect(buffer.snapshot().mouseEncoding).toBe('default');
  await output('\x1b[?1016h');
  expect(buffer.snapshot().mouseEncoding).toBe('sgrPixels');
  await output('\x1b[?1016l');
  expect(buffer.snapshot().mouseEncoding).toBe('default');
  await output('\x1b[?1000l');
  expect(buffer.snapshot().mouseTrackingMode).toBe('none');
});

test('reuses unchanged row snapshots and replaces rows whose cells changed', async () => {
  const {buffer, output, onChange} = setup(4, 20);
  await output('stable');
  const first = onChange.mock.calls.at(-1)![0];
  expect(buffer.snapshot().lines[0]).toBe(first.lines[0]);

  await output('\x1b[2;1Hchanged');
  const second = onChange.mock.calls.at(-1)![0];

  expect(second.lines[0]).toBe(first.lines[0]);
  expect(second.lines[1]).not.toBe(first.lines[1]);
  expect(second.lines[2]).toBe(first.lines[2]);
  expect(buffer.snapshot().lines[1].text.trimEnd()).toBe('changed');

  await output('\x1b[1;1H\x1b[31ms');
  const styled = onChange.mock.calls.at(-1)![0];
  expect(styled.lines[0]).not.toBe(second.lines[0]);
  expect(styled.lines[0].text).toBe(second.lines[0].text);
  expect(styled.lines[0].cells[0].foreground).toBe('#cd0000');
  expect(styled.lines[1]).toBe(second.lines[1]);
});

test('overwrites split zsh redraws, preserves repeated keys and backspace cursor', async () => {
  const {output, lines, buffer} = setup();
  await output('$ ', 'a\b', 'a', 'a');
  expect(lines()[0]).toBe('$ aa');
  await output('\b \b', 'b');
  expect(lines()[0]).toBe('$ ab');
  await output('\x1b[', '4Cclock\x1b[9D', 'c\x1b[K');
  expect(lines()[0]).toBe('$ abc');
  await output('\r$ corrected\x1b[K', '\b\x1b[K');
  expect(lines()[0]).toBe('$ correcte');
  expect(buffer.snapshot().cursor.column).toBe(10);
});

test('erases prompt-sp without losing literal percent output and redraws two lines', async () => {
  const {output, lines} = setup(6, 80);
  const startup = '\x1b[1m\x1b[7m%\x1b[27m\x1b[0m' + ' '.repeat(79) + '\r \r\r\x1b[J$ ';
  for (const character of startup) await output(character);
  expect(lines()[0]).toBe('$');
  await output('printf\r\n100%\r\n$ ');
  expect(lines().slice(0, 3)).toEqual(['$ printf', '100%', '$']);
  await output('\x1b[1A\r100%\r\n$ \x1b[K');
  expect(lines().slice(0, 3)).toEqual(['$ printf', '100%', '$']);
});

test('supports absolute/relative row and column movement, save/restore, and tabs', async () => {
  const {output, lines, buffer} = setup();
  await output('abc\tZ\x1b7', '\x1b[4;7Hbottom', '\x1b8', '!');
  expect(lines()[0]).toBe('abc     Z!');
  expect(lines()[3]).toBe('      bottom');
  await output('\x1b[2B\x1b[3D@\x1b[1A\x1b[2C#\x1b[2G$\x1b[5d%');
  expect(lines()[2]).toBe('       @');
  expect(lines()[1]).toBe(' $        #');
  expect(lines()[4]).toBe('  %');
  expect(buffer.snapshot().cursor).toEqual({row: 4, column: 3, visible: true});
});

test('erases without shifting cells and supports insert/delete redraws', async () => {
  const {output, lines, buffer} = setup(4, 12);
  await output('abcdef\x1b[1;3H\x1b[2X');
  expect(lines()[0]).toBe('ab  ef');
  expect(buffer.snapshot().cursor.column).toBe(2);
  await output('\x1b[2P\x1b[1@Z');
  expect(lines()[0]).toBe('abZef');
  await output('\x1b[1K');
  expect(lines()[0]).toBe('    f');
  await output('\x1b[2K\x1b[2;1Hsecond\x1b[3;1Hthird\x1b[2;1H\x1b[L');
  expect(lines()).toEqual(['', '', 'second', 'third']);
  await output('\x1b[M');
  expect(lines()).toEqual(['', 'second', 'third', '']);
  await output('\x1b[3;3H\x1b[J');
  expect(lines()).toEqual(['', 'second', 'th', '']);
  await output('\x1b[2J');
  expect(lines()).toEqual(['', '', '', '']);
  expect(buffer.snapshot().cursor).toEqual({row: 2, column: 2, visible: true});
});

test('uses scrolling margins and origin mode without moving the header/footer', async () => {
  const {output, lines} = setup(5, 12);
  await output('HEADER\x1b[2;1Hone\x1b[3;1Htwo\x1b[4;1Hthree\x1b[5;1HFOOTER');
  await output('\x1b[2;4r\x1b[?6h\x1b[3;1H\r\nnew');
  expect(lines()).toEqual(['HEADER', 'two', 'three', 'new', 'FOOTER']);
  await output('\x1b[H\x1bMtop');
  expect(lines()).toEqual(['HEADER', 'top', 'two', 'three', 'FOOTER']);
});

test('preserves normal scrollback/cursor across alternate-screen entry, resize, and exit', async () => {
  const {output, lines, buffer} = setup(4, 12);
  await output('shell\r\nprompt> ');
  await output('\x1b[?1049h\x1b[2J\x1b[HClaude Code\x1b[3;2HOpenCode\x1b[?25l');
  expect(buffer.snapshot().alternate).toBe(true);
  expect(lines()).toEqual(['Claude Code', '', ' OpenCode', '']);
  expect(buffer.snapshot().cursor.visible).toBe(false);
  buffer.resize({rows: 6, columns: 16});
  await jest.runAllTimersAsync();
  expect(lines()).toEqual(['Claude Code', '', ' OpenCode', '', '', '']);
  await output('\x1b[?1049l\x1b[?25h');
  expect(buffer.snapshot().alternate).toBe(false);
  expect(lines()).toEqual(['shell', 'prompt>', '', '', '', '']);
  expect(buffer.snapshot().cursor).toEqual({row: 1, column: 8, visible: true});
});

test('accepts private CSI sequences without parameters', async () => {
  const {output, buffer} = setup();

  await output('\x1b[?h\x1b[?l');

  expect(buffer.snapshot().cursor.visible).toBe(true);
});

test('preserves ANSI, 256-color, RGB, inverse and style attributes through resets/erase', async () => {
  const {output, buffer} = setup();
  await output('\x1b[31;44;1;3;4mR\x1b[0m \x1b[38;5;196;48;5;232mP\x1b[38;2;1;2;3;48;2;4;5;6;7mT\x1b[0;2;8;9mX\x1b[0mD');
  const cells = buffer.snapshot().lines[0].cells;
  expect(cells[0]).toMatchObject({text: 'R', foreground: '#cd0000', background: '#0000ee', bold: true, italic: true, underline: true});
  expect(cells[1]).toMatchObject({foreground: TERMINAL_FOREGROUND, background: TERMINAL_BACKGROUND, bold: false});
  expect(cells[2]).toMatchObject({foreground: '#ff0000', background: '#080808'});
  expect(cells[3]).toMatchObject({foreground: '#040506', background: '#010203'});
  expect(cells[4]).toMatchObject({dim: true, invisible: true, strikethrough: true});
  expect(cells[5]).toMatchObject({dim: false, invisible: false, strikethrough: false});
  await output('\x1b[42m\x1b[K');
  expect(buffer.snapshot().lines[0].cells[6]).toMatchObject({text: ' ', background: '#00cd00'});
});

test('streams UTF-8 and accounts for CJK, emoji, combining marks and wide-cell overwrites', async () => {
  const {buffer, lines, output} = setup(4, 12);
  for (const byte of encode('A界🙂e\u0301Z')) buffer.append(new Uint8Array([byte]));
  await jest.runAllTimersAsync();
  expect(lines()[0]).toBe('A界🙂éZ');
  expect(buffer.snapshot().lines[0].cells.slice(0, 5).map(cell => [cell.text, cell.column, cell.width])).toEqual([
    ['A', 0, 1], ['界', 1, 2], ['🙂', 3, 2], ['é', 5, 1], ['Z', 6, 1],
  ]);
  await output('\x1b[1;3Hx');
  expect(lines()[0]).toBe('A x🙂éZ');
});

test('keeps delayed autowrap, CR/LF distinction, and normal-buffer resize reflow', async () => {
  const {output, buffer, lines} = setup(4, 8);
  await output('abcdefgh', '\rX');
  expect(lines()).toEqual(['Xbcdefgh', '', '', '']);
  await output('\x1b[Habcdefghijkl');
  expect(lines().slice(0, 2)).toEqual(['abcdefgh', 'ijkl']);
  buffer.resize({rows: 4, columns: 16});
  await jest.runAllTimersAsync();
  expect(lines()[0]).toBe('abcdefghijkl');
  await output('\x1b[3;1Hab\nZ');
  expect(lines()[3]).toBe('  Z');
});

test('serializes output around resize and waits for the native resize result', async () => {
  const {buffer, lines} = setup(4, 8);
  let release!: () => void;
  const applied = jest.fn(() => new Promise<void>(resolve => { release = resolve; }));
  buffer.append(encode('abcdefgh'));
  buffer.resize({rows: 4, columns: 12}, applied);
  buffer.append(encode('\x1b[2;10HX'));
  await jest.runAllTimersAsync();
  expect(applied).toHaveBeenCalledTimes(1);
  expect(lines()[1]).toBe('');
  release();
  await jest.runAllTimersAsync();
  expect(lines()[1]).toBe('         X');
});

test('answers cursor/device/theme queries and suppresses split OSC payloads', async () => {
  const {output, onReply, lines} = setup();
  await output('hello\x1b[6n\x1b[c\x1b]10;?\x1b\\\x1b]11;?\x07');
  expect(onReply.mock.calls.map(call => call[0])).toEqual([
    '\x1b[1;6R', '\x1b[?1;2c', '\x1b]10;rgb:F2F2/F4F4/F5F5\x1b\\', '\x1b]11;rgb:0909/0C0C/0D0D\x1b\\',
  ]);
  await output('\x1b]0;', 'title', '\x1b', '\\ world');
  expect(lines()[0]).toBe('hello world');
});

test('bounds scrollback after sustained output and clamps dimensions', async () => {
  const {output, buffer, lines, onError} = setup(4, 12);
  for (let batch = 0; batch < 5; batch += 1) await output('100%\r\n'.repeat(100));
  expect(buffer.snapshot().lines).toHaveLength(TERMINAL_SCROLLBACK_ROWS + 4);
  expect(lines().slice(-2)).toEqual(['100%', '']);
  expect(onError).not.toHaveBeenCalled();
  expect(terminalSize(1e9, 1e9)).toEqual({rows: TERMINAL_MAX_ROWS, columns: TERMINAL_MAX_COLUMNS});
  expect(terminalSize(0, -2)).toEqual({rows: 2, columns: 2});
  expect(() => terminalSize(NaN, 80)).toThrow('invalid_terminal_size');
});

test('fails loudly on queued output overflow instead of dropping part of a VT stream', async () => {
  const {buffer, onError, onChange} = setup();
  const burstWrites = 32;
  for (let i = 0; i < burstWrites; i += 1) buffer.append(new Uint8Array(TERMINAL_WRITE_BYTES));
  expect(onError).not.toHaveBeenCalled();
  for (let i = burstWrites; i < TERMINAL_PENDING_BYTES / TERMINAL_WRITE_BYTES; i += 1) {
    buffer.append(new Uint8Array(TERMINAL_WRITE_BYTES));
  }
  expect(onError).not.toHaveBeenCalled();
  buffer.append(new Uint8Array(TERMINAL_WRITE_BYTES));
  expect(onError).toHaveBeenCalledWith('terminal_output_overflow');
  buffer.append(encode('ignored'));
  await jest.runAllTimersAsync();
  expect(onError).toHaveBeenCalledTimes(1);
  expect(onChange).not.toHaveBeenCalled();
});

test('batches a full small-chunk replay without overflowing the operation queue', async () => {
  const {buffer, lines, onError} = setup(6, 40);
  const chunks = 4096;
  for (let i = 0; i < chunks; i += 1) buffer.append(encode('x'));

  expect(onError).not.toHaveBeenCalled();
  await jest.runAllTimersAsync();
  expect(onError).not.toHaveBeenCalled();
  expect(lines().filter(Boolean).join('')).toBe('x'.repeat(chunks));
});

test('bounds replay plus the native in-flight output window', async () => {
  const {buffer, onError} = setup(6, 40);
  const replayChunks = TERMINAL_REPLAY_HISTORY_BYTES / TERMINAL_SESSION_MAX_OUTPUT_BYTES;
  const chunk = encode('x'.repeat(TERMINAL_SESSION_MAX_OUTPUT_BYTES));
  for (let i = 0; i < replayChunks + TERMINAL_SESSION_OUTPUT_WINDOW; i += 1) {
    buffer.append(chunk);
  }

  expect(TERMINAL_PENDING_BYTES).toBe(
    TERMINAL_REPLAY_HISTORY_BYTES + TERMINAL_SESSION_OUTPUT_WINDOW * TERMINAL_SESSION_MAX_OUTPUT_BYTES,
  );
  expect(onError).not.toHaveBeenCalled();
  await jest.runAllTimersAsync();
  expect(onError).not.toHaveBeenCalled();
});

test('bounds repeated combining marks in one retained cell', async () => {
  const {output, onError} = setup();
  await output('e');
  for (let i = 0; i < 5; i += 1) await output('\u0301'.repeat(TERMINAL_CELL_CODE_UNITS / 4));
  expect(onError).toHaveBeenCalledWith('terminal_cell_overflow');
});

test('checks both screens when one bounded write changes a cell and switches buffers', async () => {
  const {buffer, onError} = setup();
  buffer.append(encode(`e${'\u0301'.repeat(TERMINAL_CELL_CODE_UNITS)}\u001b[?1049h`));
  await jest.runAllTimersAsync();
  expect(onError).toHaveBeenCalledWith('terminal_cell_overflow');
});

test('checks a newly scrolled line before treating its cells as immutable history', async () => {
  const {buffer, onError} = setup(2, 12);
  buffer.append(encode(`e${'\u0301'.repeat(TERMINAL_CELL_CODE_UNITS)}\r\nx\r\ny\r\n`));
  await jest.runAllTimersAsync();
  expect(onError).toHaveBeenCalledWith('terminal_cell_overflow');
});

test('coalesces rapid terminal output into one latest frame at a scroll-friendly cadence', async () => {
  const {buffer, onChange} = setup(4, 20);
  buffer.append(encode('first'));
  await jest.advanceTimersByTimeAsync(16);
  expect(onChange).not.toHaveBeenCalled();

  buffer.append(encode('\rsecond'));
  await jest.runAllTimersAsync();
  expect(onChange).toHaveBeenCalledTimes(1);
  expect(onChange.mock.calls[0][0].lines[0].text.trimEnd()).toBe('second');
});

test('slows alternate-screen redraws without changing normal-buffer cadence', async () => {
  const {buffer, onChange} = setup(4, 20);
  buffer.append(encode('\x1b[?1049h\x1b[2J\x1b[Hscreen'));
  await jest.advanceTimersByTimeAsync(63);
  expect(onChange).not.toHaveBeenCalled();
  await jest.advanceTimersByTimeAsync(1);
  expect(onChange).toHaveBeenCalledTimes(1);

  buffer.append(encode('\x1b[2;1Hnext'));
  await jest.advanceTimersByTimeAsync(63);
  expect(onChange).toHaveBeenCalledTimes(1);
  await jest.advanceTimersByTimeAsync(1);
  expect(onChange).toHaveBeenCalledTimes(2);

  await jest.runAllTimersAsync();
  buffer.append(encode('\x1b[?1049l\x1b[1;1Hnormal'));
  await jest.advanceTimersByTimeAsync(31);
  expect(onChange).toHaveBeenCalledTimes(2);
  await jest.advanceTimersByTimeAsync(1);
  expect(onChange).toHaveBeenCalledTimes(3);
});

test('checks lines displaced while scrollback is already capped', async () => {
  const {buffer, onError} = setup(2, 12);
  buffer.append(encode('x\r\n'.repeat(TERMINAL_SCROLLBACK_ROWS + 4)));
  await jest.runAllTimersAsync();
  buffer.append(encode(`e${'\u0301'.repeat(TERMINAL_CELL_CODE_UNITS)}\r\nx\r\ny\r\n`));
  await jest.runAllTimersAsync();
  expect(onError).toHaveBeenCalledWith('terminal_cell_overflow');
});

test('preserves snapshot identity for immutable scrollback across a capped trim', async () => {
  const {buffer, output} = setup(37, 46);
  await output(Array.from({length: 260}, (_, index) => `line-${index}\r\n`).join(''));
  const before = buffer.snapshot();
  expect(before.lines).toHaveLength(TERMINAL_SCROLLBACK_ROWS + 37);

  await output('line-260\r\n');
  const after = buffer.snapshot();
  expect(after.lines[0].text.trimEnd()).toBe('line-25');
  expect(after.lines[234].text.trimEnd()).toBe('line-259');
  expect(after.lines[235].text.trimEnd()).toBe('line-260');
  expect(after.lines[236].text.trimEnd()).toBe('');
  expect(after.lines[0]).toBe(before.lines[1]);
  expect(after.lines[234]).toBe(before.lines[235]);
  expect(after.lines[235]).not.toBe(before.lines[235]);
  expect(after.lines[236]).toBe(before.lines[236]);
});

test('publishes synchronized redraws together and releases an unterminated frame within one second', async () => {
  const {buffer, onChange} = setup();
  buffer.append(encode('\x1b[?2026hfirst'));
  await jest.advanceTimersByTimeAsync(20);
  buffer.append(encode('\x1b[2;1Hsecond'));
  await jest.advanceTimersByTimeAsync(20);
  expect(onChange).not.toHaveBeenCalled();
  buffer.append(encode('\x1b[?2026l'));
  await jest.advanceTimersByTimeAsync(40);
  expect(onChange).toHaveBeenCalledTimes(1);
  expect(onChange.mock.calls[0][0].lines.slice(0, 2).map((line: {text: string}) => line.text.trimEnd())).toEqual(['first', 'second']);
  buffer.append(encode('\x1b[?2026h!'));
  await jest.advanceTimersByTimeAsync(1100);
  expect(onChange).toHaveBeenCalledTimes(2);
  expect(jest.getTimerCount()).toBe(0);
  buffer.append(encode('\x1b[?2026l\x1b[?2026hqueued'));
  await jest.advanceTimersByTimeAsync(1);
  buffer.dispose();
  await jest.runAllTimersAsync();
  expect(onChange).toHaveBeenCalledTimes(2);
});

test('fails loudly and drains outstanding promises when device replies exceed their bound', async () => {
  const {buffer, onReply, onError} = setup();
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  onReply.mockImplementation(() => pending);
  buffer.append(encode('\x1b[6n'.repeat(129)));
  await jest.runAllTimersAsync();
  expect(onReply).toHaveBeenCalledTimes(128);
  expect(onError).toHaveBeenCalledWith('terminal_reply_overflow');
  release();
  await jest.runAllTimersAsync();
  expect(jest.getTimerCount()).toBe(0);
});

test('reports failed replies and disposes pending work', async () => {
  const {output, onReply, onError} = setup();
  onReply.mockRejectedValue(new Error('bridge rejected'));
  await output('\x1b[6n');
  expect(onError).toHaveBeenCalledWith('terminal_reply_failed');
});

test('disposes with parser write, queued resize, and publication pending; no late callbacks or handles', async () => {
  const {buffer, onReply, onChange, onError} = setup();
  const applied = jest.fn(async () => {});
  buffer.append(encode('pending\x1b[6n'));
  buffer.resize({rows: 3, columns: 20}, applied);
  buffer.dispose();
  buffer.dispose();
  await jest.runAllTimersAsync();
  expect(applied).not.toHaveBeenCalled();
  expect(onReply).not.toHaveBeenCalled();
  expect(onChange).not.toHaveBeenCalled();
  expect(onError).not.toHaveBeenCalled();
  expect(jest.getTimerCount()).toBe(0);

  const second = setup();
  second.buffer.resize({rows: 3, columns: 20});
  second.buffer.dispose();
  await jest.runAllTimersAsync();
  expect(second.onChange).not.toHaveBeenCalled();
  expect(jest.getTimerCount()).toBe(0);
});

test('drains in-flight native resize/reply promises without starting queued work on disposal', async () => {
  const {buffer, onChange} = setup();
  let release!: () => void;
  buffer.resize({rows: 3, columns: 20}, () => new Promise<void>(resolve => { release = resolve; }));
  buffer.append(encode('never rendered'));
  buffer.dispose();
  release();
  await jest.runAllTimersAsync();
  expect(onChange).not.toHaveBeenCalled();
  expect(jest.getTimerCount()).toBe(0);
});
