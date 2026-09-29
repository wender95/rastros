import { describe, expect, it } from 'vitest'
import type { Agendamento, FaixaHoraria, SemanaAgenda } from '../api/tipos'
import {
  BLOCOS_POR_DIA,
  blocoDeCadaFaixa,
  faixaInicialDosBlocos,
  horasDeIntervalo,
  linhaDoInicio,
  montarLayoutSimples,
  montarLinhasSimples,
  pedacosDeHoras,
  pedacosLivresAcimaDe,
  pedacosLivresApartirDe,
} from './blocos'

/** A mesma grade real da agenda por horário: 07:30 às 18:00, almoço às 12:00. */
const FAIXAS: FaixaHoraria[] = [
  ['07:30', '09:00', 1.5],
  ['09:00', '10:00', 1],
  ['10:00', '11:00', 1],
  ['11:00', '12:00', 1],
  ['12:00', '13:30', 1.5],
  ['13:30', '15:00', 1.5],
  ['15:00', '16:00', 1],
  ['16:00', '17:00', 1],
  ['17:00', '18:00', 1],
].map(([inicio, fim, horas], i) => ({
  indice: i + 1,
  inicio: inicio as string,
  fim: fim as string,
  rotulo: `${inicio} às ${fim}`,
  horas: horas as number,
  almoco: i === 4,
}))

const SEGUNDA = '2026-09-21'
const SEXTA = '2026-09-25'

const carro = (id: number, data: string, faixa: number, quantidade: number, coluna = 1): Agendamento =>
  ({
    id,
    data,
    adesivadorId: coluna,
    slotInicio: faixa,
    horasEstimadas: 1,
    descricao: `carro ${id}`,
    segmentos: [{ data, faixaInicio: faixa, quantidade }],
  }) as Agendamento

const semanaCom = (...itens: Agendamento[]): SemanaAgenda =>
  ({
    inicio: SEGUNDA,
    fim: SEXTA,
    colunas: [{ id: 1 }, { id: 2 }],
    dias: ['2026-09-21', '2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25'].map((data) => ({
      data,
      diaSemana: '',
      agendamentos: itens.filter((i) => i.data === data),
    })),
    continuacoes: itens.filter((i) => i.data < SEGUNDA),
    faixas: FAIXAS,
  }) as unknown as SemanaAgenda

describe('os 5 pedaços do dia', () => {
  it('divide o dia de 9 horas como a planilha dividia (1,8h por pedaço)', () => {
    const blocos = blocoDeCadaFaixa(FAIXAS, SEGUNDA)
    expect([1, 2, 3, 4, 6, 7, 8, 9].map((f) => blocos.get(f))).toEqual([1, 2, 2, 3, 3, 4, 5, 5])
  })

  it('a faixa do almoço fica com o pedaço de antes: ninguém começa nela', () => {
    expect(blocoDeCadaFaixa(FAIXAS, SEGUNDA).get(5)).toBe(3) // fica com o pedaço das 11:00
    expect(faixaInicialDosBlocos(FAIXAS, SEGUNDA)).toEqual([1, 2, 4, 7, 8])
  })

  it('na sexta o dia tem 8 horas e ainda assim 5 pedaços', () => {
    const inicios = faixaInicialDosBlocos(FAIXAS, SEXTA)
    expect(inicios).toHaveLength(BLOCOS_POR_DIA)
    expect(inicios).toEqual([1, 2, 4, 6, 7])
    expect(inicios).not.toContain(9) // 17:00-18:00 não existe na sexta
  })

  it('a semana tem 25 linhas, 5 por dia', () => {
    const linhas = montarLinhasSimples(semanaCom())
    expect(linhas).toHaveLength(25)
    expect(linhas.filter((l) => l.primeiraDoDia)).toHaveLength(5)
    expect(linhas[0]).toMatchObject({ data: SEGUNDA, bloco: 1, faixaInicio: 1 })
    expect(linhas[24]).toMatchObject({ data: SEXTA, bloco: 5, ultimaDoDia: true })
  })

  it('as horas de um pedaço são as das faixas dele, não a média do dia', () => {
    const semana = semanaCom()
    // 1º pedaço de segunda = 07:30-09:00; o 2º = 09:00-11:00; o 3º atravessa o almoço.
    expect(horasDeIntervalo(semana, 0, 0)).toBe(1.5)
    expect(horasDeIntervalo(semana, 1, 1)).toBe(2)
    expect(horasDeIntervalo(semana, 2, 2)).toBe(2.5)
    expect(horasDeIntervalo(semana, 3, 3)).toBe(1)
    expect(horasDeIntervalo(semana, 4, 4)).toBe(2)
  })

  it('o dia inteiro dá 9 horas, e a sexta 8', () => {
    const semana = semanaCom()
    expect(horasDeIntervalo(semana, 0, 4)).toBe(9)
    expect(horasDeIntervalo(semana, 20, 24)).toBe(8) // sexta fecha às 17:00
  })

  it('um pedaço vira um card de um horário só', () => {
    // Era o defeito antigo: 1,8h arredondado para 2h transbordava para o pedaço seguinte.
    const horas = horasDeIntervalo(semanaCom(), 0, 0)
    const coluna = montarLayoutSimples(semanaCom(carro(1, SEGUNDA, 1, 1))).get(1)!
    expect(horas).toBe(1.5)
    expect(coluna.inicios.get(0)?.span).toBe(1)
  })
})

