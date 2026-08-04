import { useCallback, useEffect, useRef, useState } from 'react'

export type CameraState = 'starting' | 'ready' | 'denied' | 'unavailable'

/** 캡처 시 긴 변을 이 크기로 줄인다. 원본 그대로 보내면 base64 가 수 MB 가 된다. */
const MAX_EDGE = 1024
const JPEG_QUALITY = 0.82

export function useCamera() {
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const [state, setState] = useState<CameraState>('starting')
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    async function start() {
      setState('starting')
      setError(null)

      if (!navigator.mediaDevices?.getUserMedia) {
        setState('unavailable')
        setError('이 브라우저에서는 카메라를 쓸 수 없습니다. https 또는 localhost 인지 확인해 주세요.')
        return
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: 'user' },
          audio: false,
        })
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop())
          return
        }
        streamRef.current = stream
        if (videoRef.current) {
          videoRef.current.srcObject = stream
          await videoRef.current.play().catch(() => undefined)
        }
        setState('ready')
      } catch (e) {
        if (cancelled) return
        const name = e instanceof DOMException ? e.name : ''
        if (name === 'NotAllowedError' || name === 'SecurityError') {
          setState('denied')
          setError('카메라 권한이 거부되었습니다. 주소창의 카메라 아이콘에서 허용해 주세요.')
        } else {
          setState('unavailable')
          setError(e instanceof Error ? e.message : '카메라를 열 수 없습니다.')
        }
      }
    }

    void start()

    return () => {
      cancelled = true
      streamRef.current?.getTracks().forEach((t) => t.stop())
      streamRef.current = null
    }
  }, [attempt])

  /** 현재 프레임을 JPEG data URL 로. 조명 필터가 걸리기 전의 원본을 보낸다. */
  const capture = useCallback((): string | null => {
    const video = videoRef.current
    if (!video || !video.videoWidth) return null

    const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight))
    const w = Math.round(video.videoWidth * scale)
    const h = Math.round(video.videoHeight * scale)

    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.drawImage(video, 0, 0, w, h)
    return canvas.toDataURL('image/jpeg', JPEG_QUALITY)
  }, [])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])

  return { videoRef, state, error, capture, retry }
}
