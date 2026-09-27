import {Terminal, type IBufferCell, type IBufferLine, type IDisposable} from '@xterm/headless';
import {Unicode11Addon} from '@xterm/addon-unicode11';
import {
  TERMINAL_SESSION_MAX_OUTPUT_BYTES,
  TERMINAL_SESSION_OUTPUT_WINDOW,
} from './session/sessionContract';
import type {TerminalMouseEncoding} from './terminalMouse';

// Pinned xterm core; only public (including proposed buffer/parser) APIs are
// used. No DOM, WebView, PTY decoding, or terminal contents in logs.
export const TERMINAL_MAX_COLUMNS = 240;
export const TERMINAL_MAX_ROWS = 100;
export const TERMINAL_SCROLLBACK_ROWS = 200;
export const TERMINAL_REPLAY_HISTORY_BYTES = 1024 * 1024;
export const TERMINAL_PENDING_BYTES =
  TERMINAL_REPLAY_HISTORY_BYTES + TERMINAL_SESSION_OUTPUT_WINDOW * TERMINAL_SESSION_MAX_OUTPUT_BYTES;
export const TERMINAL_WRITE_BYTES = 16 * 1024;
export const TERMINAL_CELL_CODE_UNITS = 64;
const MAX_OPERATIONS = 256;
const MAX_PENDING_REPLIES = 128;
// Coalesce rapid PTY writes so frame snapshots leave JS time for touch input.
const TERMINAL_FRAME_PUBLISH_INTERVAL_MS = 32;
const TERMINAL_SCROLLING_FRAME_PUBLISH_INTERVAL_MS = 100;
// Alternate-screen TUIs repaint the entire screen after each wheel/page report.
// A slightly wider cadence keeps those redraws from monopolizing JS while
// preserving the normal-buffer stream cadence and interactive input latency.
const TERMINAL_ALTERNATE_FRAME_PUBLISH_INTERVAL_MS = 64;
const MAX_SNAPSHOT_SCROLL_ROWS = TERMINAL_SCROLLBACK_ROWS + TERMINAL_MAX_ROWS;
export const TERMINAL_FOREGROUND = '#F2F4F5';
export const TERMINAL_BACKGROUND = '#090C0D';

export type TerminalSize = Readonly<{rows: number; columns: number}>;
export type TerminalCell = Readonly<{
  text: string;
  column: number;
  width: number;
  foreground: string;
  background: string;
  bold: boolean;
  italic: boolean;
  dim: boolean;
  underline: boolean;
  strikethrough: boolean;
  invisible: boolean;
}>;
export type TerminalRow = Readonly<{text: string; cells: readonly TerminalCell[]; wrapped: boolean}>;
export type TerminalMouseTrackingMode = 'none' | 'x10' | 'vt200' | 'drag' | 'any';
export type TerminalFrame = TerminalSize & Readonly<{
  lines: readonly TerminalRow[];
  alternate: boolean;
  mouseTrackingMode: TerminalMouseTrackingMode;
  mouseEncoding: TerminalMouseEncoding;
  cursor: Readonly<{row: number; column: number; visible: boolean}>;
}>;
type TerminalCallbacks = Readonly<{
  onChange: (frame: TerminalFrame) => void;
  onReply: (data: string) => Promise<void>;
  onError: (code: string) => void;
}>;
type OutputOperation = {bytes: Uint8Array; byteLength: number};
type Operation = OutputOperation | {size: TerminalSize; applied?: () => Promise<void>};

export function terminalSize(rows: number, columns: number): TerminalSize {
  if (!Number.isFinite(rows) || !Number.isFinite(columns)) throw new Error('invalid_terminal_size');
  return {
    rows: Math.max(2, Math.min(TERMINAL_MAX_ROWS, Math.floor(rows))),
    columns: Math.max(2, Math.min(TERMINAL_MAX_COLUMNS, Math.floor(columns))),
  };
}

