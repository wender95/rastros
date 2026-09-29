import { describe, expect, it } from 'vitest'
import { diasAlcancados, posicoesOcupadas } from '../api/faixas'
import type { FaixaHoraria } from '../api/tipos'
import { diasUteisEntre } from './regua'

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

describe('faixas', () => {
  it('pula o almoço e não conta a hora dele', () => {
    // 09:00 com 4,5h: 09-10, 10-11, 11-12, (almoço), 13:30-15:00
    expect(posicoesOcupadas(FAIXAS, 2, 4.5)).toEqual([1, 2, 3, 5])
  })

  it('serviço maior que o dia avança para o seguinte', () => {
    expect(diasAlcancados(FAIXAS, 1, 18)).toBe(1)
    expect(diasAlcancados(FAIXAS, 1, 9)).toBe(0)
  })
})

describe('dias úteis', () => {
  it('conta dias úteis pulando o fim de semana', () => {
    expect(diasUteisEntre('2026-09-18', '2026-09-21')).toBe(1) // sexta -> segunda
    expect(diasUteisEntre('2026-09-21', '2026-09-18')).toBe(-1)
    expect(diasUteisEntre('2026-09-21', '2026-09-25')).toBe(4)
  })
})
