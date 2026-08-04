import type { CSSProperties } from 'react'
import type { Lighting } from './types'

/**
 * 조명 스펙 → CSS 변수. (PRD §14.1)
 *
 * 핵심은 배경이 아니라 <b>카메라 영상 자체</b>를 물들이는 것이다.
 * 배경만 바뀌면 "색이 변했다"로 읽히지만, 내 얼굴과 옷의 색이 변하면
 * "조명이 들어왔다"로 읽힌다. 그래서 video 에 filter 를, 그 위에
 * blend 레이어를 얹는 2단 구성을 쓴다.
 */
export function lightingToCssVars(lighting: Lighting | null): CSSProperties {
  if (!lighting) {
    return {
      '--primary': '#0b0b0d',
      '--accent': '#2a2a30',
      '--transition': '2400ms',
      '--video-brightness': 0.55,
      '--video-contrast': 1.02,
      '--video-saturate': 0.75,
      '--tint-primary-opacity': 0.25,
      '--tint-accent-opacity': 0.1,
      '--breathe-scale': 0,
    } as CSSProperties
  }

  const b = lighting.brightness

  // 색온도가 낮을수록(따뜻할수록) 채도를 올린다. 2000K → 1.3, 6500K → 0.85
  const warmth = clamp01((6500 - lighting.colorTemperatureK) / 4500)
  const saturate = 0.85 + warmth * 0.45

  return {
    '--primary': lighting.primaryColor,
    '--accent': lighting.accentColor,
    '--transition': `${lighting.transitionMs}ms`,
    // 어두운 씬에서도 인물이 사라지지 않도록 하한을 둔다.
    '--video-brightness': (0.45 + b * 0.8).toFixed(3),
    '--video-contrast': (1.0 + (1 - b) * 0.25).toFixed(3),
    '--video-saturate': saturate.toFixed(3),
    // 밝은 씬일수록 틴트를 옅게 — 안 그러면 하얗게 날아간다.
    '--tint-primary-opacity': (0.62 - b * 0.25).toFixed(3),
    '--tint-accent-opacity': (0.5 - b * 0.18).toFixed(3),
    '--breathe-scale': lighting.effect === 'breathe' ? 1 : 0,
  } as CSSProperties
}

/** effect 값을 stage 클래스로. */
export function effectClass(lighting: Lighting | null): string {
  if (!lighting) return 'fx-none'
  return `fx-${lighting.effect}`
}

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v))
}

/** 색 이름 → 스와치용 hex. 카탈로그의 colors 필드를 시각화하는 용도. */
export const SWATCH: Record<string, string> = {
  black: '#111114',
  white: '#f2efe9',
  ivory: '#f2efe9',
  grey: '#8a8a90',
  gray: '#8a8a90',
  silver: '#b9bcc2',
  beige: '#d9c9ad',
  sand: '#d9c9ad',
  cognac: '#a4642a',
  brown: '#6b4423',
  tan: '#c08a52',
  gold: '#c9a227',
  red: '#a8232f',
  ruby: '#8e1c2c',
  navy: '#1e2a44',
  blue: '#2c4a7c',
  green: '#2f5138',
  forest: '#25422e',
  // MCM 시즌 컬러
  cinnamon: '#8c4a2f',
  pink: '#e8b7c4',
  khaki: '#5c5f3d',
  moss: '#5c5f3d',
  multi: 'conic-gradient(#a4642a, #25422e, #8e1c2c, #c9a227, #a4642a)',
}

export function swatch(color: string): string {
  return SWATCH[color.toLowerCase()] ?? '#55555c'
}