describe('desenho da grade simplificada', () => {
  it('um serviço curto ocupa um pedaço só', () => {
    const coluna = montarLayoutSimples(semanaCom(carro(1, SEGUNDA, 1, 1))).get(1)!
    expect(coluna.inicios.get(0)).toMatchObject({ span: 1 })
    expect(coluna.cobertas.has(1)).toBe(false)
  })

  it('o dia inteiro ocupa os 5 pedaços do dia, sem partir no almoço', () => {
    const coluna = montarLayoutSimples(semanaCom(carro(1, SEGUNDA, 1, 9))).get(1)!
    expect(coluna.inicios.get(0)).toMatchObject({ span: 5 })
    expect([...coluna.cobertas]).toEqual([0, 1, 2, 3, 4])
  })

  it('quem atravessa o meio-dia é um retângulo só', () => {
    // 11:00 até 15:00: faixa 4, almoço e faixa 6 — pedaços 3 e 3, depois 13:30 ainda é o 3.
    const coluna = montarLayoutSimples(semanaCom(carro(1, SEGUNDA, 4, 3))).get(1)!
    expect(coluna.inicios.get(2)).toMatchObject({ span: 1 })
  })

  it('serviço que passa do dia continua no dia seguinte', () => {
    const item = carro(1, SEGUNDA, 8, 1)
    item.segmentos = [
      { data: SEGUNDA, faixaInicio: 8, quantidade: 2 },
      { data: '2026-09-22', faixaInicio: 1, quantidade: 2 },
    ]
    const coluna = montarLayoutSimples(semanaCom(item)).get(1)!
    expect(coluna.inicios.get(4)).toMatchObject({ span: 3 }) // do 5º pedaço de segunda ao 2º de terça
  })

  it('serviço vindo da semana anterior aparece cortado no começo de segunda', () => {
    const anterior = carro(9, '2026-09-18', 7, 1)
    anterior.segmentos = [
      { data: '2026-09-18', faixaInicio: 7, quantidade: 2 },
      { data: SEGUNDA, faixaInicio: 1, quantidade: 4 },
    ]
    const coluna = montarLayoutSimples(semanaCom(anterior)).get(1)!
    expect(coluna.inicios.get(0)).toMatchObject({ span: 3 }) // manhã de segunda
  })

  it('dois serviços no mesmo pedaço dividem a célula', () => {
    const coluna = montarLayoutSimples(semanaCom(carro(1, SEGUNDA, 1, 1), carro(2, SEGUNDA, 2, 1))).get(1)!
    expect(coluna.inicios.get(0)?.itens.map((i) => i.id)).toEqual([1])
    expect(coluna.inicios.get(1)?.itens.map((i) => i.id)).toEqual([2])
  })

  it('cada coluna desenha só os seus', () => {
    const layout = montarLayoutSimples(semanaCom(carro(1, SEGUNDA, 1, 1), carro(2, SEGUNDA, 1, 1, 2)))
    expect(layout.get(1)!.inicios.get(0)?.itens.map((i) => i.id)).toEqual([1])
    expect(layout.get(2)!.inicios.get(0)?.itens.map((i) => i.id)).toEqual([2])
  })
})

