import type { StatusAgendamento } from '../api/tipos'
import type { StatusDaLegenda } from '../componentes/Ui'

/** Os estados que se troca direto no card, na ordem e nas cores da legenda da agenda. */
export const STATUS_DO_MENU: StatusAgendamento[] = ['PROGRAMADO', 'EM_PATIO', 'EXECUTANDO', 'CONCLUIDO', 'NAO_VEIO']

/**
 * Teclas de estado, com o card selecionado — as iniciais que o pessoal já usava na
 * planilha. Com **espaços vazios** escolhidos em vez de um card, o N marca indisponível.
 */
export const TECLAS_DE_STATUS: Record<string, StatusAgendamento> = {
  b: 'PROGRAMADO',
  p: 'EM_PATIO',
  e: 'EXECUTANDO',
  c: 'CONCLUIDO',
  n: 'NAO_VEIO',
  x: 'EXTERNO',
}

/** Um status que dá para escolher no menu do card. */
export interface OpcaoDeStatus {
  status: StatusAgendamento
  /** Status criado na agenda: o card fica Programado, com o nome e a cor dele. */
  etiquetaId: number | null
  /** A tecla de atalho (vazia nos criados na agenda). */
  tecla: string
  nome: string
}

/**
 * Os status do menu do card, na ordem da legenda: os do sistema que se trocam direto no
 * card e os criados na agenda (esses sem tecla).
 */
export const opcoesDeStatus = (legenda: StatusDaLegenda[]): OpcaoDeStatus[] =>
  legenda
    .filter((s) => !s.chave || STATUS_DO_MENU.includes(s.chave))
    .map((s) => ({
      status: (s.chave ?? 'PROGRAMADO') as StatusAgendamento,
      etiquetaId: s.chave ? null : s.id,
      tecla: s.chave ? (Object.entries(TECLAS_DE_STATUS).find(([, t]) => t === s.chave)?.[0] ?? '') : '',
      nome: s.nome,
    }))
