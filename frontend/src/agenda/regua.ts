import { bloqueioDesde, faixaDaPosicao, posicoesAPartirDe, posicoesOcupadas } from '../api/faixas'
import type { Bloqueio } from '../api/faixas'
import type { Agendamento, FaixaHoraria, SemanaAgenda } from '../api/tipos'

/**
 * A régua da grade: cada dia contribui suas faixas de horário, em sequência, e um
 * serviço longo passa do fim do dia para o seguinte. Tudo aqui é cálculo puro — sem
 * React, sem rede — para poder ser testado e ficar igual ao servidor.
 */

/** Segunda-feira da semana de uma data, em ISO (yyyy-mm-dd). */
export function segundaDaSemana(base: Date): string {
  const d = new Date(base)
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7))
  return d.toISOString().slice(0, 10)
}

export function somarDias(iso: string, dias: number): string {
  const d = new Date(`${iso}T12:00:00`)
  d.setDate(d.getDate() + dias)
  return d.toISOString().slice(0, 10)
}

export const formatarDia = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })

/**
 * Dias úteis de `de` até `ate` (negativo se `ate` vem antes). Sábado e domingo não
 * contam: sexta + 1 dia útil = segunda.
 */
export function diasUteisEntre(de: string, ate: string): number {
  const indice = (iso: string) => {
    // Dias desde uma segunda-feira fixa, em semanas de 5 dias úteis.
    const dias = Math.round((Date.parse(`${iso}T12:00:00Z`) - Date.parse('2000-01-03T12:00:00Z')) / 86_400_000)
    const semanas = Math.floor(dias / 7)
    return semanas * 5 + Math.min(dias - semanas * 7, 5)
  }
  return indice(ate) - indice(de)
}

export interface LinhaGrade {
  data: string
  diaSemana: string
  faixa: number
  rotulo: string
  almoco: boolean
  /** 17:00-18:00 da sexta: a empresa fecha as 17h, a linha nao recebe servico. */
  fechada: boolean
  primeiraDoDia: boolean
}

export function montarLinhas(semana: SemanaAgenda): LinhaGrade[] {
  return semana.dias.flatMap((dia) =>
    semana.faixas.map((faixa, i) => ({
      data: dia.data,
      diaSemana: dia.diaSemana,
      faixa: faixa.indice,
      rotulo: faixa.rotulo,
      almoco: faixa.almoco,
      fechada: faixa.indice === semana.faixas.length && new Date(`${dia.data}T12:00:00`).getDay() === 5,
      primeiraDoDia: i === 0,
    })),
  )
}

export interface ItemDaColuna {
  id: number
  posicao: number
  horas: number
}

/**
 * Reempilha uma coluna no navegador, com as mesmas regras do servidor:
 * - ninguém sobe;
 * - só desce quem encosta no serviço editado ou em alguém já empurrado agora — uma
 *   sobreposição antiga entre dois serviços que ninguém tocou fica como está;
 * - na mesma faixa, o editado fica e o outro desce;
 * - o almoço (e a sexta depois das 17h) nunca recebe o início de um serviço.
 *
 * Serve para a prévia enquanto o usuário puxa a borda de um card: o card cresce e os de
 * baixo descem na hora, antes de a resposta do servidor chegar.
 */
export function reempilharPrevia(
  faixas: FaixaHoraria[],
  itens: ItemDaColuna[],
  editadoId: number,
  bloqueada: Bloqueio = (p) => faixaDaPosicao(faixas, p).almoco,
): Map<number, number[]> {
  const ordenados = [...itens].sort(
    (a, b) =>
      a.posicao - b.posicao ||
      Number(b.id === editadoId) - Number(a.id === editadoId) ||
      a.id - b.id,
  )
  const resultado = new Map<number, number[]>()
  // Sem limite inicial: quem comecou numa semana anterior tem posicao negativa.
  let ocupadoAte = -Infinity
  let ondaAte = -Infinity

  for (const item of ordenados) {
    const naOnda = item.id === editadoId || item.posicao <= ondaAte
    if (!naOnda) {
      const posicoes = posicoesAPartirDe(faixas, item.posicao, item.horas, bloqueada)
      resultado.set(item.id, posicoes)
      ocupadoAte = Math.max(ocupadoAte, posicoes[posicoes.length - 1])
      continue
    }
    let p = Math.max(item.posicao, ocupadoAte + 1)
    while (bloqueada(p)) p++
    const posicoes = posicoesAPartirDe(faixas, p, item.horas, bloqueada)
    resultado.set(item.id, posicoes)
    const fim = posicoes[posicoes.length - 1]
    ondaAte = Math.max(ondaAte, fim)
    ocupadoAte = Math.max(ocupadoAte, fim)
  }
  return resultado
}

/** Um serviço é um bloco só, da primeira à última faixa — o almoço fica dentro dele. */
export function blocoDasPosicoes(posicoes: number[]) {
  const primeira = posicoes[0]
  const ultima = posicoes[posicoes.length - 1]
  return { indice: primeira, quantidade: ultima - primeira + 1 }
}

