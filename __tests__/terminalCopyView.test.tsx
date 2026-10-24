import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import {BackHandler} from 'react-native';
import {TerminalCopyView} from '../src/terminal/TerminalCopyView';
import {terminalRowsText, type TerminalRow} from '../src/terminal/terminalBuffer';

function row(text: string, wrapped = false): TerminalRow {
  return {text, cells: [], wrapped};
}

test('joins soft-wrapped rows and trims trailing blanks', () => {
  expect(terminalRowsText([
    row('$ echo https://example.com/a-very-long'),
    row('/path    ', true),
    row('  indented   '),
    row('    '),
    row('$ '),
  ])).toBe('$ echo https://example.com/a-very-long/path\n  indented\n\n$');
  expect(terminalRowsText([])).toBe('');
});

test('shows the terminal text as selectable text and closes on Done and Back', async () => {
  const onClose = jest.fn();
  let backHandler: Parameters<typeof BackHandler.addEventListener>[1] | undefined;
  const remove = jest.fn();
  const spy = jest.spyOn(BackHandler, 'addEventListener').mockImplementation((eventName, handler) => {
    if (eventName === 'hardwareBackPress') backHandler = handler;
    return {remove};
  });
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<TerminalCopyView onClose={onClose} text={'line one\nline two'} />);
  });
  const text = renderer.root.findByProps({testID: 'terminal-copy-text'});
  expect(text.props.selectable).toBe(true);
  expect(text.props.children).toBe('line one\nline two');

  await ReactTestRenderer.act(async () => { renderer.root.findByProps({testID: 'terminal-copy-close'}).props.onPress(); });
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(backHandler?.({} as never)).toBe(true);
  expect(onClose).toHaveBeenCalledTimes(2);
  await ReactTestRenderer.act(async () => { renderer.unmount(); });
  expect(remove).toHaveBeenCalled();
  spy.mockRestore();
});

test('says when there is nothing to copy', async () => {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<TerminalCopyView onClose={jest.fn()} text="" />);
  });
  expect(renderer.root.findAllByProps({testID: 'terminal-copy-text'})).toHaveLength(0);
  expect(renderer.root.findByProps({testID: 'terminal-copy-empty'})).toBeTruthy();
  await ReactTestRenderer.act(async () => { renderer.unmount(); });
});