const ANSI_COLORS = [
  '#000000', '#cd0000', '#00cd00', '#cdcd00', '#0000ee', '#cd00cd', '#00cdcd', '#e5e5e5',
  '#7f7f7f', '#ff0000', '#00ff00', '#ffff00', '#5c5cff', '#ff00ff', '#00ffff', '#ffffff',
];
function rgb(value: number): string {
  return `#${value.toString(16).padStart(6, '0')}`;
}
function palette(value: number): string {
  if (value < 16) return ANSI_COLORS[value];
  if (value >= 232) return rgb((8 + (value - 232) * 10) * 0x010101);
  const index = value - 16;
  const levels = [0, 95, 135, 175, 215, 255];
  return rgb(levels[Math.floor(index / 36)] * 65536 + levels[Math.floor(index / 6) % 6] * 256 + levels[index % 6]);
}
function copyCell(cell: IBufferCell, column: number): TerminalCell {
  let foreground = cell.isFgRGB() ? rgb(cell.getFgColor()) : cell.isFgPalette() ? palette(cell.getFgColor()) : TERMINAL_FOREGROUND;
  let background = cell.isBgRGB() ? rgb(cell.getBgColor()) : cell.isBgPalette() ? palette(cell.getBgColor()) : TERMINAL_BACKGROUND;
  if (cell.isInverse()) [foreground, background] = [background, foreground];
  return {
    text: cell.getChars() || ' ', column, width: cell.getWidth(), foreground, background,
    bold: !!cell.isBold(), italic: !!cell.isItalic(), dim: !!cell.isDim(),
    underline: !!cell.isUnderline(), strikethrough: !!cell.isStrikethrough(), invisible: !!cell.isInvisible(),
  };
}

function cellColorMatches(cell: IBufferCell, foreground: boolean, expected: string): boolean {
  const useForeground = foreground !== !!cell.isInverse();
  if (useForeground) {
    if (cell.isFgRGB()) return expected === rgb(cell.getFgColor());
    if (cell.isFgPalette()) return expected === palette(cell.getFgColor());
    return expected === TERMINAL_FOREGROUND;
  }
  if (cell.isBgRGB()) return expected === rgb(cell.getBgColor());
  if (cell.isBgPalette()) return expected === palette(cell.getBgColor());
  return expected === TERMINAL_BACKGROUND;
}

function rowMatches(line: IBufferLine, row: TerminalRow, columns: number, cell: IBufferCell): boolean {
  if (row.wrapped !== line.isWrapped) return false;
  let rowCellIndex = 0;
  for (let column = 0; column < columns; column += 1) {
    line.getCell(column, cell);
    const width = cell.getWidth();
    if (width === 0) continue;
    const previous = row.cells[rowCellIndex];
    rowCellIndex += 1;
    if (
      previous === undefined || previous.column !== column || previous.width !== width ||
      previous.text !== (cell.getChars() || ' ') || previous.bold !== !!cell.isBold() ||
      previous.italic !== !!cell.isItalic() || previous.dim !== !!cell.isDim() ||
      previous.underline !== !!cell.isUnderline() || previous.strikethrough !== !!cell.isStrikethrough() ||
      previous.invisible !== !!cell.isInvisible() ||
      !cellColorMatches(cell, true, previous.foreground) || !cellColorMatches(cell, false, previous.background)
    ) return false;
  }
  return rowCellIndex === row.cells.length;
}

/** Bounded VT state and ordered writes/resizes. Native contracts remain intact. */
export class TerminalCellBuffer {
  private readonly terminal: Terminal;
  private readonly listeners: IDisposable[] = [];
  private lastSnapshotRows: TerminalRow[] = [];
  private lastSnapshotBufferType: 'normal' | 'alternate' | undefined;
  private lastSnapshotNormalBaseY: number | undefined;
  private normalRowsScrolledSinceSnapshot = 0;
  private callbacks: TerminalCallbacks | undefined;
  private queue: Operation[] = [];
  private pendingBytes = 0;
  private pendingReplies = 0;
  private pendingReplyBytes = 0;
  private busy = false;
  private disposed = false;
  private cursorVisible = true;
  private mouseEncoding: TerminalMouseEncoding = 'default';
  private synchronizedUntil = 0;
  private alternateBufferTouchedDuringWrite = false;
  private combiningMarkTouchedDuringWrite = false;
  private combiningMarkBytesTail: number[] = [];
  private lastCheckedNormalBaseY = 0;
  private normalScrollsDuringWrite = 0;
  private publishTimer: ReturnType<typeof setTimeout> | undefined;
  private publishIsSynchronized = false;
  private scrolling = false;

