import { bloqueioDesde } from '../api/faixas'
import type { Agendamento, FaixaHoraria, SemanaAgenda } from '../api/tipos'
import { diasUteisEntre } from './regua'

/**
 * A agenda simplificada, como a planilha: o dia não tem relógio, tem **5 espaços de
 * trabalho** de
 * tamanho igual. Cada faixa de horário de verdade cai em um deles — a mesma conta que o
 * importador da planilha faz ao contrário, então o que estava escrito na linha 3 da
 * planilha aparece no 3º pedaço do dia.
 *
 * Tudo aqui é cálculo puro, sem React e sem rede, para ser testado.
 */

export const BLOCOS_POR_DIA = 5

/** Uma linha da grade simplificada: um pedaço de um dia. */
export interface LinhaSimples {
  data: string
  diaSemana: string
  /** 1 a 5 dentro do dia. */
  bloco: number
  /** Faixa de horário de verdade onde um serviço solto neste espaço começa. */
  faixaInicio: number
  primeiraDoDia: boolean
  ultimaDoDia: boolean
}

const ehSexta = (data: string) => new Date(`${data}T12:00:00`).getDay() === 5

/**
 * Bloco (1 a 5) de cada faixa do dia, pelo meio dela dentro das horas úteis — a conta do
 * importador. Na sexta a última faixa não existe (a empresa fecha às 17h), então o dia de
 * 8 horas se divide diferente do de 9.
 *
 * A faixa do almoço fica com o bloco da faixa anterior: ela não recebe serviço, só é
 * atravessada por quem passa do meio-dia.
 */
export function blocoDeCadaFaixa(faixas: FaixaHoraria[], data: string): Map<number, number> {
  const sexta = ehSexta(data)
  const util = (f: FaixaHoraria) => !f.almoco && !(sexta && f.indice === faixas.length)
  const horasDoDia = faixas.filter(util).reduce((soma, f) => soma + f.horas, 0)
  const horasPorBloco = horasDoDia / BLOCOS_POR_DIA

  const mapa = new Map<number, number>()
  let acumulado = 0
  let ultimo = 1
  for (const faixa of faixas) {
    if (!util(faixa)) {
      mapa.set(faixa.indice, ultimo)
      continue
    }
    const meio = acumulado + faixa.horas / 2
    ultimo = Math.min(BLOCOS_POR_DIA, Math.floor(meio / horasPorBloco) + 1)
    mapa.set(faixa.indice, ultimo)
    acumulado += faixa.horas
  }
  return mapa
}

/** Primeira faixa útil de cada bloco do dia: onde um serviço solto no bloco começa. */
export function faixaInicialDosBlocos(faixas: FaixaHoraria[], data: string): number[] {
  const porFaixa = blocoDeCadaFaixa(faixas, data)
  const sexta = ehSexta(data)
  const inicios: number[] = []
  for (const faixa of faixas) {
    if (faixa.almoco || (sexta && faixa.indice === faixas.length)) continue
    const bloco = porFaixa.get(faixa.indice)!
    if (inicios[bloco - 1] === undefined) inicios[bloco - 1] = faixa.indice
  }
  return inicios
}

/** As 25 linhas da semana: 5 dias com 5 pedaços cada. */
export function montarLinhasSimples(semana: SemanaAgenda): LinhaSimples[] {
  return semana.dias.flatMap((dia) => {
    const inicios = faixaInicialDosBlocos(semana.faixas, dia.data)
    return inicios.map((faixaInicio, i) => ({
      data: dia.data,
      diaSemana: dia.diaSemana,
      bloco: i + 1,
      faixaInicio,
      primeiraDoDia: i === 0,
      ultimaDoDia: i === BLOCOS_POR_DIA - 1,
    }))
  })
}

/** Linha da grade simplificada de uma posição da régua de faixas da semana. */
function linhaDaPosicao(semana: SemanaAgenda, posicao: number): number {
  const faixasPorDia = semana.faixas.length
  const dia = Math.floor(posicao / faixasPorDia)
  const indiceFaixa = ((posicao % faixasPorDia) + faixasPorDia) % faixasPorDia
  const data = semana.dias[Math.min(Math.max(dia, 0), semana.dias.length - 1)]?.data ?? semana.inicio
  const bloco = blocoDeCadaFaixa(semana.faixas, data).get(indiceFaixa + 1) ?? 1
  return dia * BLOCOS_POR_DIA + (bloco - 1)
}

