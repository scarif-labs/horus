export type TerminalMouseWheelDirection = 'up' | 'down';
export type TerminalMouseEncoding = 'default' | 'sgr' | 'sgrPixels';

export type TerminalMouseWheel = Readonly<{
  direction: TerminalMouseWheelDirection;
  column: number;
  row: number;
  encoding: Exclude<TerminalMouseEncoding, 'sgrPixels'>;
}>;

function boundedCoordinate(value: number, maximum: number): number {
  if (!Number.isFinite(value)) return 1;
  return Math.max(1, Math.min(maximum, Math.floor(value)));
}

/** Encode a wheel report using the protocol the TUI requested. */
export function terminalMouseWheelSequence({direction, column, row, encoding}: TerminalMouseWheel): string {
  const button = direction === 'up' ? 64 : 65;
  const boundedColumn = boundedCoordinate(column, encoding === 'sgr' ? 999 : 223);
  const boundedRow = boundedCoordinate(row, encoding === 'sgr' ? 999 : 223);
  if (encoding === 'sgr') return `\u001b[<${button};${boundedColumn};${boundedRow}M`;
  return `\u001b[M${String.fromCharCode(button + 32, boundedColumn + 32, boundedRow + 32)}`;
}
