import React from 'react';
import {Image} from 'react-native';
import type {TerminalToolchainTarget} from '../native/NativeTerminalRuntime';
import {BrandMark} from './BrandMark';
import {uiColors} from './palette';

/**
 * The AI apps' marks (see harness-logos/README.md for their sources). They
 * live under src/terminal so the terminal screens can show them; the home
 * screen imports them from here too.
 */
export const HARNESS_LOGO_SOURCES = {
  claude: require('./harness-logos/claude-code.png'),
  codex: require('./harness-logos/openai-blossom.png'),
  opencode: require('./harness-logos/opencode.png'),
} as const;

export type HarnessLogo = keyof typeof HARNESS_LOGO_SOURCES;

/** The Codex mark is monochrome and takes the ink colour. */
export const HARNESS_LOGO_TINT: Partial<Record<HarnessLogo, string>> = {codex: uiColors.ink};

function isHarnessLogo(toolchain: TerminalToolchainTarget): toolchain is HarnessLogo {
  return toolchain in HARNESS_LOGO_SOURCES;
}

/** The app's own mark, or the Horus eye for the shell and GitHub CLI. */
export function HarnessMark({toolchain, size}: Readonly<{toolchain: TerminalToolchainTarget; size: number}>): React.JSX.Element {
  if (!isHarnessLogo(toolchain)) return <BrandMark accessible={false} size={size} />;
  const tint = HARNESS_LOGO_TINT[toolchain];
  return (
    <Image
      accessibilityElementsHidden
      importantForAccessibility="no"
      resizeMode="contain"
      source={HARNESS_LOGO_SOURCES[toolchain]}
      style={[{height: size, width: size}, tint === undefined ? null : {tintColor: tint}]}
      testID={`harness-mark-${toolchain}`}
    />
  );
}

