import { useRef } from 'react'
import { horasEntre } from '../api/faixas'
import type { Bloqueio } from '../api/faixas'
import type { Agendamento, FaixaHoraria } from '../api/tipos'
import type { Previa } from './regua'

interface Opcoes {
  faixas: FaixaHoraria[] | undefined
  /** Linhas da semana que nao recebem servico: almoco e sexta depois das 17h. */
  bloqueada: Bloqueio
  /** A cada linha percorrida, como o card ficaria; nulo ao soltar. */
  aoMudarPrevia: (previa: Previa | null) => void
  /** Borda de baixo: soltou numa duração diferente da inicial. */
  aoConfirmar: (id: number, horas: number) => void
  /** Borda de cima: soltou com o card começando em outra linha da semana. */
  aoConfirmarInicio: (id: number, linha: number) => void
  /** Soltou (mudando ou não): o clique que o navegador dispara em seguida não seleciona. */
  aoTerminar: () => void
}

/** Puxando pela borda de cima: onde o card termina e até onde ele pode subir. */
export interface Topo {
  /** Linha da semana onde o serviço termina — fica parada. */
  linhaFinal: number
  /** Primeira linha livre acima do card: dali para cima tem outro carro. */
  limite: number
}

/**
 * Puxar a borda de baixo de um card até a linha onde o serviço deve terminar — ou a de
 * cima até a linha onde ele deve começar, com o fim parado.
 *
 * Usa Pointer Events, então funciona igual com mouse, dedo e caneta. A duração vem das
 * faixas percorridas, não de "uma linha = uma hora": a conta respeita faixas de 1h e
 * 1h30, pula o almoço e atravessa o fim do dia naturalmente.
 */
export function useRedimensionar(opcoes: Opcoes) {
  // Os callbacks mais recentes, para o arraste que começou num render usar os do atual.
  const atuais = useRef(opcoes)
  atuais.current = opcoes

  return function iniciar(
    evento: React.PointerEvent<HTMLElement>,
    item: Agendamento,
    linhaInicial: number,
    topo?: Topo,
  ) {
    const { faixas, bloqueada } = atuais.current
    const punho = evento.currentTarget
    const corpo = punho.closest('tbody')
    if (!corpo || !faixas) return
    evento.preventDefault()
    evento.stopPropagation()

    // Desliga o arraste nativo do card na hora, sem esperar o re-render: senão o
    // navegador pode iniciar um drag-and-drop e engolir os eventos do ponteiro.
    punho.closest('.card-agenda')?.setAttribute('draggable', 'false')
    try {
      punho.setPointerCapture(evento.pointerId)
    } catch {
      // Sem captura o arraste ainda funciona pelos eventos na janela.
    }

    const linhas = [...corpo.querySelectorAll('tr')]
    const horasIniciais = item.horasEstimadas
    let atual = horasIniciais
    atuais.current.aoMudarPrevia({ id: item.id, horas: atual })

    /** Em que linha da grade o ponteiro está — funciona com linhas de alturas diferentes. */
    const linhaSob = (y: number) => {
      const indice = linhas.findIndex((tr) => {
        const r = tr.getBoundingClientRect()
        return y >= r.top && y <= r.bottom
      })
      if (indice >= 0) return indice
      return y < corpo.getBoundingClientRect().top ? 0 : linhas.length - 1
    }

    let inicioAtual = linhaInicial

    const mover = (e: PointerEvent) => {
      if (e.pointerId !== evento.pointerId) return
      if (topo) {
        // O início desce até a última linha do card e sobe até o limite livre; o almoço
        // não recebe início, então escorrega para a linha de baixo.
        let inicio = Math.min(topo.linhaFinal, Math.max(topo.limite, linhaSob(e.clientY)))
        while (inicio < topo.linhaFinal && bloqueada(inicio)) inicio++
        const nova = horasEntre(faixas, inicio, topo.linhaFinal, bloqueada)
        if (nova > 0 && inicio !== inicioAtual) {
          inicioAtual = inicio
          atual = nova
          atuais.current.aoMudarPrevia({ id: item.id, horas: nova, posicao: inicio })
        }
        return
      }
      const fim = Math.max(linhaInicial, linhaSob(e.clientY))
      const nova = Math.max(0.5, horasEntre(faixas, linhaInicial, fim, bloqueada))
      if (nova !== atual) {
        atual = nova
        atuais.current.aoMudarPrevia({ id: item.id, horas: nova })
      }
    }
    const largar = (e: PointerEvent) => {
      if (e.pointerId !== evento.pointerId) return
      window.removeEventListener('pointermove', mover)
      window.removeEventListener('pointerup', largar)
      window.removeEventListener('pointercancel', largar)
      const cancelado = e.type === 'pointercancel'
      atuais.current.aoMudarPrevia(null)
      atuais.current.aoTerminar()
      if (cancelado) return
      if (topo) {
        if (inicioAtual !== linhaInicial) atuais.current.aoConfirmarInicio(item.id, inicioAtual)
      } else if (atual !== horasIniciais) {
        atuais.current.aoConfirmar(item.id, atual)
      }
    }
    window.addEventListener('pointermove', mover)
    window.addEventListener('pointerup', largar)
    window.addEventListener('pointercancel', largar)
  }
}
