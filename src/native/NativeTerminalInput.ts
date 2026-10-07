import {requireNativeComponent, type ViewProps} from 'react-native';

export type NativeTerminalInputProps = ViewProps & Readonly<{
  sessionId?: string;
  terminalEnabled: boolean;
  terminalAutoFocus: boolean;
  ctrlActive: boolean;
  altActive: boolean;
  /** The native view applied CTRL/ALT to a key and released them (one-shot). */
  onModifiersConsumed?: () => void;
  keyboardShowRequest: number;
  keyboardHideRequest: number;
}>;

/** Android keyboard bridge that writes committed text directly to the PTY. */
export const NativeTerminalInput = requireNativeComponent<NativeTerminalInputProps>('HorusTerminalInput');
