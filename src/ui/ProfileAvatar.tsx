import React from 'react';
import {Image, StyleSheet, View, type ImageSourcePropType} from 'react-native';
import type {AvatarId} from '../profile/profileStore';
import {uiColors} from './brand';

const AVATAR_IMAGES: Readonly<Record<AvatarId, ImageSourcePropType>> = {
  falcon: require('../../assets/avatars/falcon.png'),
  ibis: require('../../assets/avatars/ibis.png'),
  jackal: require('../../assets/avatars/jackal.png'),
  cat: require('../../assets/avatars/cat.png'),
  cobra: require('../../assets/avatars/cobra.png'),
  scarab: require('../../assets/avatars/scarab.png'),
  lotus: require('../../assets/avatars/lotus.png'),
  papyrus: require('../../assets/avatars/papyrus.png'),
  'sun-disk': require('../../assets/avatars/sun-disk.png'),
};

/** Spoken names for screen readers. */
export const AVATAR_LABELS: Readonly<Record<AvatarId, string>> = {
  falcon: 'Falcon',
  ibis: 'Ibis',
  jackal: 'Jackal',
  cat: 'Cat',
  cobra: 'Cobra',
  scarab: 'Scarab',
  lotus: 'Lotus',
  papyrus: 'Papyrus',
  'sun-disk': 'Sun disk',
};

type ProfileAvatarProps = Readonly<{
  avatar: AvatarId;
  size: number;
  /** Draws the accent ring used for the chosen avatar or user. */
  selected?: boolean;
  testID?: string;
}>;

/** A user's hieroglyph on a dark green rounded square, like the app's tiles. */
export function ProfileAvatar({avatar, size, selected = false, testID}: ProfileAvatarProps): React.JSX.Element {
  return (
    <View
      style={[styles.tile, {borderRadius: Math.round(size * 0.2), height: size, width: size}, selected && styles.selected]}
      testID={testID}>
      <Image accessibilityIgnoresInvertColors resizeMode="contain" source={AVATAR_IMAGES[avatar]} style={{height: size, width: size}} />
    </View>
  );
}

const styles = StyleSheet.create({
  tile: {alignItems: 'center', backgroundColor: uiColors.accentSurface, borderColor: uiColors.border, borderWidth: 1, justifyContent: 'center', overflow: 'hidden'},
  selected: {borderColor: uiColors.accent, borderWidth: 2},
});