  constructor(size: TerminalSize, callbacks: TerminalCallbacks) {
    const bounded = terminalSize(size.rows, size.columns);
    this.callbacks = callbacks;
    this.terminal = new Terminal({
      rows: bounded.rows, cols: bounded.columns, scrollback: TERMINAL_SCROLLBACK_ROWS,
      allowProposedApi: true, logLevel: 'off', convertEol: false, reflowCursorLine: true,
    });
    this.terminal.loadAddon(new Unicode11Addon());
    this.terminal.unicode.activeVersion = '11';
    this.listeners.push(this.terminal.onData(data => this.reply(data)));
    this.listeners.push(this.terminal.onScroll(() => {
      if (this.terminal.buffer.active.type === 'normal') this.normalScrollsDuringWrite += 1;
    }));
    // Headless exposes position but not DECTCEM. Observe it without consuming
    // the sequence, so xterm still maintains all its own DEC mode state.
    for (const final of ['h', 'l']) {
      this.listeners.push(this.terminal.parser.registerCsiHandler({prefix: '?', final}, params => {
        const hasParameter = (value: number) => Array.isArray(params) && params.some(parameter => (
          Array.isArray(parameter) ? parameter.includes(value) : parameter === value
        ));

        if (hasParameter(25)) this.cursorVisible = final === 'h';
        if (hasParameter(47) || hasParameter(1047) || hasParameter(1049)) {
          this.alternateBufferTouchedDuringWrite = true;
        }
        if (hasParameter(2026)) {
          if (final === 'l') this.synchronizedUntil = 0;
          else if (this.synchronizedUntil === 0) this.synchronizedUntil = Date.now() + 1000;
        }
        if (hasParameter(1006)) this.mouseEncoding = final === 'h' ? 'sgr' : 'default';
        if (hasParameter(1016)) this.mouseEncoding = final === 'h' ? 'sgrPixels' : 'default';
        return false;
      }));
    }
    this.listeners.push(this.terminal.parser.registerEscHandler({final: 'c'}, () => {
      this.cursorVisible = true;
      this.mouseEncoding = 'default';
      this.synchronizedUntil = 0;
      this.invalidateSnapshot();
      return false;
    }));
    // No title, hyperlink activation, or clipboard integration on this surface.
    // xterm bounds OSC/DCS payload accumulation internally to 10 MB each.
    for (const code of [0, 1, 2, 8, 52]) {
      this.listeners.push(this.terminal.parser.registerOscHandler(code, () => true));
    }
    // Full-screen TUIs commonly query the theme before their first redraw.
    for (const [code, color] of [[10, TERMINAL_FOREGROUND], [11, TERMINAL_BACKGROUND]] as const) {
      this.listeners.push(this.terminal.parser.registerOscHandler(code, data => {
        if (data === '?') {
          const channels = [color.slice(1, 3), color.slice(3, 5), color.slice(5, 7)].map(value => value + value);
          this.reply(`\u001b]${code};rgb:${channels.join('/')}\u001b\\`);
        }
        return true;
      }));
    }
  }

  append(bytes: Uint8Array): void {
    if (this.disposed || bytes.length === 0) return;
    if (bytes.length > TERMINAL_WRITE_BYTES || this.pendingBytes + bytes.length > TERMINAL_PENDING_BYTES) {
      this.fail('terminal_output_overflow');
      return;
    }

    const last = this.queue[this.queue.length - 1];
    if (last !== undefined && 'bytes' in last && last.byteLength + bytes.length <= TERMINAL_WRITE_BYTES) {
      // Resume replay delivers many small native chunks synchronously. Batch
      // adjacent output while preserving resize barriers and stream order.
      last.bytes.set(bytes, last.byteLength);
      last.byteLength += bytes.length;
      this.pendingBytes += bytes.length;
      this.pump();
      return;
    }
    if (this.queue.length >= MAX_OPERATIONS) {
      this.fail('terminal_output_overflow');
      return;
    }
    const outputBytes = new Uint8Array(TERMINAL_WRITE_BYTES);
    outputBytes.set(bytes);
    this.pendingBytes += bytes.length;
    this.queue.push({bytes: outputBytes, byteLength: bytes.length});
    this.pump();
  }

  resize(size: TerminalSize, applied?: () => Promise<void>): void {
    if (this.disposed) return;
    if (this.queue.length >= MAX_OPERATIONS) {
      this.fail('terminal_resize_overflow');
      return;
    }
    this.queue.push({size: terminalSize(size.rows, size.columns), applied});
    this.pump();
  }

