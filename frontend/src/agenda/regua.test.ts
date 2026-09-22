import { describe, expect, it } from 'vitest'
import { diasAlcancados, horasEntre, posicoesOcupadas } from '../api/faixas'
import type { Agendamento, FaixaHoraria, SemanaAgenda } from '../api/tipos'
import {
  diasUteisEntre,
  linhaFinalDe,
  montarLayout,
  primeiraLivreAcima,
  reempilharPrevia,
  segundaDaSemana,
  somarDias,
} from './regua'

/** A grade real: 07:30 às 18:00, com o almoço das 12:00 às 13:30. */
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

const ALMOCO = 4

describe('faixas', () => {
  it('pula o almoço e não conta a hora dele', () => {
    // 09:00 com 4,5h: 09-10, 10-11, 11-12, (almoço), 13:30-15:00
    expect(posicoesOcupadas(FAIXAS, 2, 4.5)).toEqual([1, 2, 3, 5])
  })

  it('traduz "puxei a borda até esta linha" em horas, sem somar o almoço', () => {
    expect(horasEntre(FAIXAS, 0, 5)).toBe(6) // 07:30 até 15:00
  })

  it('serviço maior que o dia avança para o seguinte', () => {
    expect(diasAlcancados(FAIXAS, 1, 18)).toBe(1)
    expect(diasAlcancados(FAIXAS, 1, 9)).toBe(0)
  })
})

describe('prévia do redimensionamento', () => {
  const posicoes = (resultado: Map<number, number[]>, id: number) => resultado.get(id)!

  it('aumentar empurra o de baixo, pulando o almoço', () => {
    const r = reempilharPrevia(
      FAIXAS,
      [
        { id: 1, posicao: 0, horas: 4.5 }, // cresceu: 07:30-12:00
        { id: 2, posicao: 2, horas: 1 },
      ],
      1,
    )
    expect(posicoes(r, 2)[0]).toBe(5) // 13:30
    expect(posicoes(r, 2)).not.toContain(ALMOCO)
  })

  it('diminuir não puxa ninguém para cima', () => {
    const r = reempilharPrevia(
      FAIXAS,
      [
        { id: 1, posicao: 0, horas: 1.5 },
        { id: 2, posicao: 5, horas: 1 },
      ],
      1,
    )
    expect(posicoes(r, 2)[0]).toBe(5)
  })

  it('sobreposição antiga entre dois que ninguém tocou fica como está', () => {
    const r = reempilharPrevia(
      FAIXAS,
      [
        { id: 1, posicao: 0, horas: 1.5 }, // editado, mas não encosta em ninguém
        { id: 7, posicao: 6, horas: 3.5 }, // vaza para o dia seguinte...
        { id: 8, posicao: 9, horas: 1.5 }, // ...por cima deste
      ],
      1,
    )
    expect(posicoes(r, 7)[0]).toBe(6)
    expect(posicoes(r, 8)[0]).toBe(9)
  })

  it('quem a edição atinge é empurrado em cascata', () => {
    const r = reempilharPrevia(
      FAIXAS,
      [
        { id: 1, posicao: 0, horas: 6 }, // até 15:00
        { id: 2, posicao: 3, horas: 1 },
        { id: 3, posicao: 6, horas: 1 },
      ],
      1,
    )
    expect(posicoes(r, 2)[0]).toBe(6)
    expect(posicoes(r, 3)[0]).toBe(7)
  })

  it('na mesma faixa, o editado fica e o outro desce', () => {
    const r = reempilharPrevia(
      FAIXAS,
      [
        { id: 1, posicao: 0, horas: 1.5 },
        { id: 2, posicao: 0, horas: 1 }, // editado
      ],
      2,
    )
    expect(posicoes(r, 2)[0]).toBe(0)
    expect(posicoes(r, 1)[0]).toBe(1)
  })
})

describe('datas da semana', () => {
  it('a semana começa na segunda', () => {
    expect(segundaDaSemana(new Date('2026-09-17T12:00:00'))).toBe('2026-09-14')
    expect(segundaDaSemana(new Date('2026-09-20T12:00:00'))).toBe('2026-09-14') // domingo
  })

  it('soma dias atravessando o mês', () => {
    expect(somarDias('2026-08-31', 7)).toBe('2026-09-07')
  })
})