export interface BlocoSimples {
  item: Agendamento
  /** Linha onde começa e quantas linhas cobre, já cortado na semana vista. */
  indice: number
  span: number
}

export interface ColunaSimples {
  inicios: Map<number, { itens: Agendamento[]; span: number }>
  cobertas: Set<number>
}

/** O card enquanto a borda está sendo puxada: onde ele começaria e terminaria. */
export interface PreviaSimples {
  id: number
  linhaInicio: number
  linhaFim: number
}

/**
 * Onde cada serviço aparece na grade simplificada, coluna por coluna. O serviço é um
 * retângulo só, do pedaço em que começa ao pedaço em que termina — o almoço não parte
 * nada, porque aqui não existe horário.
 */
export function montarLayoutSimples(
  semana: SemanaAgenda,
  previa: PreviaSimples | null = null,
): Map<number, ColunaSimples> {
  const totalLinhas = semana.dias.length * BLOCOS_POR_DIA
  const faixasPorDia = semana.faixas.length
  const bloqueada = bloqueioDesde(semana.faixas, semana.inicio)
  const posicaoNaSemana = (data: string, faixa: number) =>
    diasUteisEntre(semana.inicio, data) * faixasPorDia + (faixa - 1)

  const todos = [...(semana.continuacoes ?? []), ...semana.dias.flatMap((d) => d.agendamentos)]
  const mapa = new Map<number, ColunaSimples>()

  semana.colunas.forEach((coluna) => {
    const blocos: BlocoSimples[] = []

    todos
      .filter((a) => a.adesivadorId === coluna.id)
      .forEach((item) => {
        const posicoes = item.segmentos.flatMap((seg) => {
          const de = posicaoNaSemana(seg.data, seg.faixaInicio)
          return Array.from({ length: seg.quantidade }, (_, k) => de + k).filter((p) => !bloqueada(p))
        })
        if (!posicoes.length) return
        const emPrevia = previa?.id === item.id
        const primeira = emPrevia ? previa.linhaInicio : linhaDaPosicao(semana, Math.min(...posicoes))
        const ultima = emPrevia ? previa.linhaFim : linhaDaPosicao(semana, Math.max(...posicoes))
        const indice = Math.max(0, primeira)
        const fim = Math.min(totalLinhas, ultima + 1)
        if (fim > indice) blocos.push({ item, indice, span: fim - indice })
      })

    const porLinha = new Map<number, BlocoSimples[]>()
    blocos.forEach((b) => porLinha.set(b.indice, [...(porLinha.get(b.indice) ?? []), b]))

    const inicios = new Map<number, { itens: Agendamento[]; span: number }>()
    const cobertas = new Set<number>()
    const ocupadas = [...porLinha.keys()].sort((a, b) => a - b)

    ocupadas.forEach((indice, i) => {
      const naLinha = porLinha.get(indice)!
      const proxima = ocupadas[i + 1] ?? totalLinhas
      const desejada = Math.min(...naLinha.map((b) => b.span))
      const span = Math.max(1, Math.min(desejada, proxima - indice, totalLinhas - indice))
      inicios.set(indice, { itens: naLinha.map((b) => b.item), span })
      for (let k = indice; k < indice + span; k++) cobertas.add(k)
    })

    mapa.set(coluna.id, { inicios, cobertas })
  })

  return mapa
}

/** As faixas de horário que caem num pedaço do dia (o almoço não entra). */
export function faixasDoBloco(faixas: FaixaHoraria[], data: string, bloco: number): FaixaHoraria[] {
  const porFaixa = blocoDeCadaFaixa(faixas, data)
  const sexta = ehSexta(data)
  return faixas.filter(
    (f) => !f.almoco && !(sexta && f.indice === faixas.length) && porFaixa.get(f.indice) === bloco,
  )
}

/**
 * Horas de um serviço que vai da linha `de` até a linha `ate` da grade, inclusive.
 *
 * A conta é pelas faixas de verdade que caem em cada pedaço, não pela média: o 1º pedaço
 * do dia vale 1h30 (07:30–09:00) e o 2º vale 2h (09:00–11:00). Só assim um serviço fica
 * **exatamente** nos pedaços escolhidos — com a média, um pedaço só transbordava para o
 * seguinte.
 */
