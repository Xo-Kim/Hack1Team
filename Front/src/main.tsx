import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { MirrorApp } from './customer/MirrorApp'
import { StaffApp } from './staff/StaffApp'
import './styles.css'

/**
 * 고객 미러와 직원 단말은 <b>다른 기기에서 도는 다른 앱</b>이다.
 * 라우터를 넣을 만큼 화면이 많지 않아 경로만 보고 갈라준다.
 *
 *   /        미러 디스플레이 (고객)
 *   /staff   태블릿·모바일 (직원)
 *
 * 한 컴포넌트 안에서 조건부로 그리지 않는 것이 중요하다. 그렇게 하면 고객 화면
 * 코드에서 추천 데이터에 손이 닿게 되고, 서버에서 경로를 갈라 둔 의미가 사라진다.
 */
const isStaff = window.location.pathname.startsWith('/staff')

createRoot(document.getElementById('root')!).render(
  <StrictMode>{isStaff ? <StaffApp /> : <MirrorApp />}</StrictMode>,
)
