/**
 * Datas da agenda: cálculo puro — sem React, sem rede — para poder ser testado e ficar
 * igual ao servidor.
 */

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
