import React from 'react';
import {AccessibilityInfo, StyleSheet, Text, View} from 'react-native';
import {HarnessMark} from '../terminal/harnessLogos';
import {uiColors} from './brand';
import {UI_FONT_FAMILY} from './typography';

type DemoScene = Readonly<{
  toolchain: 'claude' | 'codex' | 'opencode';
  name: string;
  prompt: string;
  lines: readonly string[];
}>;

const SCENES: readonly DemoScene[] = [
  {toolchain: 'claude', name: 'Claude Code', prompt: 'fix the failing login test', lines: ['● Read src/auth.ts', '● Edit src/auth.ts  +4 −1', '✓ 12 tests passed']},
  {toolchain: 'codex', name: 'Codex', prompt: 'add dark mode to settings', lines: ['• Searched for theme', '• Updated 3 files', '✓ Build succeeded']},
  {toolchain: 'opencode', name: 'OpenCode', prompt: 'explain this repo', lines: ['• Read README.md', '• Mapped 48 files', '✓ Here’s the overview']},
];

const TYPE_MS = 45;
const LINE_MS = 550;
const HOLD_MS = 1800;

type DemoState = Readonly<{scene: number; typed: number; lines: number}>;

function nextState({scene, typed, lines}: DemoState): [DemoState, number] {
  const current = SCENES[scene];
  if (typed < current.prompt.length) return [{scene, typed: typed + 1, lines}, TYPE_MS];
  if (lines < current.lines.length) return [{scene, typed, lines: lines + 1}, LINE_MS];
  return [{scene: (scene + 1) % SCENES.length, typed: 0, lines: 0}, HOLD_MS];
}

/** A small terminal that types a task to each agent in turn. */
export function WelcomeDemo(): React.JSX.Element {
  const [state, setState] = React.useState<DemoState>({scene: 0, typed: 0, lines: 0});
  const [reduceMotion, setReduceMotion] = React.useState(false);

  React.useEffect(() => {
    let mounted = true;
    AccessibilityInfo.isReduceMotionEnabled().then(value => { if (mounted) setReduceMotion(value); }).catch(() => undefined);
    return () => { mounted = false; };
  }, []);

  React.useEffect(() => {
    if (reduceMotion) return undefined;
    const [, delay] = nextState(state);
    const timer = setTimeout(() => setState(current => nextState(current)[0]), delay);
    return () => clearTimeout(timer);
  }, [reduceMotion, state]);

  const scene = SCENES[state.scene];
  const typed = reduceMotion ? scene.prompt.length : state.typed;
  const lines = reduceMotion ? scene.lines.length : state.lines;
  const typing = typed < scene.prompt.length || lines === 0;

  return (
    <View accessible accessibilityLabel="Horus runs Claude Code, Codex and OpenCode in a terminal on your phone" testID="welcome-demo">
      <View style={styles.window}>
        <View style={styles.titleBar}>
          <View style={styles.lights}>
            <View style={[styles.light, styles.lightRed]} />
            <View style={[styles.light, styles.lightAmber]} />
            <View style={[styles.light, styles.lightGreen]} />
          </View>
          <Text style={styles.titleText}>{scene.name.toLowerCase()} · ~/app</Text>
        </View>
        <View style={styles.body}>
          <Text style={styles.prompt}>
            <Text style={styles.chevron}>{'> '}</Text>
            {scene.prompt.slice(0, typed)}
            {typing ? <Text style={styles.cursor}>▌</Text> : null}
          </Text>
          {scene.lines.map((line, index) => (
            <Text key={line} style={[styles.line, line.startsWith('✓') && styles.lineDone, index >= lines && styles.hidden]}>{line}</Text>
          ))}
        </View>
      </View>
      <View style={styles.agents}>
        {SCENES.map((item, index) => (
          <View key={item.toolchain} style={[styles.agent, index !== state.scene && styles.agentIdle]}>
            <HarnessMark size={22} toolchain={item.toolchain} />
            <Text style={[styles.agentName, index === state.scene && styles.agentNameActive]}>{item.name}</Text>
          </View>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  window: {backgroundColor: uiColors.panel, borderColor: uiColors.border, borderRadius: 12, borderWidth: 1, overflow: 'hidden'},
  titleBar: {alignItems: 'center', borderBottomColor: uiColors.borderSoft, borderBottomWidth: 1, flexDirection: 'row', paddingHorizontal: 12, paddingVertical: 9},
  lights: {flexDirection: 'row', gap: 5},
  light: {borderRadius: 4, height: 8, width: 8},
  lightRed: {backgroundColor: '#FF5F57'},
  lightAmber: {backgroundColor: '#FEBC2E'},
  lightGreen: {backgroundColor: '#28C840'},
  titleText: {color: uiColors.subdued, flex: 1, fontFamily: UI_FONT_FAMILY, fontSize: 10, marginRight: 31, textAlign: 'center'},
  body: {minHeight: 118, paddingHorizontal: 14, paddingVertical: 12},
  prompt: {color: uiColors.ink, fontFamily: UI_FONT_FAMILY, fontSize: 12, lineHeight: 20},
  chevron: {color: uiColors.accent},
  cursor: {color: uiColors.accent},
  line: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 11, lineHeight: 20},
  lineDone: {color: uiColors.accent},
  hidden: {opacity: 0},
  agents: {flexDirection: 'row', justifyContent: 'space-between', marginTop: 14},
  agent: {alignItems: 'center', flex: 1, gap: 6},
  agentIdle: {opacity: 0.35},
  agentName: {color: uiColors.muted, fontFamily: UI_FONT_FAMILY, fontSize: 10},
  agentNameActive: {color: uiColors.ink},
});