export function horasDeIntervalo(semana: SemanaAgenda, de: number, ate: number): number {
  let horas = 0
  for (let linha = de; linha <= ate; linha++) {
    const dia = semana.dias[Math.floor(linha / BLOCOS_POR_DIA)]
    if (!dia) continue
    horas += faixasDoBloco(semana.faixas, dia.data, (linha % BLOCOS_POR_DIA) + 1).reduce(
      (soma, f) => soma + f.horas,
      0,
    )
  }
  return Math.max(0.5, horas)
}

/** Pedaços livres logo acima de uma linha, na coluna — até esbarrar no serviço de cima. */
export function pedacosLivresAcimaDe(
  coluna: ColunaSimples | undefined,
  linha: number,
  ignorarId?: number,
): number {
  if (!coluna) return linha
  const donos = donosPorLinha(coluna)
  let livres = 0
  for (let k = linha; k >= 0; k--) {
    const ocupantes = (donos.get(k) ?? []).filter((id) => id !== ignorarId)
    if (ocupantes.length) break
    livres++
  }
  return livres
}

/** Dono de cada linha da coluna: o serviço desenhado ali (pode haver mais de um). */
function donosPorLinha(coluna: ColunaSimples): Map<number, number[]> {
  const donos = new Map<number, number[]>()
  coluna.inicios.forEach((celula, indice) => {
    for (let k = indice; k < indice + celula.span; k++) {
      donos.set(k, [...(donos.get(k) ?? []), ...celula.itens.map((i) => i.id)])
    }
  })
  return donos
}

/**
 * Quantos pedaços livres existem a partir de uma linha, até esbarrar no próximo serviço
 * ou no fim da semana. `ignorarId` deixa de fora o serviço que está sendo movido ou
 * redimensionado — o espaço que ele mesmo ocupa hoje conta como livre para ele.
 *
 * É o que sustenta a regra da agenda simplificada: **nada empurra ninguém**. Quem não cabe
 * no buraco é encolhido até caber.
 */
export function pedacosLivresApartirDe(
  coluna: ColunaSimples | undefined,
  linha: number,
  totalLinhas: number,
  ignorarId?: number,
): number {
  if (!coluna) return Math.max(0, totalLinhas - linha)
  const donos = donosPorLinha(coluna)
  let livres = 0
  for (let k = linha; k < totalLinhas; k++) {
    const ocupantes = (donos.get(k) ?? []).filter((id) => id !== ignorarId)
    if (ocupantes.length) break
    livres++
  }
  return livres
}

/** Linha em que um serviço começa hoje na grade, ou nulo se ele não está desenhado. */
export function linhaDoItem(coluna: ColunaSimples | undefined, id: number): number | null {
  for (const [indice, celula] of coluna?.inicios ?? []) {
    if (celula.itens.some((i) => i.id === id)) return indice
  }
  return null
}

/**
 * Quantos espaços de trabalho um serviço de `horas` ocupa a partir de uma linha.
 *
 * Os espaços do dia não valem a mesma coisa — o 1º vale 1h30 (07:30–09:00) e o 2º vale 2h
 * (09:00–11:00) —, então as mesmas horas ocupam um espaço num lugar e dois noutro. É por
 * isso que copiar e colar (e arrastar) guardam **quantos espaços** o serviço ocupa, e não
 * as horas dele.
 */
export function pedacosDeHoras(semana: SemanaAgenda, linhaInicio: number, horas: number): number {
  const totalLinhas = semana.dias.length * BLOCOS_POR_DIA
  let somadas = 0
  for (let linha = linhaInicio; linha < totalLinhas; linha++) {
    somadas = horasDeIntervalo(semana, linhaInicio, linha)
    if (somadas >= horas - 0.001) return linha - linhaInicio + 1
  }
  return Math.max(1, totalLinhas - linhaInicio)
}

/** Linha da grade em que um serviço começa, pelo dia e pela faixa dele. */
export function linhaDoInicio(semana: SemanaAgenda, data: string, faixaInicio: number): number {
  const dia = semana.dias.findIndex((d) => d.data === data)
  if (dia < 0) return -1
  const bloco = blocoDeCadaFaixa(semana.faixas, data).get(faixaInicio) ?? 1
  return dia * BLOCOS_POR_DIA + (bloco - 1)
}
