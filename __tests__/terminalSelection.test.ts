import {
  extendTerminalSelection, selectAllTerminal, terminalCellAt,
  terminalSelectionBetween, terminalSelectionFixedCell,
} from '../src/terminal/terminalSelection';

const word = {startRow: 3, startColumn: 4, endRow: 3, endColumn: 9};

describe('terminal selection', () => {
  it('maps content points to cells and clamps them to the frame', () => {
    expect(terminalCellAt(25, 40, 10, 18, 5, 8)).toEqual({row: 2, column: 2});
    expect(terminalCellAt(-5, -5, 10, 18, 5, 8)).toEqual({row: 0, column: 0});
    expect(terminalCellAt(500, 500, 10, 18, 5, 8)).toEqual({row: 4, column: 7});
    expect(terminalCellAt(Number.NaN, 20, 10, 18, 5, 8)).toEqual({row: 1, column: 0});
  });

  it('keeps the whole long-pressed word while dragging either way', () => {
    expect(extendTerminalSelection(word, {row: 3, column: 6})).toEqual(word);
    expect(extendTerminalSelection(word, {row: 5, column: 2})).toEqual({startRow: 3, startColumn: 4, endRow: 5, endColumn: 3});
    expect(extendTerminalSelection(word, {row: 3, column: 9})).toEqual({startRow: 3, startColumn: 4, endRow: 3, endColumn: 10});
    expect(extendTerminalSelection(word, {row: 1, column: 7})).toEqual({startRow: 1, startColumn: 7, endRow: 3, endColumn: 9});
  });

  it('pins the opposite handle and swaps sides when a handle crosses it', () => {
    const startFixed = terminalSelectionFixedCell(word, 'start');
    expect(startFixed).toEqual({row: 3, column: 8});
    expect(terminalSelectionBetween(startFixed, {row: 2, column: 0})).toEqual({startRow: 2, startColumn: 0, endRow: 3, endColumn: 9});
    expect(terminalSelectionBetween(startFixed, {row: 4, column: 1})).toEqual({startRow: 3, startColumn: 8, endRow: 4, endColumn: 2});

    const endFixed = terminalSelectionFixedCell(word, 'end');
    expect(endFixed).toEqual({row: 3, column: 4});
    expect(terminalSelectionBetween(endFixed, {row: 3, column: 4})).toEqual({startRow: 3, startColumn: 4, endRow: 3, endColumn: 5});
    expect(terminalSelectionBetween(endFixed, {row: 0, column: 0})).toEqual({startRow: 0, startColumn: 0, endRow: 3, endColumn: 5});
  });

  it('selects every row and column of the frozen frame', () => {
    expect(selectAllTerminal(30, 80)).toEqual({startRow: 0, startColumn: 0, endRow: 29, endColumn: 80});
  });
});
