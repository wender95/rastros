import { api } from '../api/client'
import type { Agendamento, SemanaAgenda, StatusAgendamento } from '../api/tipos'

/** Um lugar na grade: dia, coluna (adesivador) e faixa inicial. */
export interface LugarNaGrade {
  data: string
  adesivadorId: number
  slotInicio: number
  horasEstimadas: number
}

/** As chamadas da agenda, num lugar só: a tela decide o quê; aqui fica o como. */
export const apiDaAgenda = {
  /** Muda o card de lugar; com `horasDoOcupante`, troca com o card que estava lá. */
  mover: (
    id: number,
    corpo: { data: string; adesivadorId: number; slotInicio: number; horasEstimadas?: number; horasDoOcupante?: number },
  ) => api.patch<SemanaAgenda>(`/agenda/${id}/mover`, corpo),

  /** Mais uma parte do mesmo serviço (colar): nome, vendedor, OS e estado são os mesmos. */
  novaParte: (id: number, lugar: LugarNaGrade) => api.post(`/agenda/${id}/partes`, lugar),

  /** O N sobre vários espaços: um bloco INDISPONÍVEL em cada um, com um Ctrl+Z só. */
  marcarIndisponivel: (lugares: LugarNaGrade[]) =>
    api.post<{ cards: number }>(`/agenda/indisponiveis`, { destinos: lugares }),

  /** O mesmo serviço replicado em vários espaços de uma vez (a alça do canto). */
  replicar: (id: number, destinos: LugarNaGrade[]) =>
    api.post<{ cards: number }>(`/agenda/${id}/replicas`, { destinos }),

  criar: (corpo: LugarNaGrade & { descricao: string; status?: StatusAgendamento; tipo?: 'INDISPONIVEL' }) =>
    api.post('/agenda', corpo),

  /** Salva o card como está, trocando só o que vier em `mudancas`. */
  atualizar: (item: Agendamento, mudancas: { descricao?: string; vendedorCodigo?: string | null }) =>
    api.put(`/agenda/${item.id}`, {
      data: item.data,
      adesivadorId: item.adesivadorId,
      tipo: item.tipo,
      slotInicio: item.slotInicio,
      horasEstimadas: item.horasEstimadas,
      descricao: item.descricao,
      vendedorCodigo: item.vendedorCodigo,
      status: item.status,
      // Sem isto, renomear o card apagava o status criado na agenda (etiqueta).
      etiquetaId: item.etiquetaId ?? null,
      osId: item.material?.osId ?? null,
      observacao: item.observacao,
      ...mudancas,
    }),

  /** Coluna Noturno: quem vai fazer o serviço (lista vazia tira todos). */
  atribuir: (id: number, adesivadorIds: number[]) =>
    api.put<Agendamento>(`/agenda/${id}/atribuidos`, { adesivadorIds }),

  mudarStatus: (id: number, status: StatusAgendamento, etiquetaId: number | null) =>
    api.patch(`/agenda/${id}/status`, { status, etiquetaId }),

  mudarHoras: (id: number, horasEstimadas: number) => api.patch(`/agenda/${id}/horas`, { horasEstimadas }),

  excluir: (id: number) => api.delete(`/agenda/${id}`),

  desfazer: () => api.post<{ desfeito: string }>('/agenda/desfazer'),

  statusEmLote: (ids: number[], status: StatusAgendamento, etiquetaId: number | null) =>
    api.post<{ cards: number }>('/agenda/lote/status', { ids, status, etiquetaId }),

  moverEmLote: (itens: (LugarNaGrade & { id: number })[]) => api.post<{ cards: number }>('/agenda/lote/mover', { itens }),

  excluirEmLote: (ids: number[]) => api.post<{ cards: number }>('/agenda/lote/excluir', { ids }),
}
