import React from 'react';
import {BackHandler, FlatList, Platform, Pressable, ScrollView, StyleSheet, Text, View, type GestureResponderEvent, type LayoutChangeEvent, type NativeTouchEvent} from 'react-native';
import {UI_FONT_FAMILY} from '../ui/typography';
import {NativeTerminalCanvas, type NativeTerminalFrameMeta, type NativeTerminalLinkRange} from '../native/NativeTerminalCanvas';
import {
  TERMINAL_BACKGROUND, TERMINAL_FOREGROUND,
  type TerminalCell, type TerminalFrame, type TerminalRow,
} from './terminalBuffer';
import {findTrustedTerminalLinks, type TerminalLinkMatch} from './terminalLinks';
import type {TerminalMouseWheel} from './terminalMouse';
import {uiColors} from './palette';

export const TERMINAL_FONT_SIZE = 12;
export const TERMINAL_CELL_HEIGHT = 18;
const TERMINAL_LINK_FOREGROUND = uiColors.accent;
const FONT_SAMPLE = 'MMMMMMMMMMMMMMMMMMMM';
const TAP_SLOP = 12;
const SWIPE_DISTANCE = 24;
const SWIPE_REPEAT_DISTANCE = 96;
const SWIPE_REPEAT_INTERVAL_MS = 120;
const MOUSE_WHEEL_DISTANCE = 24;
const MOUSE_WHEEL_INTERVAL_MS = 32;
const ASCII_CELL_TEXT = /^[\x20-\x7e]+$/;

type SwipeDirection = 'up' | 'down';
type TerminalTouch = Pick<NativeTouchEvent, 'identifier' | 'pageX' | 'pageY'> & {
  dragged: boolean;
  paged: boolean;
  lastPageY: number;
  lastPageAt: number;
  mouseTrackingMode: TerminalFrame['mouseTrackingMode'];
  mouseEncoding: TerminalFrame['mouseEncoding'];
};

export type NativeScreenState = Readonly<{hasContent: boolean; alternate: boolean; mouseTracking: boolean}>;

type Props = Readonly<{
  frame: TerminalFrame | undefined;
  nativeSessionId?: string;
  nativeRows?: number;
  nativeColumns?: number;
  cellWidth: number;
  running: boolean;
  placeholder?: string;
  /** Native sessions only: whether the screen shows text, and on which screen. */
  onNativeScreenChange?: (screen: NativeScreenState) => void;
  onCellWidth: (width: number) => void;
  onLayout: (event: LayoutChangeEvent) => void;
  onLinkPress: (url: string) => void;
  /** A tap that was not a link; it does not open the keyboard (the key bar does). */
  onTap?: () => void;
  onSwipe: (direction: SwipeDirection) => void;
  onMouseWheel?: (event: TerminalMouseWheel) => void;
  onScrollStateChange?: (scrolling: boolean) => void;
}>;

type TerminalRowKeyState = {
  keys: WeakMap<TerminalRow, string>;
  next: number;
};

export type PositionedTerminalLink = TerminalLinkMatch & Readonly<{startColumn: number; endColumn: number}>;
/**
 * Memoizes one wrapped row group's link scan, keyed by the group's first row
 * object. An entry stays valid only while the exact same row identities form
 * the group, so unchanged groups reuse their link arrays by reference and
 * keep the memoized row view from re-rendering while output streams.
 */
export type TerminalLinkScanCache = WeakMap<TerminalRow, {
  rows: readonly TerminalRow[];
  linksByRow: ReadonlyMap<TerminalRow, readonly PositionedTerminalLink[]>;
}>;
type TerminalRowViewProps = Readonly<{
  row: TerminalRow;
  rowKey: string;
  columns: number;
  cellWidth: number;
  links: readonly PositionedTerminalLink[];
  onLinkPress: (url: string) => void;
  cursorColumn: number;
}>;
type TerminalCellRun = {
  cell: TerminalCell;
  text: string;
  columns: number;
  linked: boolean;
  ascii: boolean;
};

const NO_LINKS: readonly PositionedTerminalLink[] = [];
const NO_NATIVE_LINKS: readonly NativeTerminalLinkRange[] = [];

