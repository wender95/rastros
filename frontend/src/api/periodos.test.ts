import { describe, expect, it } from 'vitest'
import { mesDe, outraSemana, outroMes, rotuloPeriodo, semanaNoMes, semanasDoMes } from './periodos'

describe('semanas cortadas no mês, como a planilha', () => {
  it('a semana que cai em dois meses é cortada na virada', () => {
    expect(semanaNoMes('2026-09-29')).toEqual({ inicio: '2026-09-28', fim: '2026-09-30' })
    expect(semanaNoMes('2026-10-02')).toEqual({ inicio: '2026-10-01', fim: '2026-10-02' })
    expect(semanaNoMes('2026-10-03')).toEqual({ inicio: '2026-10-05', fim: '2026-10-09' }) // sábado vai para a segunda
  })

  it('lista as semanas do mês, a primeira e a última podem ser curtas', () => {
    const outubro = semanasDoMes(2026, 9)
    expect(outubro[0]).toEqual({ inicio: '2026-10-01', fim: '2026-10-02' })
    expect(outubro.at(-1)).toEqual({ inicio: '2026-10-26', fim: '2026-10-30' })
    expect(outubro).toHaveLength(5)
    expect(semanasDoMes(2026, 7).at(-1)).toEqual({ inicio: '2026-08-31', fim: '2026-08-31' }) // o "DIA 31/08" da planilha
  })

  it('andar de semana atravessa a virada do mês', () => {
    expect(outraSemana({ inicio: '2026-09-28', fim: '2026-09-30' }, 1)).toEqual({ inicio: '2026-10-01', fim: '2026-10-02' })
    expect(outraSemana({ inicio: '2026-10-01', fim: '2026-10-02' }, -1)).toEqual({ inicio: '2026-09-28', fim: '2026-09-30' })
  })

  it('mês inteiro, troca de mês e rótulo', () => {
    expect(mesDe('2026-09-15')).toEqual({ inicio: '2026-09-01', fim: '2026-09-30' })
    expect(outroMes('2026-09-15', 1)).toBe('2026-10-01')
    expect(outroMes('2026-10-15', 1)).toBe('2026-11-02') // 01/11/2026 é domingo
    expect(rotuloPeriodo({ inicio: '2026-09-28', fim: '2026-09-30' })).toBe('28–30/09')
  })
})
