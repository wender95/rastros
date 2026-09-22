import type { FaixaHoraria } from './tipos'

/** A faixa de uma posicao da regua. Aceita posicao negativa: um servico que comecou antes da semana vista. */
export const faixaDaPosicao = (faixas: FaixaHoraria[], posicao: number) =>
  faixas[((posicao % faixas.length) + faixas.length) % faixas.length]

/** Diz se uma posição da régua não recebe serviço. */
export type Bloqueio = (posicao: number) => boolean

/**
 * Almoço e, na sexta, 17:00–18:00 (a empresa fecha às 17h). As posições contam a partir
 * de `dataBase` em dias úteis — pode ser negativa, para quem começou antes da semana vista.
 * Espelho do FaixasDoDia.bloqueada do servidor.
 */
export function bloqueioDesde(faixas: FaixaHoraria[], dataBase: string): Bloqueio {
  const d = new Date(`${dataBase}T12:00:00`)
  const diaUtil0 = (d.getDay() + 6) % 7 // segunda = 0 ... sexta = 4
  return (posicao) => {
    const faixa = faixaDaPosicao(faixas, posicao)
    if (faixa.almoco) return true
    if (faixa.indice !== faixas.length) return false
    const dias = Math.floor(posicao / faixas.length)
    return (((diaUtil0 + dias) % 5) + 5) % 5 === 4
  }
}

const soAlmoco = (faixas: FaixaHoraria[]): Bloqueio => (p) => faixaDaPosicao(faixas, p).almoco

/**
 * Posições da régua contínua (uma por faixa de horário, N por dia) que um serviço ocupa
 * a partir de uma faixa inicial.
 *
 * O almoço **nunca entra**: é pulado, não abate horas e não é ocupado. Quem atravessa o
 * meio-dia aparece em dois pedaços, com a faixa do almoço livre.
 *
 * O servidor é a autoridade — isto existe para a prévia enquanto o usuário escolhe as
 * horas ou arrasta a borda do card, antes de a resposta chegar.
 */
export function posicoesOcupadas(
  faixas: FaixaHoraria[],
  slotInicio: number,
  horas: number,
  bloqueada: Bloqueio = soAlmoco(faixas),
): number[] {
  return posicoesAPartirDe(faixas, slotInicio - 1, horas, bloqueada)
}

/** Idem, a partir de uma posição absoluta da régua. */
export function posicoesAPartirDe(
  faixas: FaixaHoraria[],
  posicaoInicial: number,
  horas: number,
  bloqueada: Bloqueio = soAlmoco(faixas),
): number[] {
  const posicoes: number[] = []
  let restante = Math.max(0.5, horas)
  let posicao = posicaoInicial
  let guarda = 0

  while (restante > 0.001 && guarda++ < faixas.length * 60) {
    const faixa = faixaDaPosicao(faixas, posicao)
    if (!bloqueada(posicao)) {
      posicoes.push(posicao)
      restante -= faixa.horas
    }
    posicao++
  }
  return posicoes
}

/** As faixas de horário correspondentes, para mostrar ao usuário o que será preenchido. */
export function faixasCobertas(
  faixas: FaixaHoraria[],
  slotInicio: number,
  horas: number,
  bloqueada: Bloqueio = soAlmoco(faixas),
): FaixaHoraria[] {
  return posicoesOcupadas(faixas, slotInicio, horas, bloqueada).map((p) => faixaDaPosicao(faixas, p))
}

/**
 * Horas de trabalho entre duas posições da régua, inclusive. O almoço não soma.
 *
 * É o que traduz "arrastei a borda do card até esta linha" em horas: como as faixas têm
 * durações diferentes (1h e 1h30) e o almoço não conta, não dá para contar linhas.
 */
export function horasEntre(
  faixas: FaixaHoraria[],
  inicio: number,
  fim: number,
  bloqueada: Bloqueio = soAlmoco(faixas),
): number {
  let total = 0
  for (let p = inicio; p <= fim; p++) {
    if (!bloqueada(p)) total += faixaDaPosicao(faixas, p).horas
  }
  return total
}

/** Quantos dias além do primeiro o serviço alcança. */
export function diasAlcancados(
  faixas: FaixaHoraria[],
  slotInicio: number,
  horas: number,
  bloqueada: Bloqueio = soAlmoco(faixas),
): number {
  const posicoes = posicoesOcupadas(faixas, slotInicio, horas, bloqueada)
  return Math.floor(posicoes[posicoes.length - 1] / faixas.length)
}
