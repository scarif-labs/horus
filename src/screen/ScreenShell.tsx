import React from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  StatusBar,
  StyleSheet,
  View,
} from 'react-native';
import {uiColors} from '../ui/brand';

export type ScreenShellProps = Readonly<{
  children: React.ReactNode;
  backgroundColor?: string;
  keyboardAware?: boolean;
  testID?: string;
}>;

export function ScreenShell({children, backgroundColor = uiColors.background, keyboardAware = false, testID}: ScreenShellProps): React.JSX.Element {
  const topInset = Platform.OS === 'android' ? StatusBar.currentHeight ?? 0 : 0;
  const content = keyboardAware ? (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      keyboardVerticalOffset={topInset}
      style={styles.flex}
    >
      {children}
    </KeyboardAvoidingView>
  ) : children;

  return (
    <>
      <StatusBar barStyle="light-content" />
      <View style={[styles.safeArea, {backgroundColor, paddingTop: topInset}]} testID={testID}>
        {content}
      </View>
    </>
  );
}

const styles = StyleSheet.create({
  flex: {flex: 1},
  safeArea: {flex: 1},
});
