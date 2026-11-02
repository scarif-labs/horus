import {terminalTextFromRows, TERMINAL_TEXT_MAX_CHARACTERS} from '../src/terminal/terminalText';

describe('terminalTextFromRows', () => {
  test('joins soft-wrapped rows, keeps hard newlines, and trims terminal padding', () => {
    expect(terminalTextFromRows([
      {text: 'first   ', wrapped: false},
      {text: '  continuation   ', wrapped: true},
      {text: 'last   ', wrapped: false},
      {text: '   ', wrapped: false},
    ])).toEqual({text: 'first     continuation\nlast', truncated: false});
  });

  test('keeps the newest whole lines when the selectable text limit is exceeded', () => {
    const rows = Array.from({length: 300}, (_, index) => ({text: `${String(index).padStart(3, '0')}${'x'.repeat(997)}`, wrapped: false}));
    const snapshot = terminalTextFromRows(rows);
    expect(snapshot.truncated).toBe(true);
    expect(snapshot.text.length).toBeLessThanOrEqual(TERMINAL_TEXT_MAX_CHARACTERS);
    expect(snapshot.text).toContain('299');
    expect(snapshot.text).not.toContain('000');
    expect(snapshot.text.startsWith('\n')).toBe(false);
  });
});