  snapshot(): TerminalFrame {
    const buffer = this.terminal.buffer.active;
    const cell = buffer.getNullCell();
    const lines: TerminalRow[] = [];
    const sameBuffer = this.lastSnapshotBufferType === buffer.type;
    const previousRows = sameBuffer ? this.lastSnapshotRows : [];
    const previousNormalBaseY = sameBuffer && buffer.type === 'normal'
      ? this.lastSnapshotNormalBaseY ?? 0
      : 0;
    const pendingNormalScrolls = buffer.type === 'normal' && sameBuffer
      ? Math.min(this.normalRowsScrolledSinceSnapshot, previousRows.length)
      : 0;
    const cappedNormalTrim = buffer.type === 'normal' && sameBuffer && pendingNormalScrolls > 0 &&
      buffer.baseY >= TERMINAL_SCROLLBACK_ROWS &&
      buffer.length === TERMINAL_SCROLLBACK_ROWS + this.terminal.rows &&
      previousRows.length === buffer.length;
    let rowOffset = 0;
    if (cappedNormalTrim && buffer.cursorY === this.terminal.rows - 1 && previousRows.length > 1) {
      const candidateOffset = Math.min(pendingNormalScrolls, previousRows.length - 1);
      const candidateRows = previousRows.length - candidateOffset;
      // Validate anchors in the retained scrollback and at the top of the
      // screen before trusting the circular buffer's logical row shift. The
      // active screen is still checked below, so a redraw remains correct.
      const validationRows = new Set([
        0,
        Math.min(Math.max(0, buffer.baseY - 1), candidateRows - 1),
        Math.min(buffer.baseY, candidateRows - 1),
      ]);
      let validShift = candidateOffset > 0 && candidateRows >= buffer.baseY;
      for (const row of validationRows) {
        const previous = previousRows[row + candidateOffset];
        const line = buffer.getLine(row);
        if (previous === undefined || line === undefined || !rowMatches(line, previous, this.terminal.cols, cell)) {
          validShift = false;
          break;
        }
      }
      if (validShift) rowOffset = candidateOffset;
    }
    // Rows already in scrollback at the previous snapshot are immutable
    // history unless the capped circular buffer just trimmed. In that case,
    // trust only the validated shifted identity; an uncertain trim falls back
    // to a full comparison.
    const immutablePrefixEnd = sameBuffer && buffer.type === 'normal' &&
      (!cappedNormalTrim || rowOffset > 0)
      ? Math.min(previousNormalBaseY, buffer.baseY, buffer.length)
      : 0;
    for (let row = 0; row < buffer.length; row += 1) {
      const shiftedPrevious = rowOffset > 0 ? previousRows[row + rowOffset] : previousRows[row];
      if (row < immutablePrefixEnd && shiftedPrevious !== undefined) {
        lines.push(shiftedPrevious);
        continue;
      }
      const line = buffer.getLine(row)!;
      if (shiftedPrevious !== undefined && rowMatches(line, shiftedPrevious, this.terminal.cols, cell)) {
        lines.push(shiftedPrevious);
        continue;
      }
      // The final blank row can remain at its original index after a trim;
      // keep that identity when the shifted candidate was a newly written
      // row and the original row still matches.
      const sameIndexPrevious = rowOffset > 0 ? previousRows[row] : undefined;
      if (sameIndexPrevious !== undefined && rowMatches(line, sameIndexPrevious, this.terminal.cols, cell)) {
        lines.push(sameIndexPrevious);
        continue;
      }
      const cells: TerminalCell[] = [];
      const text: string[] = [];
      for (let column = 0; column < this.terminal.cols; column += 1) {
        line.getCell(column, cell);
        if (cell.getWidth() !== 0) {
          const copied = copyCell(cell, column);
          cells.push(copied);
          text.push(copied.invisible ? ' '.repeat(copied.width) : copied.text);
        }
      }
      lines.push({
        text: text.join(''),
        cells,
        wrapped: line.isWrapped,
      });
    }
    this.lastSnapshotRows = lines;
    this.lastSnapshotBufferType = buffer.type;
    this.lastSnapshotNormalBaseY = buffer.type === 'normal' ? buffer.baseY : undefined;
    this.normalRowsScrolledSinceSnapshot = 0;
    return {
      rows: this.terminal.rows, columns: this.terminal.cols, lines,
      alternate: buffer.type === 'alternate',
      mouseTrackingMode: this.terminal.modes.mouseTrackingMode,
      mouseEncoding: this.mouseEncoding,
      cursor: {row: buffer.baseY + buffer.cursorY, column: Math.min(buffer.cursorX, this.terminal.cols - 1), visible: this.cursorVisible},
    };
  }

