import React from 'react';
import {Image, StyleSheet, View} from 'react-native';

type BrandMarkProps = Readonly<{accessible?: boolean; size?: number}>;

export function BrandMark({accessible = true, size = 42}: BrandMarkProps): React.JSX.Element {
  return (
    <View accessible={accessible} accessibilityLabel={accessible ? 'Horus' : undefined} style={[styles.mark, {height: size, width: size}]}>
      <Image source={require('./horus.png')} resizeMode="contain" style={[styles.markImage, {height: size, width: size}]} />
    </View>
  );
}

const styles = StyleSheet.create({
  mark: {height: 42, justifyContent: 'center', marginRight: 9, width: 42},
  markImage: {height: 42, width: 42},
});
