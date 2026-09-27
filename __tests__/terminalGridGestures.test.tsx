import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {nativeKeyboardRestoreOffset, nativeTailOffset, TerminalGrid, TERMINAL_CELL_HEIGHT} from '../src/terminal/TerminalGrid';
import type {TerminalFrame} from '../src/terminal/terminalBuffer';

const frame: TerminalFrame = {
  rows: 24, columns: 80, alternate: true, mouseTrackingMode: 'none', mouseEncoding: 'default', lines: [],
  cursor: {row: 0, column: 0, visible: false},
};

function touch(pageX: number, pageY: number) {
  const point = {identifier: 'finger', pageX, pageY, locationX: pageX, locationY: pageY};
  return {nativeEvent: {...point, changedTouches: [point], touches: [point]}};
}

beforeEach(() => jest.useFakeTimers());
afterEach(async () => {
  await jest.runAllTimersAsync();
  jest.runAllTicks();
  expect(jest.getTimerCount()).toBe(0);
  jest.useRealTimers();
});

test('preserves alternate-buffer offset and only reveals a hidden prompt after keyboard resize', () => {
  // A 12-row viewport; offsets are expressed in rows so the cases hold at any cell height.
  const row = TERMINAL_CELL_HEIGHT;
  const viewportHeight = 12 * row;
  const scrolledOffset = 5 * row + 5;
  expect(nativeKeyboardRestoreOffset({previousOffset: 0, contentRows: 37, viewportHeight, cursorRow: 4})).toBe(0);
  expect(nativeKeyboardRestoreOffset({previousOffset: 0, contentRows: 37, viewportHeight, cursorRow: 14})).toBe(3 * row);
  expect(nativeKeyboardRestoreOffset({previousOffset: scrolledOffset, contentRows: 37, viewportHeight, cursorRow: 4})).toBe(4 * row);
  expect(nativeKeyboardRestoreOffset({previousOffset: scrolledOffset, contentRows: 37, viewportHeight, cursorRow: 8})).toBe(scrolledOffset);
});

test('anchors the normal-buffer tail on the last written row, not the blank grid bottom', () => {
  // A 45-row grid behind a 24-row keyboard-open viewport.
  const row = TERMINAL_CELL_HEIGHT;
  const viewportHeight = 24 * row;
  // Output fills rows 0..14: nothing to scroll, the blank rows stay below.
  expect(nativeTailOffset({contentRows: 45, lastContentRow: 14, cursorRow: 11, viewportHeight})).toBe(0);
  // Output reaches row 39: scroll just far enough to show it.
  expect(nativeTailOffset({contentRows: 45, lastContentRow: 39, cursorRow: 36, viewportHeight})).toBe(16 * row);
  // A cursor below the last text (a fresh blank prompt line) stays visible.
  expect(nativeTailOffset({contentRows: 45, lastContentRow: 20, cursorRow: 30, viewportHeight})).toBe(7 * row);
  // A blank screen and a full grid clamp to the grid's own bounds.
  expect(nativeTailOffset({contentRows: 45, lastContentRow: -1, cursorRow: 0, viewportHeight})).toBe(0);
  expect(nativeTailOffset({contentRows: 45, lastContentRow: 44, cursorRow: 44, viewportHeight})).toBe(21 * row);
});

async function createGrid(alternate = true, mouseTrackingMode: TerminalFrame['mouseTrackingMode'] = 'none', mouseEncoding: TerminalFrame['mouseEncoding'] = 'default') {
  const onSwipe = jest.fn();
  const onMouseWheel = jest.fn();
  const onTap = jest.fn();
  const props = {
    frame: {...frame, alternate, mouseTrackingMode, mouseEncoding}, cellWidth: 8, running: true,
    onCellWidth: jest.fn(), onLayout: jest.fn(), onLinkPress: jest.fn(), onTap, onSwipe, onMouseWheel,
  };
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalGrid {...props} />); });
  const output = () => renderer.root.findByProps({testID: 'terminal-output'}).props;
  const unmount = async () => {
    await ReactTestRenderer.act(async () => {
      renderer.unmount();
      await jest.runAllTimersAsync();
    });
  };
  return {renderer, props, output, onSwipe, onMouseWheel, onTap, unmount};
}

