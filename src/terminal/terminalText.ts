import type {TerminalRow} from './terminalBuffer';

/** Keep the selectable view bounded even when an app fills every cell with combining marks. */
export const TERMINAL_TEXT_MAX_CHARACTERS = 262_144;

export type TerminalTextSnapshot = Readonly<{text: string; truncated: boolean}>;

/**
 * Builds plain text in terminal row order. A row marked wrapped continues the
 * previous row; other rows begin a new line. Keep the newest text if it exceeds
 * the selection-view limit.
 */
export function terminalTextFromRows(rows: readonly Pick<TerminalRow, 'text' | 'wrapped'>[]): TerminalTextSnapshot {
  let text = '';
  let truncated = false;
  rows.forEach((row, index) => {
    const content = rows[index + 1]?.wrapped === true ? row.text : row.text.trimEnd();
    const piece = `${index > 0 && !row.wrapped ? '\n' : ''}${content}`;
    text += piece;
    while (text.length > TERMINAL_TEXT_MAX_CHARACTERS) {
      const desiredStart = text.length - TERMINAL_TEXT_MAX_CHARACTERS;
      const nextLine = text.indexOf('\n', desiredStart);
      let start = nextLine >= 0 && nextLine + 1 < text.length ? nextLine + 1 : desiredStart;
      if (start > 0 && start < text.length && /[\uDC00-\uDFFF]/.test(text[start])) start += 1;
      text = text.slice(start);
      truncated = true;
    }
  });
  return {text: text.replace(/\n+$/, ''), truncated};
}
