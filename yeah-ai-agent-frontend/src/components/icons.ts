/**
 * 内置线性图标集（Phosphor 风格：圆头描边、24 栅格）。
 *
 * 做成内联 SVG 而不是引第三方图标包，是为了零依赖、零打包体积，
 * 描边粗细也能跟着字号自由调。若要换成官方 Phosphor：
 *   npm i @phosphor-icons/vue
 * 然后把 AppIcon.vue 改成对 <PhPlus /> 等组件的转发，调用方无需改动。
 */
export type IconName =
  | 'plus'
  | 'microphone'
  | 'caret-down'
  | 'arrow-up'
  | 'arrow-right'
  | 'x'
  | 'file-pdf'
  | 'map-pin'
  | 'sun'
  | 'moon'
  | 'circle-half'
  | 'car'
  | 'star'
  | 'clock'
  | 'tag'
  | 'sparkle'
  | 'sun-horizon'
  | 'cloud'
  | 'drop'
  | 'trash'
  | 'pencil'
  | 'warning'
  | 'spinner'
  | 'stop'
  | 'lightbulb'
  | 'chat'
  | 'globe'
  | 'check'
  | 'camera'

export const ICONS: Record<IconName, string> = {
  plus: '<path d="M12 5v14M5 12h14"/>',
  microphone:
    '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6"/>',
  'caret-down': '<path d="M6 9.5l6 6 6-6"/>',
  'arrow-up': '<path d="M12 19V5M5 12l7-7 7 7"/>',
  'arrow-right': '<path d="M4 12h16M13 6l6 6-6 6"/>',
  x: '<path d="M6 6l12 12M18 6L6 18"/>',
  'file-pdf':
    '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 14h6M9 17h4"/>',
  'map-pin':
    '<path d="M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M19.1 4.9l-1.8 1.8M6.7 17.3l-1.8 1.8"/>',
  moon: '<path d="M20.5 14.8A8.6 8.6 0 0 1 9.2 3.5a8.6 8.6 0 1 0 11.3 11.3z"/>',
  'circle-half':
    '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5a8.5 8.5 0 0 1 0 17z" fill="currentColor" stroke="none"/>',
  car: '<path d="M4 16.5v-3.6a2 2 0 0 1 .2-.9l1.7-3.5A2 2 0 0 1 7.7 7.4h8.6a2 2 0 0 1 1.8 1.1l1.7 3.5a2 2 0 0 1 .2.9v3.6"/><path d="M3 16.5h18"/><circle cx="7.5" cy="17.8" r="1.6"/><circle cx="16.5" cy="17.8" r="1.6"/>',
  star: '<path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.2-4.1 5.8-.8z"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.2V12l3.2 2.1"/>',
  tag: '<path d="M3.8 12.4V5.4a1.6 1.6 0 0 1 1.6-1.6h7l8 8a1.6 1.6 0 0 1 0 2.3l-5.1 5.1a1.6 1.6 0 0 1-2.3 0z"/><circle cx="8.4" cy="8.4" r="1.2" fill="currentColor" stroke="none"/>',
  sparkle:
    '<path d="M12 3.2l2 5.3 5.3 2-5.3 2-2 5.3-2-5.3-5.3-2 5.3-2z"/><path d="M18.5 16.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>',
  'sun-horizon':
    '<path d="M2.8 18.5h18.4"/><path d="M6.6 18.5a5.4 5.4 0 0 1 10.8 0"/><path d="M12 3v2.8M4.6 7.1l2 2M19.4 7.1l-2 2M2.8 14.2h2.4M18.8 14.2h2.4"/>',
  cloud: '<path d="M7.2 18.5a4.2 4.2 0 0 1 .4-8.4 5.6 5.6 0 0 1 10.8 1.5 3.6 3.6 0 0 1-.4 6.9z"/>',
  drop: '<path d="M12 3.2s5.5 5.6 5.5 9.4a5.5 5.5 0 0 1-11 0C6.5 8.8 12 3.2 12 3.2z"/>',
  trash:
    '<path d="M4.2 7h15.6M9.2 7V5.2a1.2 1.2 0 0 1 1.2-1.2h3.2a1.2 1.2 0 0 1 1.2 1.2V7"/><path d="M6.4 7l.9 12.1a1.4 1.4 0 0 0 1.4 1.3h6.6a1.4 1.4 0 0 0 1.4-1.3L17.6 7"/><path d="M10.4 11v5.6M13.6 11v5.6"/>',
  pencil: '<path d="M4 20.2h4.2L19.4 9a2.2 2.2 0 0 0-3.1-3.1L5.1 17.1z"/><path d="M14.8 7.4l2.9 2.9"/>',
  warning:
    '<circle cx="12" cy="12" r="8.8"/><path d="M12 7.4v5.4"/><circle cx="12" cy="16.3" r="0.9" fill="currentColor" stroke="none"/>',
  spinner: '<path d="M12 3.2a8.8 8.8 0 1 0 8.8 8.8"/>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor" stroke="none"/>',
  lightbulb:
    '<path d="M12 3a6 6 0 0 0-3.4 10.9V16h6.8v-2.1A6 6 0 0 0 12 3z"/><path d="M9.6 19h4.8M10.4 21.4h3.2"/>',
  chat: '<path d="M4 6.2a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2H9.2L4.4 20.4a.3.3 0 0 1-.4-.2z"/>',
  globe:
    '<circle cx="12" cy="12" r="8.8"/><path d="M3.2 12h17.6"/><path d="M12 3.2c2.3 2.4 3.5 5.5 3.5 8.8s-1.2 6.4-3.5 8.8c-2.3-2.4-3.5-5.5-3.5-8.8S9.7 5.6 12 3.2z"/>',
  check: '<path d="M5 12.8l4.4 4.4L19 7.6"/>',
  camera:
    '<path d="M3.5 8.8a2 2 0 0 1 2-2h2l1.4-2.2h6.2L16.5 6.8h2a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/><circle cx="12" cy="12.8" r="3.2"/>',
}