test('keeps a snapshot row key when the row shifts after scrollback trim', async () => {
  const stable = {text: 'stable', cells: [], wrapped: false};
  const tail = {text: 'tail', cells: [], wrapped: false};
  const head = {text: 'head', cells: [], wrapped: false};
  const props = {
    frame: {...frame, alternate: false, lines: [stable, tail]}, cellWidth: 8, running: true,
    onCellWidth: jest.fn(), onLayout: jest.fn(), onLinkPress: jest.fn(), onTap: jest.fn(), onSwipe: jest.fn(),
  };
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => { renderer = ReactTestRenderer.create(<TerminalGrid {...props} />); });
  const firstList = renderer.root.findByProps({testID: 'terminal-output-grid'});
  const stableKey = firstList.props.keyExtractor(stable, 0);

  await ReactTestRenderer.act(async () => {
    renderer.update(<TerminalGrid {...props} frame={{...props.frame, lines: [head, stable]}} />);
  });
  const secondList = renderer.root.findByProps({testID: 'terminal-output-grid'});
  expect(secondList.props.keyExtractor(stable, 1)).toBe(stableKey);
  expect(secondList.props.keyExtractor(head, 0)).not.toBe(stableKey);
  await ReactTestRenderer.act(async () => { renderer.unmount(); });
});

test.each([['down', 1], ['up', -1]] as const)('pages %s during one continuing drag without a duplicate on release', async (direction, sign) => {
  const grid = await createGrid();
  grid.output().onTouchStart(touch(40, 400));
  grid.output().onTouchMove(touch(40, 400 + sign * 30));
  expect(grid.onSwipe.mock.calls).toEqual([[direction]]);
  jest.advanceTimersByTime(120);
  grid.output().onTouchMove(touch(40, 400 + sign * 150));
  jest.advanceTimersByTime(120);
  grid.output().onTouchMove(touch(40, 400 + sign * 270));
  expect(grid.onSwipe.mock.calls).toEqual([[direction], [direction], [direction]]);
  grid.output().onTouchEnd(touch(40, 400 + sign * 270));
  grid.output().onResponderRelease(touch(40, 400 + sign * 270));
  expect(grid.onSwipe).toHaveBeenCalledTimes(3);
  expect(grid.onTap).not.toHaveBeenCalled();
  await grid.unmount();
});

test('reports protocol-matched wheel gestures when the alternate screen enables mouse tracking', async () => {
  const grid = await createGrid(true, 'vt200', 'sgr');
  grid.output().onTouchStart(touch(80, 100));
  grid.output().onTouchMove(touch(80, 130));
  expect(grid.onMouseWheel).toHaveBeenCalledWith({direction: 'up', column: 11, row: 8, encoding: 'sgr'});
  expect(grid.onSwipe).not.toHaveBeenCalled();
  jest.advanceTimersByTime(32);
  grid.output().onTouchMove(touch(80, 170));
  expect(grid.onMouseWheel).toHaveBeenCalledTimes(2);
  grid.output().onTouchEnd(touch(80, 170));
  expect(grid.onMouseWheel).toHaveBeenCalledTimes(2);
  await grid.unmount();
});

test('keeps PageUp/PageDown fallback for pixel mouse encoding', async () => {
  const grid = await createGrid(true, 'any', 'sgrPixels');
  grid.output().onTouchStart(touch(80, 100));
  grid.output().onTouchMove(touch(80, 130));
  expect(grid.onSwipe.mock.calls).toEqual([['down']]);
  expect(grid.onMouseWheel).not.toHaveBeenCalled();
  await grid.unmount();
});

test.each([['down', 1], ['up', -1]] as const)('consumes one final %s page after missed moves without catch-up or duplicate release', async (direction, sign) => {
  const grid = await createGrid();
  grid.output().onTouchStart(touch(40, 400));
  grid.output().onResponderMove(touch(40, 400 + sign * 30));
  expect(grid.onSwipe.mock.calls).toEqual([[direction]]);
  jest.advanceTimersByTime(120);
  const release = touch(40, 400 + sign * 10000);
  grid.output().onResponderRelease(release);
  grid.output().onTouchEnd(release);
  expect(grid.onSwipe.mock.calls).toEqual([[direction], [direction]]);
  expect(grid.onTap).not.toHaveBeenCalled();
  jest.advanceTimersByTime(10000);
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  await grid.unmount();
});