function sameCellStyle(left: TerminalCell, right: TerminalCell): boolean {
  return left.foreground === right.foreground && left.background === right.background &&
    left.bold === right.bold && left.italic === right.italic && left.dim === right.dim &&
    left.underline === right.underline && left.strikethrough === right.strikethrough &&
    left.invisible === right.invisible;
}

function terminalCellRuns(row: TerminalRow, links: readonly PositionedTerminalLink[]): TerminalCellRun[] {
  const runs: TerminalCellRun[] = [];
  for (const cell of row.cells) {
    if (cell.text === ' ' && cell.background === TERMINAL_BACKGROUND && !cell.underline && !cell.strikethrough) continue;
    const linked = links.some(link => cell.column < link.endColumn && cell.column + cell.width > link.startColumn);
    const ascii = cell.width === 1 && !cell.invisible && ASCII_CELL_TEXT.test(cell.text);
    const previous = runs[runs.length - 1];
    if (ascii && previous !== undefined && previous.ascii && previous.linked === linked &&
      previous.cell.column + previous.columns === cell.column && sameCellStyle(previous.cell, cell)) {
      previous.text += cell.text;
      previous.columns += 1;
    } else {
      runs.push({cell, text: cell.invisible ? ' ' : cell.text, columns: cell.width, linked, ascii});
    }
  }
  return runs;
}

function columnAtTextOffset(row: TerminalRow, offset: number): number {
  let textOffset = 0;
  for (const cell of row.cells) {
    const visibleText = cell.invisible ? ' '.repeat(cell.width) : cell.text;
    const nextOffset = textOffset + visibleText.length;
    if (offset < nextOffset) return cell.column;
    if (offset === nextOffset) return cell.column + cell.width;
    textOffset = nextOffset;
  }
  const lastCell = row.cells[row.cells.length - 1];
  return lastCell === undefined ? 0 : lastCell.column + lastCell.width;
}

export function positionTerminalLinks(
  frame: TerminalFrame | undefined,
  cache: TerminalLinkScanCache,
): ReadonlyMap<number, readonly PositionedTerminalLink[]> {
  const linksByRow = new Map<number, readonly PositionedTerminalLink[]>();
  if (frame === undefined) return linksByRow;
  const lines = frame.lines;
  // A group starts at a non-wrapped row (or the first frame row) and includes
  // every wrapped continuation row after it, exactly as before this cache.
  for (let groupStart = 0; groupStart < lines.length; ) {
    let groupEnd = groupStart + 1;
    while (groupEnd < lines.length && lines[groupEnd].wrapped) groupEnd += 1;
    const groupRows = lines.slice(groupStart, groupEnd);
    const entry = cache.get(groupRows[0]);
    // Clean only when the group is the same rows in the same order; any
    // replaced member or changed composition forces a rescan.
    let groupLinks = entry !== undefined &&
      entry.rows.length === groupRows.length &&
      entry.rows.every((row, index) => row === groupRows[index])
      ? entry.linksByRow
      : undefined;
    if (groupLinks === undefined) {
      const scannedLinks = new Map<TerminalRow, PositionedTerminalLink[]>();
      let groupTail = '';
      let groupMayContainUrl = false;
      for (const row of groupRows) {
        if (!groupMayContainUrl && `${groupTail}${row.text}`.includes('://')) {
          groupMayContainUrl = true;
        }
        groupTail = `${groupTail}${row.text}`.slice(-2);
      }
      if (groupMayContainUrl) {
        const groupText = groupRows.map(row => row.text).join('');
        for (const link of findTrustedTerminalLinks(groupText)) {
          let sourceStart = 0;
          for (const sourceRow of groupRows) {
            const sourceEnd = sourceStart + sourceRow.text.length;
            const start = Math.max(link.startIndex, sourceStart);
            const end = Math.min(link.endIndex, sourceEnd);
            const offset = sourceStart;
            sourceStart = sourceEnd;
            if (end <= start) continue;
            const positioned: PositionedTerminalLink = {
              ...link,
              startColumn: columnAtTextOffset(sourceRow, start - offset),
              endColumn: columnAtTextOffset(sourceRow, end - offset),
            };
            if (positioned.endColumn > positioned.startColumn) {
              const rowLinks = scannedLinks.get(sourceRow) ?? [];
              rowLinks.push(positioned);
              scannedLinks.set(sourceRow, rowLinks);
            }
          }
        }
      }
      cache.set(groupRows[0], {rows: groupRows, linksByRow: scannedLinks});
      groupLinks = scannedLinks;
    }
    for (let index = 0; index < groupRows.length; index += 1) {
      const rowLinks = groupLinks.get(groupRows[index]);
      if (rowLinks !== undefined) linksByRow.set(groupStart + index, rowLinks);
    }
    groupStart = groupEnd;
  }
  return linksByRow;
}

