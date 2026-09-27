import {requireNativeComponent, type ViewProps} from 'react-native';

export type NativeTerminalInputProps = ViewProps & Readonly<{
  sessionId?: string;
  terminalEnabled: boolean;
  terminalAutoFocus: boolean;
  ctrlActive: boolean;
  altActive: boolean;
  keyboardShowRequest: number;
  keyboardHideRequest: number;
}>;

/** Android keyboard bridge that writes committed text directly to the PTY. */
export const NativeTerminalInput = requireNativeComponent<NativeTerminalInputProps>('HorusTerminalInput');
