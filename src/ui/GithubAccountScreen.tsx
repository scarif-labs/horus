import React from 'react';
import {Image, Linking, ScrollView, StyleSheet, Text, View} from 'react-native';
import type {GithubAccount} from '../projects/githubRepositories';
import {ScreenShell} from '../screen/ScreenShell';
import {InteractivePressable as Pressable} from './InteractivePressable';
import {BrandHeader, uiColors} from './brand';
import {UI_FONT_FAMILY} from './typography';

export type GithubAccountScreenProps = Readonly<{
  account: GithubAccount;
  onBack: () => void;
  onLogout: () => void;
  error?: string;
}>;

export function GithubAccountScreen({account, onBack, onLogout, error}: GithubAccountScreenProps): React.JSX.Element {
  const profileUrl = `https://github.com/${encodeURIComponent(account.username)}`;
  const openProfile = React.useCallback(async () => {
    try {
      await Linking.openURL(profileUrl);
    } catch {
      // The profile remains visible even when no browser can open the link.
    }
  }, [profileUrl]);

  return (
    <ScreenShell testID="github-account-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <BrandHeader
          eyebrow="CONNECTED ACCOUNT"
          title="GitHub"
          meta={null}
          action={(
            <Pressable accessibilityLabel="Back to home" accessibilityRole="button" onPress={onBack} style={styles.backButton} testID="github-account-back">
              <Text style={styles.backText}>← BACK</Text>
            </Pressable>
          )}
        />

        <View style={styles.accountCard}>
          <View style={styles.avatarFrame}>
            {account.avatarUrl === undefined
              ? <Text style={styles.avatarFallback} testID="github-account-avatar-fallback">GH</Text>
              : <Image accessibilityLabel={`${account.username} GitHub profile image`} source={{uri: account.avatarUrl}} style={styles.avatar} testID="github-account-avatar" />}
          </View>
          <Text style={styles.label}>SIGNED IN AS</Text>
          <Text style={styles.username} testID="github-account-username">@{account.username}</Text>
          <Pressable accessibilityLabel="Open GitHub profile" accessibilityRole="link" onPress={openProfile} style={styles.profileLink} testID="github-account-profile-link">
            <Text style={styles.profileLinkText}>{profileUrl.replace(/^https:\/\//, '')}</Text>
          </Pressable>
          {error === undefined ? null : <Text style={styles.error} testID="github-account-error">{error}</Text>}
          <Pressable accessibilityRole="button" onPress={onLogout} style={styles.logoutButton} testID="github-account-logout">
            <Text style={styles.logoutText}>LOG OUT OF GITHUB</Text>
          </Pressable>
        </View>
      </ScrollView>
    </ScreenShell>
  );
}

const styles = StyleSheet.create({
  content: {flexGrow: 1, paddingHorizontal: 18, paddingBottom: 24},
  backButton: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 9, borderWidth: 1, height: 38, justifyContent: 'center', paddingHorizontal: 9},
  backText: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 9, fontWeight: '800', letterSpacing: 0.3},
  accountCard: {alignItems: 'center', backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, marginTop: 4, padding: 18},
  avatarFrame: {alignItems: 'center', backgroundColor: uiColors.accent, borderRadius: 9, height: 88, justifyContent: 'center', width: 88},
  avatar: {height: 70, width: 70},
  avatarFallback: {color: uiColors.background, fontFamily: UI_FONT_FAMILY, fontSize: 28, fontWeight: '900', letterSpacing: -1},
  label: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, letterSpacing: 0.7, marginTop: 20},
  username: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 21, fontWeight: '800', marginTop: 5},
  profileLink: {marginTop: 7, padding: 4},
  profileLinkText: {color: uiColors.accent, fontFamily: UI_FONT_FAMILY, fontSize: 12, textDecorationLine: 'underline'},
  error: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 16, marginTop: 16, textAlign: 'center'},
  logoutButton: {alignItems: 'center', borderColor: uiColors.danger, borderRadius: 8, borderWidth: 1, justifyContent: 'center', marginTop: 24, minHeight: 48, width: '100%'},
  logoutText: {color: uiColors.danger, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '900', letterSpacing: 0.5},
});
