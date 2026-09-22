/**
 * Meses e semanas como a planilha: cada mês tem só os dias dele, e a semana que cai em
 * dois meses é cortada na virada (28/09 a 02/10 vira 28–30/09 em setembro e 01–02/10 em
 * outubro). Espelho do FaixasDoDia.semanaNoMes do servidor.
 */

export interface Periodo {
  inicio: string
  fim: string
}

export const MESES = [
  'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
  'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro',
]

const comoData = (iso: string) => new Date(`${iso}T12:00:00`)
export const paraIso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const fimDeSemana = (d: Date) => d.getDay() === 0 || d.getDay() === 6

/** A semana de uma data, cortada no mês dela. Sábado e domingo contam como a segunda seguinte. */
export function semanaNoMes(iso: string): Periodo {
  const d = comoData(iso)
  while (fimDeSemana(d)) d.setDate(d.getDate() + 1)
  const segunda = new Date(d)
  segunda.setDate(d.getDate() - (d.getDay() - 1))
  const sexta = new Date(segunda)
  sexta.setDate(segunda.getDate() + 4)
  const primeiro = new Date(d.getFullYear(), d.getMonth(), 1, 12)
  const ultimo = new Date(d.getFullYear(), d.getMonth() + 1, 0, 12)
  return {
    inicio: paraIso(segunda < primeiro ? primeiro : segunda),
    fim: paraIso(sexta > ultimo ? ultimo : sexta),
  }
}

/** As semanas (cortadas) de um mês, na ordem. `mes` é 0 a 11. */
export function semanasDoMes(ano: number, mes: number): Periodo[] {
  const semanas: Periodo[] = []
  const d = new Date(ano, mes, 1, 12)
  while (d.getMonth() === mes) {
    if (!fimDeSemana(d)) {
      const s = semanaNoMes(paraIso(d))
      if (!semanas.some((x) => x.inicio === s.inicio)) semanas.push(s)
    }
    d.setDate(d.getDate() + 1)
  }
  return semanas
}

/** O mês inteiro de uma data: do dia 1 ao último dia. */
export function mesDe(iso: string): Periodo {
  const d = comoData(iso)
  return {
    inicio: paraIso(new Date(d.getFullYear(), d.getMonth(), 1, 12)),
    fim: paraIso(new Date(d.getFullYear(), d.getMonth() + 1, 0, 12)),
  }
}

/** A semana (cortada) seguinte ou anterior, atravessando a virada do mês. */
export function outraSemana(atual: Periodo, passo: 1 | -1): Periodo {
  const d = comoData(passo === 1 ? atual.fim : atual.inicio)
  do d.setDate(d.getDate() + passo)
  while (fimDeSemana(d))
  return semanaNoMes(paraIso(d))
}

/** Primeiro dia útil de um mês deslocado de `passo` meses a partir da data. */
export function outroMes(iso: string, passo: number): string {
  const d = comoData(iso)
  const alvo = new Date(d.getFullYear(), d.getMonth() + passo, 1, 12)
  while (fimDeSemana(alvo)) alvo.setDate(alvo.getDate() + 1)
  return paraIso(alvo)
}

export const nomeDoMes = (iso: string) => {
  const d = comoData(iso)
  return `${MESES[d.getMonth()]} ${d.getFullYear()}`
}

/** "28–30/09", "01–02/10", "30/09". */
export function rotuloPeriodo({ inicio, fim }: Periodo) {
  const [, mi, di] = inicio.split('-')
  const [, mf, df] = fim.split('-')
  if (inicio === fim) return `${di}/${mi}`
  return mi === mf ? `${di}–${df}/${mf}` : `${di}/${mi}–${df}/${mf}`
}

export const hojeIso = () => paraIso(new Date())