describe('espaço livre (nada empurra ninguém)', () => {
  const coluna = (...itens: Agendamento[]) => montarLayoutSimples(semanaCom(...itens)).get(1)!

  it('conta os pedaços livres até o próximo serviço', () => {
    // 1º pedaço ocupado, o seguinte às 13:30 (4º pedaço): sobram 2 livres no meio.
    const c = coluna(carro(1, SEGUNDA, 1, 1), carro(2, SEGUNDA, 7, 1))
    expect(pedacosLivresApartirDe(c, 1, 25)).toBe(2)
  })

  it('o próprio serviço não é obstáculo para ele mesmo', () => {
    const c = coluna(carro(1, SEGUNDA, 1, 2), carro(2, SEGUNDA, 7, 1))
    expect(pedacosLivresApartirDe(c, 0, 25)).toBe(0)
    expect(pedacosLivresApartirDe(c, 0, 25, 1)).toBe(3) // pedaços 1 a 3
  })

  it('sem serviço abaixo, o espaço vai até o fim da semana', () => {
    const c = coluna(carro(1, SEXTA, 7, 1))
    expect(pedacosLivresApartirDe(c, 20, 25)).toBe(4)
  })

  it('conta também os pedaços livres acima, para a borda de cima', () => {
    const c = coluna(carro(1, SEGUNDA, 1, 1), carro(2, SEGUNDA, 7, 1))
    expect(pedacosLivresAcimaDe(c, 2)).toBe(2) // pedaços 3 e 2 estão livres
    expect(pedacosLivresAcimaDe(c, 2, 2)).toBe(2)
    expect(pedacosLivresAcimaDe(c, 0)).toBe(0) // o 1º pedaço é do serviço 1
  })
})

describe('copiar e colar guardam os horários, não as horas', () => {
  it('as mesmas horas ocupam um horário num lugar e dois noutro', () => {
    const semana = semanaCom()
    // 2h no 2º horário (09:00–11:00) cabem nele; as mesmas 2h no 1º (07:30–09:00) transbordam.
    expect(pedacosDeHoras(semana, 1, 2)).toBe(1)
    expect(pedacosDeHoras(semana, 0, 2)).toBe(2)
  })

  it('um horário colado noutro lugar continua sendo um horário', () => {
    const semana = semanaCom()
    const origem = linhaDoInicio(semana, SEGUNDA, 2) // 2º horário de segunda
    const pedacos = pedacosDeHoras(semana, origem, 2)
    const destino = linhaDoInicio(semana, SEGUNDA, 1) // 1º horário
    const horasLa = horasDeIntervalo(semana, destino, destino + pedacos - 1)

    expect(pedacos).toBe(1)
    expect(horasLa).toBe(1.5) // vira 1h30, que é o que cabe num horário ali
    expect(pedacosDeHoras(semana, destino, horasLa)).toBe(1)
  })

  it('acha a linha em que um serviço começa', () => {
    const semana = semanaCom()
    expect(linhaDoInicio(semana, SEGUNDA, 1)).toBe(0)
    expect(linhaDoInicio(semana, SEGUNDA, 7)).toBe(3)
    expect(linhaDoInicio(semana, '2026-09-22', 1)).toBe(5)
  })
})