test('keeps release paging within the repeat interval limit', async () => {
  const grid = await createGrid();
  grid.output().onTouchStart(touch(40, 0));
  grid.output().onResponderMove(touch(40, 30));
  jest.advanceTimersByTime(119);
  grid.output().onResponderRelease(touch(40, 10000));
  expect(grid.onSwipe.mock.calls).toEqual([['down']]);
  jest.advanceTimersByTime(10000);
  expect(grid.onSwipe).toHaveBeenCalledTimes(1);
  expect(grid.onTap).not.toHaveBeenCalled();
  await grid.unmount();
});

test('bounds pages by movement and elapsed time without catch-up or stationary repeats', async () => {
  const grid = await createGrid();
  const initialTimers = jest.getTimerCount();
  grid.output().onTouchStart(touch(40, 0));
  grid.output().onTouchMove(touch(40, 10000));
  expect(grid.onSwipe).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(initialTimers);
  for (let y = 10100; y <= 11000; y += 100) grid.output().onTouchMove(touch(40, y));
  expect(grid.onSwipe).toHaveBeenCalledTimes(1);
  jest.advanceTimersByTime(120);
  grid.output().onTouchMove(touch(40, 11100));
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  jest.advanceTimersByTime(120);
  grid.output().onTouchMove(touch(40, 11110));
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  jest.advanceTimersByTime(10000);
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  grid.output().onTouchEnd(touch(40, 11110));
  grid.output().onTouchMove(touch(40, 12000));
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  await grid.unmount();
});

test('ignores a gesture that begins with multiple touches', async () => {
  const grid = await createGrid();
  const start = touch(40, 0);
  start.nativeEvent.touches.push({identifier: 'second-finger', pageX: 44, pageY: 4, locationX: 44, locationY: 4});
  grid.output().onTouchStart(start);
  grid.output().onTouchMove(touch(40, 100));
  grid.output().onTouchEnd(touch(40, 100));
  expect(grid.onSwipe).not.toHaveBeenCalled();
  expect(grid.onTap).not.toHaveBeenCalled();
  await grid.unmount();
});

test('keeps native history scrolling and horizontal or returning drags from paging or focusing', async () => {
  const grid = await createGrid(false);
  expect(grid.renderer.root.findByProps({testID: 'terminal-output-grid'}).props.scrollEnabled).toBe(true);
  grid.output().onTouchStart(touch(40, 400));
  grid.output().onTouchMove(touch(40, 100));
  grid.output().onTouchEnd(touch(40, 400));
  expect(grid.onSwipe).not.toHaveBeenCalled();
  expect(grid.onTap).not.toHaveBeenCalled();
  await ReactTestRenderer.act(async () => { grid.renderer.update(<TerminalGrid {...grid.props} frame={frame} />); });
  grid.output().onTouchStart(touch(40, 400));
  grid.output().onTouchMove(touch(200, 410));
  grid.output().onTouchEnd(touch(200, 410));
  expect(grid.onSwipe).not.toHaveBeenCalled();
  expect(grid.onTap).not.toHaveBeenCalled();
  grid.output().onTouchStart(touch(40, 400));
  grid.output().onTouchEnd(touch(41, 401));
  expect(grid.onTap).toHaveBeenCalledTimes(1);
  await grid.unmount();
});

test('clears cancelled, buffer-switched, and unmounted drags without pending input or timers', async () => {
  const grid = await createGrid();
  grid.output().onTouchStart(touch(40, 0));
  grid.output().onTouchMove(touch(40, 30));
  grid.output().onTouchCancel();
  jest.advanceTimersByTime(120);
  grid.output().onTouchMove(touch(40, 200));
  grid.output().onTouchEnd(touch(40, 200));
  expect(grid.onSwipe).toHaveBeenCalledTimes(1);
  grid.output().onTouchStart(touch(40, 0));
  await ReactTestRenderer.act(async () => {
    grid.renderer.update(<TerminalGrid {...grid.props} frame={{...frame, alternate: false}} />);
  });
  grid.output().onTouchEnd(touch(40, 0));
  expect(grid.onTap).not.toHaveBeenCalled();
  await ReactTestRenderer.act(async () => { grid.renderer.update(<TerminalGrid {...grid.props} />); });
  const handlers = grid.output();
  handlers.onTouchStart(touch(40, 0));
  handlers.onTouchMove(touch(40, 30));
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  await grid.unmount();
  jest.advanceTimersByTime(10000);
  handlers.onTouchMove(touch(40, 200));
  handlers.onTouchEnd(touch(40, 200));
  expect(grid.onSwipe).toHaveBeenCalledTimes(2);
  expect(grid.onTap).not.toHaveBeenCalled();
});

