import type {TerminalSelectionRange} from '../native/NativeTerminalRuntime';

export type {TerminalSelectionRange};

export type TerminalCellPoint = Readonly<{row: number; column: number}>;

/** True when (aRow, aColumn) comes before (bRow, bColumn) in reading order. */
function before(aRow: number, aColumn: number, bRow: number, bColumn: number): boolean {
  return aRow < bRow || (aRow === bRow && aColumn < bColumn);
}

/** The cell under a point in content coordinates, clamped to the frame. */
export function terminalCellAt(
  x: number,
  y: number,
  cellWidth: number,
  cellHeight: number,
  contentRows: number,
  columns: number,
): TerminalCellPoint {
  const row = Math.floor((Number.isFinite(y) ? y : 0) / Math.max(cellHeight, 1));
  const column = Math.floor((Number.isFinite(x) ? x : 0) / Math.max(cellWidth, 1));
  return {
    row: Math.max(0, Math.min(Math.max(contentRows - 1, 0), row)),
    column: Math.max(0, Math.min(Math.max(columns - 1, 0), column)),
  };
}

/**
 * Grows the long-pressed word [anchor] to reach [point], keeping the whole
 * word selected: dragging back extends the start, dragging on extends the end.
 */
export function extendTerminalSelection(anchor: TerminalSelectionRange, point: TerminalCellPoint): TerminalSelectionRange {
  if (before(point.row, point.column, anchor.startRow, anchor.startColumn)) {
    return {startRow: point.row, startColumn: point.column, endRow: anchor.endRow, endColumn: anchor.endColumn};
  }
  if (!before(point.row, point.column, anchor.endRow, anchor.endColumn)) {
    return {startRow: anchor.startRow, startColumn: anchor.startColumn, endRow: point.row, endColumn: point.column + 1};
  }
  return anchor;
}

/** The cell under the handle opposite [edge], which stays put while [edge] is dragged. */
export function terminalSelectionFixedCell(range: TerminalSelectionRange, edge: 'start' | 'end'): TerminalCellPoint {
  return edge === 'start'
    ? {row: range.endRow, column: Math.max(0, range.endColumn - 1)}
    : {row: range.startRow, column: range.startColumn};
}

/** Every cell from [a] to [b] inclusive, in either order. */
export function terminalSelectionBetween(a: TerminalCellPoint, b: TerminalCellPoint): TerminalSelectionRange {
  const [first, last] = before(b.row, b.column, a.row, a.column) ? [b, a] : [a, b];
  return {startRow: first.row, startColumn: first.column, endRow: last.row, endColumn: last.column + 1};
}

export function sameTerminalSelection(a: TerminalSelectionRange, b: TerminalSelectionRange): boolean {
  return a.startRow === b.startRow && a.startColumn === b.startColumn && a.endRow === b.endRow && a.endColumn === b.endColumn;
}

/** Every row and column of a frozen frame. */
export function selectAllTerminal(contentRows: number, columns: number): TerminalSelectionRange {
  return {startRow: 0, startColumn: 0, endRow: Math.max(0, contentRows - 1), endColumn: Math.max(0, columns)};
}
