import { useEffect, useLayoutEffect, useRef, useState } from 'react'

export interface ItemMenu {
  rotulo: string
  /** Bolinha colorida antes do texto (os status usam as cores da legenda da agenda). */
  cor?: string
  /** Marca o item como o estado atual do card. */
  atual?: boolean
  /** Ausente ou nulo: o item aparece apagado e não faz nada. */
  aoEscolher?: () => void
  /** Uma linha separando grupos antes deste item. */
  separadorAntes?: boolean
  perigo?: boolean
}

interface Props {
  x: number
  y: number
  titulo?: string
  itens: ItemMenu[]
  aoFechar: () => void
}

/**
 * O menu do botão direito, como o do Windows: abre onde o mouse clicou, fecha no Esc, no
 * clique fora e ao rolar a tela. Num tablet, o "segurar o dedo" também abre.
 */
export default function MenuContexto({ x, y, titulo, itens, aoFechar }: Props) {
  const caixa = useRef<HTMLDivElement | null>(null)
  const [posicao, setPosicao] = useState({ x, y })

  useLayoutEffect(() => {
    const r = caixa.current?.getBoundingClientRect()
    if (!r) return
    // Perto da borda, o menu vira para dentro da tela.
    setPosicao({
      x: Math.max(8, Math.min(x, window.innerWidth - r.width - 8)),
      y: Math.max(8, Math.min(y, window.innerHeight - r.height - 8)),
    })
  }, [x, y, itens.length])

  useEffect(() => {
    const fechar = () => aoFechar()
    const aoTeclar = (e: KeyboardEvent) => {
      if (e.key === 'Escape') aoFechar()
    }
    window.addEventListener('pointerdown', fechar)
    window.addEventListener('scroll', fechar, true)
    window.addEventListener('resize', fechar)
    window.addEventListener('keydown', aoTeclar)
    return () => {
      window.removeEventListener('pointerdown', fechar)
      window.removeEventListener('scroll', fechar, true)
      window.removeEventListener('resize', fechar)
      window.removeEventListener('keydown', aoTeclar)
    }
  }, [aoFechar])

  return (
    <div
      ref={caixa}
      className="menu-contexto"
      style={{ left: posicao.x, top: posicao.y }}
      onPointerDown={(e) => e.stopPropagation()}
      onContextMenu={(e) => e.preventDefault()}
    >
      {titulo && <div className="menu-titulo">{titulo}</div>}
      {itens.map((item, i) => (
        <div key={i}>
          {item.separadorAntes && <div className="menu-separador" />}
          <button
            type="button"
            className={`menu-item${item.perigo ? ' menu-perigo' : ''}${item.atual ? ' menu-atual' : ''}`}
            disabled={!item.aoEscolher}
            onClick={() => {
              item.aoEscolher?.()
              aoFechar()
            }}
          >
            {item.cor && <span className={`menu-cor ${item.cor}`} />}
            {item.rotulo}
            {item.atual && <span className="menu-marca">✓</span>}
          </button>
        </div>
      ))}
    </div>
  )
}
