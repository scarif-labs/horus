import React from 'react';
import {Image, ScrollView, StyleSheet, Text, View} from 'react-native';
import {ScreenShell} from '../screen/ScreenShell';
import {HORUS_WORDMARK_FONT_FAMILY, UI_FONT_FAMILY} from './typography';
import {uiColors} from './brand';

type AuthScreenLayoutProps = Readonly<{
  children: React.ReactNode;
  screenTestID: string;
  brandTestID: string;
  /**
   * Pins the brand to the top and this content (usually the primary button)
   * to the bottom, so a sequence of screens keeps both in the same place.
   */
  footer?: React.ReactNode;
  pinned?: boolean;
}>;

export function AuthScreenLayout({children, screenTestID, brandTestID, footer, pinned = false}: AuthScreenLayoutProps): React.JSX.Element {
  const brand = (
    <View style={pinned ? [authStyles.brand, pinnedStyles.brand] : authStyles.brand} testID={`${brandTestID}-brand`}>
      <Image accessibilityLabel="Horus eye logo" resizeMode="contain" source={require('../terminal/horus.png')} style={pinned ? [authStyles.logo, pinnedStyles.logo] : authStyles.logo} testID={`${brandTestID}-logo`} />
      <Text style={pinned ? [authStyles.wordmark, pinnedStyles.wordmark] : authStyles.wordmark} testID={`${brandTestID}-wordmark`}>HORUS</Text>
    </View>
  );
  if (!pinned) {
    return (
      <ScreenShell keyboardAware testID={screenTestID}>
        <ScrollView contentContainerStyle={authStyles.content} keyboardShouldPersistTaps="handled">
          {brand}
          {children}
        </ScrollView>
      </ScreenShell>
    );
  }
  return (
    <ScreenShell keyboardAware testID={screenTestID}>
      {brand}
      <ScrollView contentContainerStyle={pinnedStyles.content} keyboardShouldPersistTaps="handled" style={pinnedStyles.scroll}>
        {children}
      </ScrollView>
      {footer === undefined ? null : <View style={pinnedStyles.footer}>{footer}</View>}
    </ScreenShell>
  );
}

const pinnedStyles = StyleSheet.create({
  brand: {marginBottom: 24, marginTop: 28},
  logo: {height: 88, width: 88},
  wordmark: {fontSize: 24, marginTop: 10},
  scroll: {flex: 1},
  content: {flexGrow: 1, paddingHorizontal: 24, paddingBottom: 12},
  footer: {alignSelf: 'center', maxWidth: 468, paddingBottom: 20, paddingHorizontal: 24, width: '100%'},
});

export const authStyles = StyleSheet.create({
  content: {flexGrow: 1, justifyContent: 'center', paddingHorizontal: 24, paddingVertical: 28},
  brand: {alignItems: 'center', marginBottom: 34},
  logo: {height: 112, width: 112},
  wordmark: {color: uiColors.ink, fontFamily: HORUS_WORDMARK_FONT_FAMILY, fontSize: 30, includeFontPadding: false, letterSpacing: 3, marginTop: 13},
  card: {alignSelf: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 14, borderWidth: 1, maxWidth: 460, padding: 18, width: '100%'},
  input: {backgroundColor: uiColors.background, borderColor: uiColors.borderSoft, borderRadius: 8, borderWidth: 1, color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 14, minHeight: 52, paddingHorizontal: 13},
  button: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 8, justifyContent: 'center', marginTop: 16, minHeight: 52},
  disabled: {opacity: 0.4},
  buttonText: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '900', letterSpacing: 0.6},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 16, marginTop: 10},
  notice: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 16, marginTop: 10},
});