const TerminalRowView = React.memo(({row, rowKey, columns, cellWidth, links, onLinkPress, cursorColumn}: TerminalRowViewProps): React.JSX.Element => {
  const cellRuns = terminalCellRuns(row, links);
  return (
    <View
      accessible={links.length === 0}
      accessibilityLabel={row.text}
      style={{height: TERMINAL_CELL_HEIGHT, width: columns * cellWidth}}
      testID={`terminal-row-${rowKey}`}>
      {cellRuns.map(({cell, columns: runColumns, linked, text}) => (
        <Text
          key={cell.column}
          accessible={false}
          allowFontScaling={false}
          numberOfLines={1}
          ellipsizeMode="clip"
          testID={`terminal-cell-${rowKey}-${cell.column}`}
          style={[
            styles.glyph,
            {
              left: cell.column * cellWidth, width: runColumns * cellWidth,
              color: linked ? TERMINAL_LINK_FOREGROUND : cell.dim ? `${cell.foreground}80` : cell.foreground,
              backgroundColor: cell.background,
            },
            cell.bold && styles.bold,
            cell.italic && styles.italic,
            cell.underline && cell.strikethrough ? styles.underlineStrike : cell.underline ? styles.underline : cell.strikethrough ? styles.strike : undefined,
            linked && styles.linkText,
          ]}>{text}</Text>
      ))}
      {links.map((link, linkIndex) => (
        <Pressable
          key={link.startIndex}
          accessibilityLabel={link.url}
          accessibilityRole="link"
          onPress={() => onLinkPress(link.url)}
          style={[styles.linkTarget, {left: link.startColumn * cellWidth, width: (link.endColumn - link.startColumn) * cellWidth}]}
          testID={`terminal-link-${rowKey}-${linkIndex}`}
        />
      ))}
      {cursorColumn >= 0 ? (
        <View
          pointerEvents="none"
          testID="terminal-cursor"
          style={[styles.cursor, {left: cursorColumn * cellWidth, width: cellWidth}]}
        />
      ) : null}
    </View>
  );
});

/** Each glyph occupies the parser's columns, never Android's wrapped prose. */
export function TerminalGrid(props: Props): React.JSX.Element {
  const nativeSession = Platform.OS === 'android' && !Platform.isTesting && props.nativeSessionId !== undefined;
  return nativeSession ? <NativeTerminalGrid {...props} /> : <SnapshotTerminalGrid {...props} />;
}

/**
 * A native session: one canvas that draws libghostty frames and handles every
 * touch itself (scroll, fling, wheel, selection, links), so nothing waits on JS.
 */