/**
 * O card enquanto uma borda está sendo puxada: as horas que teria e, pela borda de cima,
 * a linha da semana onde passaria a começar.
 */
export interface Previa {
  id: number
  horas: number
  posicao?: number
}

export interface LayoutColuna {
  /** Linha onde começa uma célula -> os serviços dela e quantas linhas ela cobre. */
  inicios: Map<number, { itens: Agendamento[]; span: number }>
  /** Linhas cobertas por uma célula que começou acima (não desenham nada). */
  cobertas: Set<number>
}

/**
 * Onde cada serviço aparece na grade, coluna por coluna. Com `previa`, a coluna do card
 * que está sendo redimensionado é reempilhada com as horas novas.
 *
 * Um serviço longo é desenhado até encontrar o próximo; dois que começam na mesma faixa
 * dividem a célula.
 */
export function montarLayout(
  semana: SemanaAgenda,
  totalLinhas: number,
  previa: Previa | null,
): Map<number, LayoutColuna> {
  const mapa = new Map<number, LayoutColuna>()
  const faixasPorDia = semana.faixas.length
  // Posicao na regua desta semana; negativa para quem comecou numa semana anterior.
  const posicaoNaSemana = (data: string, faixa: number) =>
    diasUteisEntre(semana.inicio, data) * faixasPorDia + (faixa - 1)
  const todos = [...(semana.continuacoes ?? []), ...semana.dias.flatMap((d) => d.agendamentos)]

  /** Corta o bloco no comeco e no fim da semana vista. */
  const noVisivel = (indice: number, span: number) => {
    const inicio = Math.max(0, indice)
    const fim = Math.min(totalLinhas, indice + span)
    return fim > inicio ? { indice: inicio, span: fim - inicio } : null
  }

  semana.colunas.forEach((coluna) => {
    const blocos: { item: Agendamento; indice: number; span: number }[] = []
    const daColuna = todos.filter((a) => a.adesivadorId === coluna.id)

    if (previa && daColuna.some((a) => a.id === previa.id)) {
      const itens = daColuna.map((item) => ({
        id: item.id,
        posicao:
          item.id === previa.id && previa.posicao !== undefined
            ? previa.posicao
            : posicaoNaSemana(item.data, item.slotInicio),
        horas: item.id === previa.id ? previa.horas : item.horasEstimadas,
      }))
      const porItem = reempilharPrevia(semana.faixas, itens, previa.id, bloqueioDesde(semana.faixas, semana.inicio))
      daColuna.forEach((item) => {
        const posicoes = porItem.get(item.id)
        if (!posicoes?.length) return
        const b = blocoDasPosicoes(posicoes)
        const visivel = noVisivel(b.indice, b.quantidade)
        if (visivel) blocos.push({ item, ...visivel })
      })
    } else {
      daColuna.forEach((item) => {
        item.segmentos.forEach((seg) => {
          const visivel = noVisivel(posicaoNaSemana(seg.data, seg.faixaInicio), seg.quantidade)
          if (visivel) blocos.push({ item, ...visivel })
        })
      })
    }

    const porFaixa = new Map<number, typeof blocos>()
    blocos.forEach((b) => porFaixa.set(b.indice, [...(porFaixa.get(b.indice) ?? []), b]))

    const inicios = new Map<number, { itens: Agendamento[]; span: number }>()
    const cobertas = new Set<number>()
    const ocupados = [...porFaixa.keys()].sort((a, b) => a - b)

    ocupados.forEach((indice, i) => {
      const noIndice = porFaixa.get(indice)!
      const proximo = ocupados[i + 1] ?? totalLinhas
      const desejada = Math.min(...noIndice.map((b) => b.span))
      const span = Math.max(1, Math.min(desejada, proximo - indice, totalLinhas - indice))
      inicios.set(indice, { itens: noIndice.map((b) => b.item), span })
      for (let k = indice; k < indice + span; k++) cobertas.add(k)
    })

    mapa.set(coluna.id, { inicios, cobertas })
  })
  return mapa
}

/** Linha da semana onde o card termina, contando da linha onde ele começa na grade. */
export function linhaFinalDe(faixas: FaixaHoraria[], item: Agendamento, indiceLinha: number): number {
  const posicoes = posicoesOcupadas(faixas, item.slotInicio, item.horasEstimadas, bloqueioDesde(faixas, item.data))
  return indiceLinha + posicoes[posicoes.length - 1] - (item.slotInicio - 1)
}

/**
 * Até onde a borda de cima de um card pode subir: a primeira linha livre logo acima dele.
 * Acima disso tem outro carro (ou é o começo da semana).
 */
export function primeiraLivreAcima(coluna: LayoutColuna | undefined, indiceLinha: number): number {
  let linha = indiceLinha - 1
  while (linha >= 0 && !coluna?.inicios.has(linha) && !coluna?.cobertas.has(linha)) linha--
  return linha + 1
}
