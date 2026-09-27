import {terminalMouseWheelSequence} from '../src/terminal/terminalMouse';

test('encodes one-based SGR wheel reports', () => {
  expect(terminalMouseWheelSequence({direction: 'up', column: 4, row: 7, encoding: 'sgr'})).toBe('\u001b[<64;4;7M');
  expect(terminalMouseWheelSequence({direction: 'down', column: 12, row: 3, encoding: 'sgr'})).toBe('\u001b[<65;12;3M');
});

test('encodes legacy wheel reports when SGR is not enabled', () => {
  expect(terminalMouseWheelSequence({direction: 'up', column: 4, row: 7, encoding: 'default'})).toBe('\u001b[M`$\'');
});

test('bounds invalid and oversized mouse coordinates', () => {
  expect(terminalMouseWheelSequence({direction: 'up', column: Number.NaN, row: 0, encoding: 'sgr'})).toBe('\u001b[<64;1;1M');
  expect(terminalMouseWheelSequence({direction: 'down', column: 4000, row: 1000, encoding: 'sgr'})).toBe('\u001b[<65;999;999M');
  expect(terminalMouseWheelSequence({direction: 'down', column: 4000, row: 1000, encoding: 'default'})).toBe('\u001b[Maÿÿ');
});