  /** Reduce frame churn while the native viewport is being dragged. */
  setScrolling(scrolling: boolean): void {
    if (this.disposed || this.scrolling === scrolling) return;
    this.scrolling = scrolling;
    if (this.publishTimer !== undefined) {
      clearTimeout(this.publishTimer);
      this.publishTimer = undefined;
      this.schedulePublish();
    }
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.callbacks = undefined;
    this.queue = [];
    this.pendingBytes = 0;
    this.invalidateSnapshot();
    if (this.publishTimer !== undefined) clearTimeout(this.publishTimer);
    this.publishTimer = undefined;
    for (const listener of this.listeners) listener.dispose();
    // Public xterm write() owns a non-cancellable timer. Only one <=16 KiB
    // write can be in flight; let it finish silently before disposing core.
    // Its completion never publishes, replies, or pumps more work after dispose.
    if (!this.busy) this.terminal.dispose();
  }

  private pump(): void {
    if (this.disposed || this.busy) return;
    const operation = this.queue.shift();
    if (operation === undefined) return;
    this.busy = true;
    if ('bytes' in operation) {
      this.alternateBufferTouchedDuringWrite = false;
      this.normalScrollsDuringWrite = 0;
      const bytes = operation.byteLength === operation.bytes.length
        ? operation.bytes
        : operation.bytes.subarray(0, operation.byteLength);
      this.combiningMarkTouchedDuringWrite = this.containsCombiningMark(bytes);
      this.terminal.write(bytes, () => {
        if (!this.disposed) this.pendingBytes -= operation.byteLength;
        this.complete();
      });
    } else {
      this.invalidateSnapshot();
      this.terminal.resize(operation.size.columns, operation.size.rows);
      // Serialize native resize calls as well as emulator resize, with no
      // growth beyond the same bounded operation queue.
      if (operation.applied !== undefined) {
        void operation.applied().then(() => this.complete(), () => {
          this.fail('terminal_resize_failed');
          this.complete();
        });
      } else this.complete();
    }
  }

  private complete(): void {
    this.busy = false;
    if (this.disposed) {
      this.terminal.dispose();
      return;
    }
    // A cell can accumulate combining codepoints forever even with bounded
    // rows. Scan the active screen plus normal-buffer rows that just entered
    // scrollback; older scrollback cannot be edited by PTY output. If this
    // bounded write switched screens, check both buffers to cover changes
    // made before the switch within the same parser write.
    const normalBuffer = this.terminal.buffer.normal;
    const scanBothBuffers = this.alternateBufferTouchedDuringWrite;
    const scanForCombiningMarks = scanBothBuffers || this.combiningMarkTouchedDuringWrite;
    const normalScrolls = this.normalScrollsDuringWrite;
    const buffers = scanBothBuffers ? [normalBuffer, this.terminal.buffer.alternate] : [this.terminal.buffer.active];
    this.alternateBufferTouchedDuringWrite = false;
    if (scanForCombiningMarks) {
      const normalRowsToRescan = Math.max(
        this.normalScrollsDuringWrite,
        normalBuffer.baseY - this.lastCheckedNormalBaseY,
      );
      for (const buffer of buffers) {
        const cell = buffer.getNullCell();
        const startRow = buffer.type === 'normal'
          ? normalBuffer.baseY < this.lastCheckedNormalBaseY
            ? 0
            : Math.max(0, normalBuffer.baseY - normalRowsToRescan)
          : 0;
        const endRow = buffer.type === 'normal'
          ? Math.min(buffer.length, normalBuffer.baseY + this.terminal.rows)
          : buffer.length;
        for (let row = startRow; row < endRow; row += 1) {
          const line = buffer.getLine(row)!;
          for (let column = 0; column < line.length; column += 1) {
            if (line.getCell(column, cell)!.getChars().length > TERMINAL_CELL_CODE_UNITS) {
              this.fail('terminal_cell_overflow');
              return;
            }
          }
        }
      }
    }
    this.lastCheckedNormalBaseY = normalBuffer.baseY;
    this.normalScrollsDuringWrite = 0;
    this.combiningMarkTouchedDuringWrite = false;
    this.normalRowsScrolledSinceSnapshot = Math.min(
      MAX_SNAPSHOT_SCROLL_ROWS,
      this.normalRowsScrolledSinceSnapshot + normalScrolls,
    );
    if (!this.terminal.modes.synchronizedOutputMode && this.publishIsSynchronized && this.publishTimer !== undefined) {
      clearTimeout(this.publishTimer);
      this.publishTimer = undefined;
    }
    this.schedulePublish();
    // Do not re-enter xterm.write while its completion callback is executing.
    if (this.queue.length > 0) void Promise.resolve().then(() => this.pump());
  }

