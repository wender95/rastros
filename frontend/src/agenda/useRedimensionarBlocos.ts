import { useEffect, useRef } from 'react'
import type { PreviaSimples } from './blocos'

/** O que está sendo puxado: o card, a borda e até onde ele pode ir. */
export interface Puxada {
  id: number
  /** Semana a que o card pertence (as semanas do mês ficam na mesma página). */
  semanaInicio: string
  linhaInicio: number
  linhaFim: number
  /** Limites: até onde a alça pode ir sem invadir o serviço de baixo. */
  minima: number
  maxima: number
}

interface Opcoes {
  habilitado: boolean
  aoMudarPrevia: (previa: (PreviaSimples & { semanaInicio: string }) | null) => void
  /** Soltou a alça: o último espaço do card. Igual ao de antes significa "não mexeu". */
  aoConfirmar: (puxada: Puxada, linhaFim: number) => void
  /** Chamado ao terminar, para a grade ignorar o clique que vem junto do soltar. */
  aoTerminar: () => void
}

/**
 * Linha da grade na altura do ponteiro, pela medida das linhas da tabela.
 *
 * Não dá para perguntar ao HTML quem está embaixo do dedo: o card que está sendo puxado
 * cobre várias linhas numa célula só, e ela responderia sempre a linha em que começa.
 */
function linhaNaAltura(corpo: HTMLTableSectionElement, y: number): number {
  const linhas = corpo.rows
  if (!linhas.length) return 0
  for (let i = 0; i < linhas.length; i++) {
    const r = linhas[i].getBoundingClientRect()
    if (y < r.bottom) return i
  }
  return linhas.length - 1
}

/**
 * Aumentar e diminuir o serviço **puxando a alça do canto**, como na planilha: só para
 * baixo, mudando em quantos espaços de trabalho ele fica.
 *
 * Funciona com mouse e com o dedo (eventos de ponteiro). Enquanto a borda está sendo
 * puxada, o card é desenhado no tamanho novo — a confirmação só vai para o servidor ao
 * soltar. Os limites vêm de fora: aqui ninguém empurra ninguém, então a borda para no
 * serviço vizinho.
 */
export function useRedimensionarBlocos({ habilitado, aoMudarPrevia, aoConfirmar, aoTerminar }: Opcoes) {
  const atuais = useRef({ aoMudarPrevia, aoConfirmar, aoTerminar })
  atuais.current = { aoMudarPrevia, aoConfirmar, aoTerminar }
  const limpar = useRef<() => void>(() => {})

  useEffect(() => () => limpar.current(), [])

  return function iniciar(evento: React.PointerEvent<HTMLElement>, puxada: Puxada) {
    if (!habilitado) return
    evento.preventDefault()
    evento.stopPropagation()
    limpar.current()

    const corpo = evento.currentTarget.closest('table')?.querySelector('tbody')
    if (!corpo) return

    let fim = puxada.linhaFim

    const mover = (e: PointerEvent) => {
      const linha = linhaNaAltura(corpo, e.clientY)
      fim = Math.min(Math.max(linha, puxada.minima), puxada.maxima)
      atuais.current.aoMudarPrevia({
        id: puxada.id,
        linhaInicio: puxada.linhaInicio,
        linhaFim: fim,
        semanaInicio: puxada.semanaInicio,
      })
    }

    const soltar = () => {
      encerrar()
      atuais.current.aoMudarPrevia(null)
      atuais.current.aoTerminar()
      if (fim !== puxada.linhaFim) atuais.current.aoConfirmar(puxada, fim)
    }

    const cancelar = () => {
      encerrar()
      atuais.current.aoMudarPrevia(null)
      atuais.current.aoTerminar()
    }

    function encerrar() {
      window.removeEventListener('pointermove', mover)
      window.removeEventListener('pointerup', soltar)
      window.removeEventListener('pointercancel', cancelar)
      limpar.current = () => {}
    }

    window.addEventListener('pointermove', mover)
    window.addEventListener('pointerup', soltar)
    window.addEventListener('pointercancel', cancelar)
    limpar.current = encerrar
  }
}