test('keeps responder paging across a TUI redraw when child touch events no longer bubble', async () => {
  const grid = await createGrid();
  await ReactTestRenderer.act(async () => {
    grid.renderer.update(<TerminalGrid {...grid.props} frame={{...frame, lines: [{
      text: 'o', wrapped: false,
      cells: [{
        text: 'o', column: 0, width: 1, foreground: '#ffffff', background: '#000000',
        bold: false, italic: false, dim: false, underline: false, strikethrough: false, invisible: false,
      }],
    }]}} />);
  });
  expect(grid.renderer.root.findAll(node => typeof node.props.testID === 'string' && node.props.testID.startsWith('terminal-cell-')).length).toBeGreaterThan(0);
  expect(grid.output().onStartShouldSetResponder()).toBe(true);
  grid.output().onTouchStart(touch(40, 0));
  grid.output().onResponderMove(touch(40, 30));
  // React also bubbles this move while the original child is still mounted.
  grid.output().onTouchMove(touch(40, 30));
  expect(grid.onSwipe.mock.calls).toEqual([['down']]);
  await ReactTestRenderer.act(async () => {
    grid.renderer.update(<TerminalGrid {...grid.props} frame={{...frame, lines: [{text: 'redrawn', cells: [], wrapped: false}]}} />);
  });
  expect(grid.renderer.root.findAll(node => typeof node.props.testID === 'string' && node.props.testID.startsWith('terminal-cell-'))).toHaveLength(0);
  await ReactTestRenderer.act(async () => {
    jest.advanceTimersByTime(120);
    grid.output().onResponderMove(touch(40, 150));
    jest.advanceTimersByTime(120);
    grid.output().onResponderMove(touch(40, 270));
  });
  grid.output().onResponderRelease(touch(40, 270));
  grid.output().onTouchEnd(touch(40, 270));
  expect(grid.onSwipe.mock.calls).toEqual([['down'], ['down'], ['down']]);
  expect(grid.onTap).not.toHaveBeenCalled();

  grid.output().onTouchStart(touch(40, 0));
  grid.output().onResponderTerminate();
  jest.advanceTimersByTime(120);
  grid.output().onResponderMove(touch(40, 150));
  grid.output().onResponderRelease(touch(40, 150));
  expect(grid.onSwipe).toHaveBeenCalledTimes(3);
  await ReactTestRenderer.act(async () => {
    grid.renderer.update(<TerminalGrid {...grid.props} frame={{...frame, alternate: false}} />);
  });
  expect(grid.output().onStartShouldSetResponder()).toBe(false);
  await grid.unmount();
});

test('captures vertical drags from child links while preserving taps, horizontal gestures, and native scrolling', async () => {
  const grid = await createGrid();
  const capture = () => grid.output().onMoveShouldSetResponderCapture;
  expect(capture()(touch(40, 430))).toBe(false);
  grid.output().onTouchStart(touch(40, 400));
  expect(capture()(touch(41, 401))).toBe(false);
  expect(capture()(touch(40, 412))).toBe(false);
  expect(capture()(touch(100, 430))).toBe(false);
  expect(capture()(touch(40, 413))).toBe(true);
  expect(capture()(touch(40, 387))).toBe(true);
  expect(grid.onSwipe).not.toHaveBeenCalled();
  expect(grid.onTap).not.toHaveBeenCalled();

  // Capture transfers the link's responder; both responder and bubbling
  // dispatches for the same move must still produce only one page.
  const move = touch(40, 430);
  expect(capture()(move)).toBe(true);
  grid.output().onResponderMove(move);
  grid.output().onTouchMove(move);
  grid.output().onResponderRelease(move);
  grid.output().onTouchEnd(move);
  expect(grid.onSwipe.mock.calls).toEqual([['down']]);
  expect(grid.onTap).not.toHaveBeenCalled();
  expect(capture()(touch(40, 600))).toBe(false);

  await ReactTestRenderer.act(async () => {
    grid.renderer.update(<TerminalGrid {...grid.props} frame={{...frame, alternate: false}} />);
  });
  grid.output().onTouchStart(touch(40, 400));
  expect(capture()(touch(40, 500))).toBe(false);
  await grid.unmount();
});
