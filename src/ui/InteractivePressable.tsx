import React from 'react';
import {Animated, Pressable, Vibration, type PressableProps, type StyleProp, type ViewStyle} from 'react-native';

type InteractivePressableProps = Omit<PressableProps, 'style'> & Readonly<{
  style?: StyleProp<ViewStyle>;
}>;

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);
const BUTTON_HAPTIC_DURATION_MS = 8;

export function InteractivePressable({android_ripple, onPressIn, onPressOut, style, ...props}: InteractivePressableProps): React.JSX.Element {
  const scale = React.useRef(new Animated.Value(1)).current;

  const handlePressIn = React.useCallback((event: Parameters<NonNullable<PressableProps['onPressIn']>>[0]) => {
    Vibration.vibrate(BUTTON_HAPTIC_DURATION_MS);
    scale.stopAnimation();
    Animated.spring(scale, {toValue: 0.96, speed: 30, bounciness: 4, useNativeDriver: true}).start();
    onPressIn?.(event);
  }, [onPressIn, scale]);

  const handlePressOut = React.useCallback((event: Parameters<NonNullable<PressableProps['onPressOut']>>[0]) => {
    scale.stopAnimation();
    Animated.spring(scale, {toValue: 1, speed: 24, bounciness: 12, useNativeDriver: true}).start();
    onPressOut?.(event);
  }, [onPressOut, scale]);

  return (
    <AnimatedPressable
      {...props}
      android_ripple={android_ripple ?? {color: 'rgba(196, 255, 112, 0.16)', borderless: false}}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      style={[style, {transform: [{scale}]}]}
    />
  );
}
