import { useState } from 'react'
import {
  CATEGORY_LABEL,
  type MoodAnalysis,
  type RecommendedItem,
  type RecommendResponse,
} from '../types'
import { swatch } from '../lighting'

export type RecState = 'idle' | 'loading' | 'done' | 'error'

/** 이미지가 죽어도 카드 레이아웃이 무너지지 않도록 스와치 폴백을 둔다. */
function Thumb({ item }: { item: RecommendedItem }) {
  const [failed, setFailed] = useState(false)
  const { product } = item

  if (!product.imageUrl || failed) {
    return (
      <div className="thumb thumb--empty">
        {product.colors.map((c) => (
          <i key={c} style={{ background: swatch(c) }} />
        ))}
      </div>
    )
  }

  return (
    <div className="thumb">
      {/*
        loading="lazy" 를 쓰지 않는다. 썸네일 9장이 합쳐 70KB 남짓이라 아낄 것이 없고,
        직원이 접객 중 카드를 스크롤할 때 빈 칸이 잠깐 보이는 쪽이 훨씬 손해다.
      */}
      <img
        src={product.imageUrl}
        alt={product.name}
        decoding="async"
        onError={() => setFailed(true)}
      />
    </div>
  )
}

function RecItem({ item }: { item: RecommendedItem }) {
  const { product } = item

  return (
    <li className="rec__item">
      <span className="rec__rank">{item.rank}</span>
      <Thumb item={item} />

      <div className="rec__body">
        {product.productUrl ? (
          <a className="rec__name" href={product.productUrl} target="_blank" rel="noreferrer">
            {product.name}
          </a>
        ) : (
          <p className="rec__name">{product.name}</p>
        )}

        <p className="rec__price">{product.priceKrw.toLocaleString('ko-KR')}원</p>
        <p className="rec__reason">{item.reason}</p>

        <p className="rec__meta">
          {product.line} · {product.material} · {product.size}
        </p>
        <p className="rec__loc">
          {product.storeLocation}
          <span className="rec__colors">
            {product.colors.map((c) => (
              <i key={c} className="chip__dot" style={{ background: swatch(c) }} />
            ))}
          </span>
        </p>
      </div>
    </li>
  )
}

/**
 * 직원 응대 카드. (PRD §8.2)
 *
 * 지금은 검증 편의를 위해 미러 화면 오른쪽에 붙어 있지만,
 * 실제 서비스에서 이 내용은 <b>고객에게 보이면 안 된다</b> — 직원 단말(/staff)로만 간다.
 * PRD §1.1 의 "AI 출력 비공개" 원칙이 이 패널을 떼어내는 이유다.
 */
export function StaffPanel({
  analysis,
  data,
  state,
  error,
  serving,
}: {
  analysis: MoodAnalysis | null
  data: RecommendResponse | null
  state: RecState
  error: string | null
  serving: boolean
}) {
  return (
    <aside className="staff">
      <header className="staff__head">
        <div>
          <p className="eyebrow eyebrow--warn">STAFF VIEW</p>
          <h3 className="staff__title">응대 카드</h3>
        </div>
        {serving && <span className="badge badge--live">응대 중</span>}
      </header>

      <p className="staff__disclaimer">
        실제 서비스에서 이 패널은 직원 단말에만 표시됩니다. 고객 화면에는 노출되지 않습니다.
      </p>

      {!analysis && <p className="staff__empty">촬영이 끝나면 분석 결과가 표시됩니다.</p>}

      {analysis && (
        <section className="staff__block">
          <h4 className="staff__label">고객 무드</h4>
          <p className="staff__mood">
            {analysis.conceptName} · {analysis.outfit.style}
          </p>
          <div className="palette palette--sm">
            {analysis.outfit.palette.map((c) => (
              <span key={c} className="chip chip--sm">
                <i className="chip__dot" style={{ background: swatch(c) }} />
                {c}
              </span>
            ))}
          </div>
          <p className="staff__tags">{analysis.outfit.moodTags.join(' · ')}</p>
          <p className="staff__tags">포멀리티 {analysis.outfit.formality.toFixed(2)}</p>
        </section>
      )}

      {state === 'loading' && <p className="staff__empty">추천 생성 중…</p>}
      {state === 'error' && <p className="staff__empty staff__empty--error">{error}</p>}

      {state === 'done' && data && (
        <>
          <p className="staff__source">
            <span className={`srcbadge ${data.fallback ? 'srcbadge--fallback' : 'srcbadge--ai'}`}>
              {data.fallback ? '프리필터 점수만' : 'AI 랭킹'}
            </span>
          </p>
          {data.note && <p className="staff__note">{data.note}</p>}

          {data.recommendations.map((group) => (
            <section key={group.category} className="staff__block">
              <h4 className="staff__label">{CATEGORY_LABEL[group.category] ?? group.category}</h4>
              <ol className="rec">
                {group.items.map((item) => (
                  <RecItem key={item.productId} item={item} />
                ))}
              </ol>
            </section>
          ))}

          <section className="staff__block">
            <h4 className="staff__label">스타일링 노트</h4>
            <p className="staff__styling">{data.stylingNote}</p>
          </section>

          <p className="staff__stock">재고 미연동 — 매장 재고 확인 필요</p>
        </>
      )}
    </aside>
  )
}