describe('serviço que vem da semana anterior', () => {
  it('conta dias úteis pulando o fim de semana', () => {
    expect(diasUteisEntre('2026-09-18', '2026-09-21')).toBe(1) // sexta -> segunda
    expect(diasUteisEntre('2026-09-21', '2026-09-18')).toBe(-1)
    expect(diasUteisEntre('2026-09-21', '2026-09-25')).toBe(4)
  })

  it('aparece cortado no começo da segunda e ocupa a manhã', () => {
    const carro = (id: number, data: string, faixa: number, quantidade: number): Agendamento =>
      ({
        id,
        data,
        adesivadorId: 1,
        slotInicio: faixa,
        horasEstimadas: 1,
        segmentos: [{ data, faixaInicio: faixa, quantidade }],
      }) as Agendamento
    const semana = {
      inicio: '2026-09-21',
      fim: '2026-09-25',
      colunas: [{ id: 1 }],
      dias: ['2026-09-21', '2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25'].map((data) => ({
        data,
        diaSemana: '',
        agendamentos: [],
      })),
      // Sexta 15:00 com 7,5h: 15-18 na sexta e 07:30-12:00 na segunda (7 faixas na régua).
      continuacoes: [carro(9, '2026-09-18', 7, 7)],
      faixas: FAIXAS,
    } as unknown as SemanaAgenda

    const coluna = montarLayout(semana, 45, null).get(1)!

    expect(coluna.inicios.get(0)?.itens[0].id).toBe(9)
    expect(coluna.inicios.get(0)?.span).toBe(4) // 07:30 até 12:00 de segunda
    expect(coluna.cobertas.has(4)).toBe(false) // o almoço de segunda já está livre
  })

  it('na prévia, quem veio de antes é obstáculo e não é empurrado', () => {
    const r = reempilharPrevia(
      FAIXAS,
      [
        { id: 9, posicao: -3, horas: 7.5 }, // começou sexta 15:00
        { id: 1, posicao: 0, horas: 1.5 }, // editado: segunda 07:30
      ],
      1,
    )
    expect(r.get(9)![0]).toBe(-3)
    expect(r.get(1)![0]).toBe(5) // depois da continuação e do almoço
  })
})

describe('borda de cima do card', () => {
  const carro = (id: number, data: string, faixa: number, horas: number, quantidade: number): Agendamento =>
    ({
      id,
      data,
      adesivadorId: 1,
      slotInicio: faixa,
      horasEstimadas: horas,
      segmentos: [{ data, faixaInicio: faixa, quantidade }],
    }) as Agendamento
  const semanaCom = (...itens: Agendamento[]) =>
    ({
      inicio: '2026-09-21',
      fim: '2026-09-25',
      colunas: [{ id: 1 }],
      dias: ['2026-09-21', '2026-09-22', '2026-09-23', '2026-09-24', '2026-09-25'].map((data) => ({
        data,
        diaSemana: '',
        agendamentos: itens.filter((i) => i.data === data),
      })),
      continuacoes: [],
      faixas: FAIXAS,
    }) as unknown as SemanaAgenda

  it('acha a linha onde o card termina, mesmo passando para o dia seguinte', () => {
    // Terça (linha 9) às 16:00 com 4,5h: 16-17, 17-18 e quarta 07:30-10:00.
    expect(linhaFinalDe(FAIXAS, carro(1, '2026-09-22', 8, 4.5, 4), 16)).toBe(19)
    // Segunda 09:00 com 1h: termina na mesma linha.
    expect(linhaFinalDe(FAIXAS, carro(2, '2026-09-21', 2, 1, 1), 1)).toBe(1)
  })

  it('só sobe até a primeira linha ocupada por outro carro', () => {
    const de_cima = carro(1, '2026-09-21', 1, 2.5, 2) // 07:30 às 10:00
    const alvo = carro(2, '2026-09-21', 6, 1.5, 1) // 13:30
    const coluna = montarLayout(semanaCom(de_cima, alvo), 45, null).get(1)
    expect(primeiraLivreAcima(coluna, 5)).toBe(2) // 10:00 é a primeira livre
    expect(primeiraLivreAcima(montarLayout(semanaCom(alvo), 45, null).get(1), 5)).toBe(0)
  })

  it('a prévia desenha o card começando na nova linha', () => {
    const alvo = carro(2, '2026-09-21', 6, 1.5, 1)
    const coluna = montarLayout(semanaCom(alvo), 45, { id: 2, horas: 3.5, posicao: 2 }).get(1)!
    expect(coluna.inicios.get(2)?.itens[0].id).toBe(2)
    expect(coluna.inicios.has(5)).toBe(false)
  })
})
