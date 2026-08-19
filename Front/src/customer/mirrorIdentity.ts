/**
 * 이 화면이 어느 거울인지.
 *
 * <b>라벨은 표시용 문자열이 아니라 직원에게 거기로 걸어가라는 지시다.</b> 그래서
 * 시스템이 방 이름을 지어내면 안 된다. 없는 방으로 직원을 보내는 것은 두 미러가
 * 구분되지 않는 것보다 나쁘다 — 전자는 확신에 차서 틀리기 때문이다.
 *
 * 우선순위:
 *   1. URL 파라미터   ?mirror=2F-A&label=2F 피팅룸 A
 *   2. 빌드 환경변수  VITE_MIRROR_ID / VITE_MIRROR_LABEL
 *   3. 탭별 임시 식별자 — 방 이름처럼 보이지 않게 만든다
 *
 * 매장에서는 기기마다 1번 URL 을 한 번 지정해 두면 된다. 키오스크를 설치하는
 * 보통의 방식이고, 기기별로 다시 빌드할 필요가 없다.
 */

export interface MirrorIdentity {
  mirrorId: string
  mirrorLabel: string
  /** 아직 어느 거울인지 지정되지 않은 상태. 직원 화면에서 이게 드러나야 한다. */
  provisional: boolean
}

/**
 * localStorage 가 아니라 sessionStorage 를 쓴다.
 * <p>
 * localStorage 는 탭끼리 공유되므로 창을 두 개 열어도 같은 값이 나온다 — 지금 문제가
 * 정확히 그것이다. sessionStorage 는 탭마다 독립이라 두 탭이 서로 다른 거울로 잡히고,
 * 새로고침해도 그 탭의 값은 유지된다.
 */
const KEY = 'mcm.mirror'

const STORE_ID = import.meta.env.VITE_STORE_ID ?? 'mcm-seoul'

export function resolveMirror(): MirrorIdentity {
  const params = new URLSearchParams(window.location.search)

  const fromUrl = params.get('mirror')?.trim()
  if (fromUrl) {
    return {
      mirrorId: fromUrl,
      // label 을 따로 주지 않으면 mirror 값을 그대로 쓴다. 운영자가 고른 이름이다.
      mirrorLabel: params.get('label')?.trim() || fromUrl,
      provisional: false,
    }
  }

  const fromEnv = import.meta.env.VITE_MIRROR_ID
  if (fromEnv) {
    return {
      mirrorId: fromEnv,
      mirrorLabel: import.meta.env.VITE_MIRROR_LABEL ?? fromEnv,
      provisional: false,
    }
  }

  return provisionalIdentity()
}

export const storeId = STORE_ID

/**
 * 미설정 상태.
 * <p>
 * 라벨을 <b>일부러 방 이름처럼 만들지 않는다.</b> "2F 피팅룸 B" 같은 값을 지어내면
 * 직원이 그 방으로 실제로 걸어간다. "임시 · a3f9" 는 못생겼지만, 못생긴 것이
 * 이 상황에서 전달해야 하는 정보다 — 이 거울은 아직 자리를 배정받지 않았다.
 */
function provisionalIdentity(): MirrorIdentity {
  let code = sessionStorage.getItem(KEY)
  if (!code) {
    code = Math.random().toString(36).slice(2, 6)
    sessionStorage.setItem(KEY, code)
  }
  return {
    mirrorId: `unassigned-${code}`,
    mirrorLabel: `임시 · ${code}`,
    provisional: true,
  }
}
