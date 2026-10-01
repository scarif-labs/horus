/**
 * App-wide UI colors. This lives under src/terminal so terminal screens can
 * use it without importing the UI namespace; src/ui/brand re-exports it.
 */
export const uiColors = {
  background: '#090C0D',
  panel: '#0D1112',
  panelRaised: '#111617',
  border: '#30383B',
  borderSoft: '#232A2D',
  ink: '#F2F4F5',
  muted: '#AAB7C6',
  subdued: '#707C84',
  accent: '#80EB12',
  /** Green-tinted fill behind accent-bordered selected or success states. */
  accentSurface: '#172018',
  /** Near-black for inset text wells and for text on accent fills. */
  inset: '#090D0B',
  danger: '#FF8795',
  warning: '#FFC65C',
} as const;
