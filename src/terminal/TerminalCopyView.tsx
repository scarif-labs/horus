import React from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {InteractivePressable as Pressable} from '../ui/InteractivePressable';
import {UI_FONT_FAMILY} from '../ui/typography';
import {useHardwareBack} from '../ui/useHardwareBack';
import {uiColors} from './palette';

type TerminalCopyViewProps = Readonly<{
  /** Scrollback and screen as plain text. */
  text: string;
  onClose: () => void;
}>;

/**
 * Covers the terminal with its text in a native selectable view, so Android's
 * own handles and Copy menu work. The canvas has no text to select. Back
 * returns to the session.
 */
export function TerminalCopyView({text, onClose}: TerminalCopyViewProps): React.JSX.Element {
  const scroll = React.useRef<React.ElementRef<typeof ScrollView>>(null);
  useHardwareBack(true, onClose);
  return (
    <View style={styles.overlay} testID="terminal-copy-view">
      <ScrollView
        ref={scroll}
        contentContainerStyle={styles.scrollContent}
        // Open at the newest output, like the terminal. The text is a
        // snapshot, so its size changes only while it first lays out.
        onContentSizeChange={() => scroll.current?.scrollToEnd({animated: false})}
        style={styles.scroll}
        testID="terminal-copy-scroll">
        {text.length === 0
          ? <Text style={styles.empty} testID="terminal-copy-empty">Nothing to copy yet.</Text>
          : <Text selectable style={styles.text} testID="terminal-copy-text">{text}</Text>}
      </ScrollView>
      <View style={styles.footer}>
        <View style={styles.titleColumn}>
          <Text style={styles.title}>COPY TEXT</Text>
          <Text style={styles.hint}>Long-press to select, then tap Copy.</Text>
        </View>
        <Pressable accessibilityLabel="Back to terminal" accessibilityRole="button" onPress={onClose} style={styles.close} testID="terminal-copy-close">
          <Text style={styles.closeText}>DONE</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  overlay: {backgroundColor: uiColors.background, bottom: 0, left: 0, position: 'absolute', right: 0, top: 0, zIndex: 11, elevation: 11},
  footer: {alignItems: 'center', borderTopColor: uiColors.border, borderTopWidth: 1, flexDirection: 'row', gap: 12, paddingHorizontal: 16, paddingVertical: 10},
  titleColumn: {flex: 1},
  title: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 11, fontWeight: '800', letterSpacing: 0.9},
  hint: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginTop: 2},
  close: {alignItems: 'center', borderColor: uiColors.border, borderRadius: 10, borderWidth: 1, justifyContent: 'center', minHeight: 36, paddingHorizontal: 16},
  closeText: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 10, fontWeight: '800', letterSpacing: 0.6},
  scroll: {flex: 1},
  // The floating home button sits over the top of the terminal area.
  scrollContent: {paddingBottom: 10, paddingHorizontal: 12, paddingTop: 60},
  text: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, lineHeight: 18},
  empty: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 12},
});