function NativeTerminalGrid({nativeSessionId, nativeRows = 24, nativeColumns = 80, cellWidth, running, onNativeScreenChange, onCellWidth, onLayout, onLinkPress, onTap}: Props): React.JSX.Element {
  const nativeScreen = React.useRef<NativeScreenState>({hasContent: false, alternate: false, mouseTracking: false});
  const [selectionActive, setSelectionActive] = React.useState(false);
  const [selectionClearRequest, setSelectionClearRequest] = React.useState(0);

  // A restarted app is a new session on the same screen. The screen resets
  // its copy of the native screen state, so forget ours too: otherwise a new
  // full-screen app matching the old one is never reported, the screen keeps
  // the PTY at full height under the keyboard, and the app's top is hidden.
  React.useEffect(() => {
    nativeScreen.current = {hasContent: false, alternate: false, mouseTracking: false};
    setSelectionActive(false);
  }, [nativeSessionId]);

  React.useEffect(() => {
    if (!selectionActive) return undefined;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setSelectionClearRequest(value => value >= Number.MAX_SAFE_INTEGER ? 1 : value + 1);
      return true;
    });
    return () => subscription.remove();
  }, [selectionActive]);

  const handleNativeFrameMeta = React.useCallback((event: {nativeEvent: NativeTerminalFrameMeta}) => {
    const {alternate, lastContentRow, mouseTracking} = event.nativeEvent;
    const hasContent = Number.isInteger(lastContentRow) && lastContentRow >= 0;
    const current = nativeScreen.current;
    if (hasContent === current.hasContent && alternate === current.alternate && mouseTracking === current.mouseTracking) return;
    nativeScreen.current = {hasContent, alternate, mouseTracking};
    onNativeScreenChange?.(nativeScreen.current);
  }, [onNativeScreenChange]);

  return (
    <View style={styles.root} onLayout={onLayout} testID="terminal-output">
      <FontMeasure onCellWidth={onCellWidth} />
      <NativeTerminalCanvas
        accessible={false}
        sessionId={nativeSessionId}
        nativeRows={nativeRows}
        nativeColumns={nativeColumns}
        cellWidth={cellWidth}
        cellHeight={TERMINAL_CELL_HEIGHT}
        fontSize={TERMINAL_FONT_SIZE}
        links={NO_NATIVE_LINKS}
        running={running}
        selectionClearRequest={selectionClearRequest}
        onNativeFrameMeta={handleNativeFrameMeta}
        onTerminalLink={event => onLinkPress(event.nativeEvent.url)}
        onTerminalTap={onTap}
        onTerminalSelection={event => setSelectionActive(event.nativeEvent.active)}
        style={StyleSheet.absoluteFill}
        testID="terminal-native-canvas"
      />
    </View>
  );
}

/** Measures one monospace cell from a run of glyphs. */
function FontMeasure({onCellWidth}: Readonly<{onCellWidth: (width: number) => void}>): React.JSX.Element {
  return (
    <Text
      accessible={false}
      allowFontScaling={false}
      numberOfLines={1}
      style={[styles.glyph, styles.measure]}
      testID="terminal-font-measure"
      onTextLayout={event => {
        const width = event.nativeEvent.lines[0]?.width;
        if (width !== undefined && Number.isFinite(width) && width > 0) onCellWidth(width / FONT_SAMPLE.length);
      }}>{FONT_SAMPLE}</Text>
  );
}