  private invalidateSnapshot(): void {
    this.lastSnapshotRows = [];
    this.lastSnapshotBufferType = undefined;
    this.lastSnapshotNormalBaseY = undefined;
    this.normalRowsScrolledSinceSnapshot = 0;
  }

  /**
   * Combining characters are the only unbounded per-cell state that needs a
   * post-write scan. Ordinary OpenCode redraws are ASCII/box-drawing output;
   * avoid walking every cell for those writes while retaining a conservative
   * UTF-8 check for combining ranges (including sequences split at a boundary).
   */
  private containsCombiningMark(bytes: Uint8Array): boolean {
    const combined = this.combiningMarkBytesTail.length === 0
      ? bytes
      : Uint8Array.from([...this.combiningMarkBytesTail, ...bytes]);
    let found = false;
    for (let index = 0; index < combined.length; index += 1) {
      if (this.isCombiningMarkUtf8(combined, index)) {
        found = true;
        break;
      }
    }
    this.combiningMarkBytesTail = Array.from(combined.slice(-3));
    return found;
  }

  private isCombiningMarkUtf8(bytes: Uint8Array, index: number): boolean {
    const first = bytes[index];
    const second = bytes[index + 1];
    const third = bytes[index + 2];
    if (first === 0xcc && second >= 0x80 && second <= 0xbf) return true;
    if (first === 0xcd && second >= 0x80 && second <= 0xaf) return true;
    if (first === 0xe1 && second === 0xaa && third >= 0xb0 && third <= 0xbf) return true;
    if (first === 0xe1 && second === 0xab && third >= 0x80 && third <= 0xbf) return true;
    if (first === 0xe1 && second === 0xb7 && third >= 0x80 && third <= 0xbf) return true;
    if (first === 0xe2 && second === 0x83 && third >= 0x90 && third <= 0xbf) return true;
    if (first === 0xe2 && second === 0x84 && third >= 0x80 && third <= 0xbf) return true;
    if (first === 0xef && second === 0xb8 && third >= 0xa0 && third <= 0xaf) return true;
    return first === 0xf0 && second === 0x9d && third === 0x85 && bytes[index + 3] >= 0xa5 && bytes[index + 3] <= 0xa9;
  }

  private schedulePublish(): void {
    if (this.publishTimer !== undefined) return;
    this.publishIsSynchronized = this.terminal.modes.synchronizedOutputMode;
    const publishInterval = this.scrolling
      ? TERMINAL_SCROLLING_FRAME_PUBLISH_INTERVAL_MS
      : this.terminal.buffer.active.type === 'alternate'
      ? TERMINAL_ALTERNATE_FRAME_PUBLISH_INTERVAL_MS
      : TERMINAL_FRAME_PUBLISH_INTERVAL_MS;
    this.publishTimer = setTimeout(() => {
      this.publishTimer = undefined;
      if (this.disposed) return;
      if (this.terminal.modes.synchronizedOutputMode && Date.now() < this.synchronizedUntil) {
        this.schedulePublish();
        return;
      }
      this.callbacks?.onChange(this.snapshot());
    }, Math.max(
      publishInterval,
      this.terminal.modes.synchronizedOutputMode ? this.synchronizedUntil - Date.now() : 0,
    ));
  }

  private reply(data: string): void {
    if (this.disposed) return;
    if (this.pendingReplies >= MAX_PENDING_REPLIES || this.pendingReplyBytes + data.length > TERMINAL_WRITE_BYTES) {
      this.fail('terminal_reply_overflow');
      return;
    }
    this.pendingReplies += 1;
    this.pendingReplyBytes += data.length;
    void this.callbacks!.onReply(data).catch(() => this.fail('terminal_reply_failed')).finally(() => {
      this.pendingReplies -= 1;
      this.pendingReplyBytes -= data.length;
    });
  }

  private fail(code: string): void {
    const callbacks = this.callbacks;
    this.dispose();
    callbacks?.onError(code);
  }
}
