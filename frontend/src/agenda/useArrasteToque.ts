import { useEffect, useRef } from 'react'
import type { Agendamento } from '../api/tipos'

/** Onde o carro pode ser solto: data, coluna e faixa, lidos do atributo data-alvo da célula. */
export interface AlvoSolte {
  data: string
  adesivadorId: number
  slot: number
}

export const chaveDoAlvo = (data: string, adesivadorId: number, slot: number) => `${data}|${adesivadorId}|${slot}`

export function lerAlvo(chave: string | undefined): AlvoSolte | null {
  if (!chave) return null
  const [data, coluna, faixa] = chave.split('|')
  return { data, adesivadorId: Number(coluna), slot: Number(faixa) }
}

interface Opcoes {
  habilitado: boolean
  aoIniciar: (item: Agendamento) => void
  /** Chave da célula sob o dedo (para destacar onde vai cair), ou nulo. */
  aoPassarPor: (chave: string | null) => void
  /** Soltou: o alvo, ou nulo se o dedo saiu da grade ou o gesto foi cancelado. */
  aoSoltar: (alvo: AlvoSolte | null) => void
}

/** Tempo segurando o dedo parado para "pegar" o carro. */
const SEGURAR_MS = 350
/** Quanto o dedo pode andar antes disso sem virar rolagem da tela. */
const TOLERANCIA_PX = 10

/**
 * Arrastar um carro com o dedo, em tablet ou celular.
 *
 * O arrastar-e-soltar nativo do navegador não funciona em tela de toque. Aqui o gesto é
 * o de sempre nesses aparelhos: segurar o dedo no carro até ele "pegar" (vibra de leve),
 * arrastar até a faixa e soltar. Mexer o dedo antes disso continua rolando a agenda.
 */
export function useArrasteToque(opcoes: Opcoes) {
  const atuais = useRef(opcoes)
  atuais.current = opcoes
  const limpar = useRef<() => void>(() => {})

  useEffect(() => () => limpar.current(), [])

  return function aoTocar(evento: React.TouchEvent, item: Agendamento) {
    if (!atuais.current.habilitado || evento.touches.length !== 1) return
    limpar.current()

    // Copia a posicao: o objeto do toque nao e garantido depois do evento.
    const x0 = evento.touches[0].clientX
    const y0 = evento.touches[0].clientY
    let arrastando = false
    let alvo: string | undefined

    const timer = window.setTimeout(() => {
      arrastando = true
      navigator.vibrate?.(25)
      atuais.current.aoIniciar(item)
    }, SEGURAR_MS)

    const mover = (e: TouchEvent) => {
      const toque = e.touches[0]
      if (!arrastando) {
        const andou = Math.hypot(toque.clientX - x0, toque.clientY - y0)
        if (andou > TOLERANCIA_PX) encerrar() // é rolagem, não arraste
        return
      }
      e.preventDefault() // com o carro "na mão", o dedo não rola a tela
      const celula = document
        .elementFromPoint(toque.clientX, toque.clientY)
        ?.closest<HTMLElement>('[data-alvo]')
      alvo = celula?.dataset.alvo
      atuais.current.aoPassarPor(alvo ?? null)
    }
    const soltar = () => {
      const estavaArrastando = arrastando
      encerrar()
      if (estavaArrastando) atuais.current.aoSoltar(lerAlvo(alvo))
    }
    const cancelar = () => {
      const estavaArrastando = arrastando
      encerrar()
      if (estavaArrastando) atuais.current.aoSoltar(null)
    }
    function encerrar() {
      window.clearTimeout(timer)
      arrastando = false
      window.removeEventListener('touchmove', mover)
      window.removeEventListener('touchend', soltar)
      window.removeEventListener('touchcancel', cancelar)
      limpar.current = () => {}
    }

    // passive: false para o preventDefault segurar a rolagem enquanto arrasta.
    window.addEventListener('touchmove', mover, { passive: false })
    window.addEventListener('touchend', soltar)
    window.addEventListener('touchcancel', cancelar)
    limpar.current = encerrar
  }
}
