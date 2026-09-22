import { describe, expect, it } from 'vitest'
import { formatarDuracao } from './Ui'

describe('tempo de OS em horário comercial', () => {
  it('mostra minutos e horas até completar um dia útil', () => {
    expect(formatarDuracao(45 * 60)).toBe('45 min')
    expect(formatarDuracao(90 * 60)).toBe('1 h 30 min')
    expect(formatarDuracao(8 * 3600)).toBe('8 h')
  })

  it('a partir de 9 horas conta em dias úteis de 9h, e não de 24', () => {
    expect(formatarDuracao(9 * 3600)).toBe('1 dia útil')
    expect(formatarDuracao(20 * 3600)).toBe('2 dias úteis 2 h')
  })
})