/** A JS snapshot frame (no native session), in a ScrollView or a FlatList. */
function SnapshotTerminalGrid({frame, nativeRows = 24, nativeColumns = 80, cellWidth, running, placeholder = 'Starting terminal…', onCellWidth, onLayout, onLinkPress, onTap, onSwipe, onMouseWheel, onScrollStateChange}: Props): React.JSX.Element {
  const list = React.useRef<FlatList<TerminalRow>>(null);
  const snapshotScroll = React.useRef<React.ElementRef<typeof ScrollView>>(null);
  const rowKeyState = React.useRef<TerminalRowKeyState>({keys: new WeakMap(), next: 0});
  const followTail = React.useRef(true);
  // Only a drag may stop following the tail. During a flood the content grows
  // between a programmatic scroll and its scroll event, so that event can look
  // "above the tail" and would otherwise leave the newest output off screen.
  const userDragging = React.useRef(false);
  const scrolling = React.useRef(false);
  const latestFrame = React.useRef(frame);
  const [displayFrame, setDisplayFrame] = React.useState(frame);
  const touchStart = React.useRef<TerminalTouch | null>(null);
  const alternate = frame?.alternate === true;
  const mouseTrackingMode = frame?.mouseTrackingMode ?? 'none';
  const mouseEncoding = frame?.mouseEncoding ?? 'default';
  const linkScanCache = React.useMemo<TerminalLinkScanCache>(() => new WeakMap(), []);
  latestFrame.current = frame;
  const linksByRow = React.useMemo(() => positionTerminalLinks(displayFrame, linkScanCache), [displayFrame, linkScanCache]);
  const useNativeRenderer = Platform.OS === 'android' && !Platform.isTesting;
  const nativeLinks = React.useMemo<readonly NativeTerminalLinkRange[]>(() => {
    if (!useNativeRenderer || displayFrame === undefined || linksByRow.size === 0) return NO_NATIVE_LINKS;
    const ranges: NativeTerminalLinkRange[] = [];
    linksByRow.forEach((links, row) => {
      for (const link of links) ranges.push({row, startColumn: link.startColumn, endColumn: link.endColumn});
    });
    return ranges;
  }, [displayFrame, linksByRow, useNativeRenderer]);
  const terminalRowKey = React.useCallback((row: TerminalRow): string => {
    const state = rowKeyState.current;
    const existing = state.keys.get(row);
    if (existing !== undefined) return existing;
    if (state.next >= Number.MAX_SAFE_INTEGER) throw new Error('terminal_row_key_overflow');
    const key = `row-${state.next}`;
    state.keys.set(row, key);
    state.next += 1;
    return key;
  }, []);
  React.useEffect(() => {
    followTail.current = true;
    touchStart.current = null;
    scrolling.current = false;
    onScrollStateChange?.(false);
    setDisplayFrame(latestFrame.current);
    return () => { touchStart.current = null; };
  }, [alternate, onScrollStateChange]);

  React.useEffect(() => {
    if (followTail.current) setDisplayFrame(frame);
  }, [frame]);

  const notifyScrollState = React.useCallback((isScrolling: boolean) => {
    if (scrolling.current === isScrolling) return;
    scrolling.current = isScrolling;
    onScrollStateChange?.(isScrolling);
  }, [onScrollStateChange]);

  const handleScrollBeginDrag = React.useCallback(() => {
    userDragging.current = true;
    notifyScrollState(true);
  }, [notifyScrollState]);

  const handleScrollEndDrag = React.useCallback(() => {
    userDragging.current = false;
  }, []);

  const handleScroll = React.useCallback((event: {nativeEvent: {contentOffset: {y: number}; contentSize: {height: number}; layoutMeasurement: {height: number}}}) => {
    const {contentOffset, contentSize, layoutMeasurement} = event.nativeEvent;
    const atTail = contentOffset.y + layoutMeasurement.height >= contentSize.height - TERMINAL_CELL_HEIGHT;
    const wasFollowingTail = followTail.current;
    if (wasFollowingTail && !atTail && !userDragging.current) return;
    followTail.current = atTail;
    notifyScrollState(!atTail);
    if (atTail && !wasFollowingTail) setDisplayFrame(latestFrame.current);
  }, [notifyScrollState]);

  const handleTouchStart = React.useCallback((event: GestureResponderEvent) => {
    if (event.nativeEvent.touches.length > 1) {
      touchStart.current = null;
      return;
    }
    const touch = event.nativeEvent.changedTouches[0] ?? event.nativeEvent;
    touchStart.current = {
      identifier: touch.identifier, pageX: touch.pageX, pageY: touch.pageY,
      dragged: false, paged: false, lastPageY: touch.pageY, lastPageAt: 0,
      mouseTrackingMode: alternate ? mouseTrackingMode : 'none',
      mouseEncoding: alternate ? mouseEncoding : 'default',
    };
  }, [alternate, mouseEncoding, mouseTrackingMode]);

  const shouldCaptureTouchMove = React.useCallback((event: GestureResponderEvent) => {
    const start = touchStart.current;
    if (!alternate || start === null) return false;
    const touch = event.nativeEvent.changedTouches.find(({identifier}) => identifier === start.identifier);
    if (touch === undefined) return false;
    const deltaX = touch.pageX - start.pageX;
    const deltaY = touch.pageY - start.pageY;
    return Math.abs(deltaY) > TAP_SLOP && Math.abs(deltaY) > Math.abs(deltaX);
  }, [alternate]);

  const handleTouchMove = React.useCallback((event: GestureResponderEvent) => {
    const start = touchStart.current;
    if (start === null) return;
    const touch = event.nativeEvent.changedTouches.find(({identifier}) => identifier === start.identifier);
    if (touch === undefined) return;
    const deltaX = touch.pageX - start.pageX;
    const deltaY = touch.pageY - start.pageY;
    if (deltaX * deltaX + deltaY * deltaY > TAP_SLOP * TAP_SLOP) start.dragged = true;
    if (!alternate || Math.abs(deltaY) <= Math.abs(deltaX)) return;

    const pageDelta = touch.pageY - start.lastPageY;
    const now = Date.now();
    const useMouseWheel = start.mouseTrackingMode !== 'none' && start.mouseEncoding !== 'sgrPixels' && onMouseWheel !== undefined;
    const distance = useMouseWheel ? MOUSE_WHEEL_DISTANCE : start.paged ? SWIPE_REPEAT_DISTANCE : SWIPE_DISTANCE;
    const interval = useMouseWheel ? MOUSE_WHEEL_INTERVAL_MS : SWIPE_REPEAT_INTERVAL_MS;
    if (Math.abs(pageDelta) < distance || (start.paged && now - start.lastPageAt < interval)) return;

    // Send at most one bounded input report per sampled position, including
    // release. Do not queue catch-up input or run a repeat timer after stop.
    start.paged = true;
    start.lastPageY = touch.pageY;
    start.lastPageAt = now;
    const direction = pageDelta > 0 ? 'up' : 'down';
    if (useMouseWheel) {
      const x = Number.isFinite(touch.locationX) ? touch.locationX : touch.pageX;
      const y = Number.isFinite(touch.locationY) ? touch.locationY : touch.pageY;
      const columns = frame?.columns ?? nativeColumns;
      const rows = frame?.rows ?? nativeRows;
      onMouseWheel({
        direction,
        column: Math.max(1, Math.min(columns, Math.floor(x / Math.max(cellWidth, 1)) + 1)),
        row: Math.max(1, Math.min(rows, Math.floor(y / TERMINAL_CELL_HEIGHT) + 1)),
        encoding: start.mouseEncoding === 'sgr' ? 'sgr' : 'default',
      });
    } else {
      onSwipe(pageDelta > 0 ? 'down' : 'up');
    }
  }, [alternate, cellWidth, frame?.columns, frame?.rows, nativeColumns, nativeRows, onMouseWheel, onSwipe]);

  const handleTouchEnd = React.useCallback((event: GestureResponderEvent) => {
    // The final position may be newer than the last delivered move. Consume
    // one eligible step before clearing, then ignore duplicate end callbacks.
    handleTouchMove(event);
    const start = touchStart.current;
    touchStart.current = null;
    if (start === null) return;

    const touch = event.nativeEvent.changedTouches.find(({identifier}) => identifier === start.identifier);
    if (touch === undefined) return;

    const deltaX = touch.pageX - start.pageX;
    const deltaY = touch.pageY - start.pageY;
    if (start.dragged || deltaX * deltaX + deltaY * deltaY > TAP_SLOP * TAP_SLOP) return;
    onTap?.();
  }, [handleTouchMove, onTap]);

  const handleTouchCancel = React.useCallback(() => {
    touchStart.current = null;
  }, []);

  const contentHeight = Math.max(displayFrame?.rows ?? nativeRows, displayFrame?.lines.length ?? 0) * TERMINAL_CELL_HEIGHT;
  return (
    <View
      style={styles.root}
      onLayout={onLayout}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      onTouchCancel={handleTouchCancel}
      // Android retains the initial touch target throughout a drag. A TUI
      // redraw can remove that glyph, so keep receiving moves on this stable
      // responder even when the original target can no longer bubble them.
      onStartShouldSetResponder={() => alternate}
      onMoveShouldSetResponderCapture={shouldCaptureTouchMove}
      onResponderMove={handleTouchMove}
      onResponderRelease={handleTouchEnd}
      onResponderTerminate={handleTouchCancel}
      testID="terminal-output">
      <FontMeasure onCellWidth={onCellWidth} />
      {frame === undefined ? <Text style={styles.connecting}>{placeholder}</Text> : (
        <>
        {useNativeRenderer ? (
          <ScrollView
            key={alternate ? 'alternate-native' : 'normal-native'}
            ref={snapshotScroll}
            scrollEnabled={!alternate}
            keyboardShouldPersistTaps="always"
            contentContainerStyle={{height: contentHeight}}
            onContentSizeChange={() => {
              if (alternate || !followTail.current) return;
              snapshotScroll.current?.scrollToEnd({animated: false});
            }}
            onScrollBeginDrag={handleScrollBeginDrag}
            onScrollEndDrag={handleScrollEndDrag}
            onScroll={handleScroll}
            scrollEventThrottle={32}
            testID="terminal-output-grid">
            <View style={{height: contentHeight, width: (displayFrame?.columns ?? nativeColumns) * cellWidth}}>
              <NativeTerminalCanvas
                accessible={false}
                frame={displayFrame}
                nativeRows={nativeRows}
                nativeColumns={nativeColumns}
                cellWidth={cellWidth}
                cellHeight={TERMINAL_CELL_HEIGHT}
                fontSize={TERMINAL_FONT_SIZE}
                links={nativeLinks}
                running={running}
                pointerEvents="none"
                style={StyleSheet.absoluteFill}
                testID="terminal-native-canvas"
              />
              {Array.from(linksByRow.entries()).flatMap(([row, links]) => links.map((link, linkIndex) => (
                <Pressable
                  key={`${row}-${linkIndex}-${link.startIndex}`}
                  accessibilityLabel={link.url}
                  accessibilityRole="link"
                  onPress={() => onLinkPress(link.url)}
                  style={[styles.linkTarget, {top: row * TERMINAL_CELL_HEIGHT, left: link.startColumn * cellWidth, width: (link.endColumn - link.startColumn) * cellWidth}]}
                  testID={`terminal-link-native-${row}-${linkIndex}`}
                />
              )))}
            </View>
          </ScrollView>
        ) : (
          <FlatList
            key={alternate ? 'alternate' : 'normal'}
            ref={list}
            data={displayFrame?.lines ?? []}
            keyExtractor={terminalRowKey}
            getItemLayout={(_, index) => ({length: TERMINAL_CELL_HEIGHT, offset: index * TERMINAL_CELL_HEIGHT, index})}
            initialNumToRender={displayFrame?.rows ?? nativeRows}
            maxToRenderPerBatch={Math.min(displayFrame?.rows ?? nativeRows, 10)}
            windowSize={3}
            scrollEnabled={!alternate}
            keyboardShouldPersistTaps="always"
            onContentSizeChange={() => {
              if (!alternate && followTail.current) list.current?.scrollToEnd({animated: false});
            }}
            onScrollBeginDrag={handleScrollBeginDrag}
            onScrollEndDrag={handleScrollEndDrag}
            onScroll={handleScroll}
            scrollEventThrottle={32}
            testID="terminal-output-grid"
            renderItem={({item, index}) => {
              const links = linksByRow.get(index) ?? NO_LINKS;
              const cursorColumn = displayFrame !== undefined && running && displayFrame.cursor.visible && displayFrame.cursor.row === index
                ? displayFrame.cursor.column
                : -1;
              return <TerminalRowView row={item} rowKey={terminalRowKey(item)} columns={displayFrame?.columns ?? nativeColumns} cellWidth={cellWidth} links={links} onLinkPress={onLinkPress} cursorColumn={cursorColumn} />;
            }}
          />
        )}
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, overflow: 'hidden', backgroundColor: TERMINAL_BACKGROUND},
  glyph: {position: 'absolute', top: 0, height: TERMINAL_CELL_HEIGHT, fontFamily: UI_FONT_FAMILY, fontSize: TERMINAL_FONT_SIZE, lineHeight: TERMINAL_CELL_HEIGHT, includeFontPadding: false, padding: 0},
  measure: {opacity: 0, width: 1000},
  linkTarget: {position: 'absolute', top: 0, height: TERMINAL_CELL_HEIGHT, backgroundColor: 'transparent'},
  linkText: {textDecorationLine: 'underline'},
  connecting: {color: TERMINAL_FOREGROUND, fontFamily: UI_FONT_FAMILY, fontSize: TERMINAL_FONT_SIZE},
  cursor: {position: 'absolute', top: 0, height: TERMINAL_CELL_HEIGHT, backgroundColor: TERMINAL_FOREGROUND, opacity: 0.45},
  bold: {fontWeight: 'bold'},
  italic: {fontStyle: 'italic'},
  underline: {textDecorationLine: 'underline'},
  strike: {textDecorationLine: 'line-through'},
  underlineStrike: {textDecorationLine: 'underline line-through'},
});
