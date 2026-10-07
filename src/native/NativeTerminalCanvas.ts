import {requireNativeComponent, type ViewProps} from 'react-native';
import type {TerminalFrame} from '../terminal/terminalBuffer';

export type NativeTerminalLinkRange = Readonly<{
  row: number;
  startColumn: number;
  endColumn: number;
}>;

export type NativeTerminalFrameMeta = Readonly<{
  alternate: boolean;
  contentRows: number;
  /** Rows of the active screen; the last [rows] of [contentRows]. */
  rows?: number;
  cursorRow: number;
  /** Last row with visible text, or -1 when every row is blank. */
  lastContentRow: number;
  mouseTracking: boolean;
  mouseSgr: boolean;
}>;

export type NativeTerminalCanvasProps = ViewProps & Readonly<{
  frame?: TerminalFrame;
  /** Native harness sessions render directly from the Android engine. */
  sessionId?: string;
  nativeRows?: number;
  nativeColumns?: number;
  /** Native sessions show the bounded installer transcript, then the TUI frame. */
  loadingText?: string;
  cellWidth: number;
  cellHeight: number;
  fontSize: number;
  links: readonly NativeTerminalLinkRange[];
  running: boolean;
  onNativeFrameMeta?: (event: {nativeEvent: NativeTerminalFrameMeta}) => void;
}>;

/** Android's one-view Canvas terminal surface. */
export const NativeTerminalCanvas = requireNativeComponent<NativeTerminalCanvasProps>('HorusTerminalCanvas');
