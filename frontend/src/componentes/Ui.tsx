import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import type { SetorNome, StatusAgendamento, StatusFluxo, TipoEvento } from '../api/tipos'

const ROTULO_SETOR: Record<SetorNome, string> = {
  CRIACAO: 'Criação',
  IMPRESSAO: 'Impressão',
  RECORTE: 'Recorte',
  PREPARACAO: 'Preparação',
  ACABAMENTO: 'Acabamento',
  FROTA: 'Frota',
  PRATELEIRA: 'Prateleira',
  PATIO: 'Pátio',
  FINANCEIRO: 'Financeiro',
}

const ROTULO_STATUS: Record<StatusFluxo, string> = {
  AGUARDANDO_RECEBIMENTO: 'Aguardando recebimento',
  EM_PROCESSAMENTO: 'Em processamento',
  ENCERRADA: 'Concluída',
  CANCELADA: 'Cancelada',
}

const CLASSE_STATUS: Record<StatusFluxo, string> = {
  AGUARDANDO_RECEBIMENTO: 'chip-aguardando',
  EM_PROCESSAMENTO: 'chip-processando',
  ENCERRADA: 'chip-encerrada',
  CANCELADA: 'chip-cancelada',
}

const ROTULO_EVENTO: Record<TipoEvento, string> = {
  CRIACAO: 'Fluxo criado',
  RECEBIMENTO: 'Recebida',
  DESPACHO: 'Despachada',
  RETORNO: 'Retornada',
  CANCELAMENTO: 'Cancelada',
  ENTREGA: 'Enviada ao Financeiro',
  CONCLUSAO: 'Concluída pelo Financeiro',
}

export const rotuloSetor = (setor: SetorNome) => ROTULO_SETOR[setor] ?? setor
export const rotuloStatus = (status: StatusFluxo) => ROTULO_STATUS[status] ?? status
export const rotuloEvento = (tipo: TipoEvento) => ROTULO_EVENTO[tipo] ?? tipo

export function ChipStatus({ status }: { status: StatusFluxo }) {
  return <span className={`chip ${CLASSE_STATUS[status]}`}>{rotuloStatus(status)}</span>
}

export function ChipSetor({ setor }: { setor: SetorNome }) {
  return <span className="chip chip-setor">{rotuloSetor(setor)}</span>
}

export function formatarDataHora(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** "2 d 4 h", "3 h 20 min", "45 min" - usado na coluna "parado há". */
/** Horas de um dia útil: 07:30–12:00 e 13:30–18:00. */
export const HORAS_DIA_UTIL = 9

/**
 * Tempo de OS, que o servidor já mede só em horário comercial. Por isso "dia" aqui é
 * dia útil de 9 horas, e não 24.
 */
export function formatarDuracao(segundos: number) {
  const minutos = Math.max(0, Math.floor(segundos / 60))
  if (minutos < 60) return `${minutos} min`
  const horas = Math.floor(minutos / 60)
  if (horas < HORAS_DIA_UTIL) {
    const resto = minutos % 60
    return resto ? `${horas} h ${resto} min` : `${horas} h`
  }
  const dias = Math.floor(horas / HORAS_DIA_UTIL)
  const restoHoras = horas % HORAS_DIA_UTIL
  const rotulo = dias === 1 ? '1 dia útil' : `${dias} dias úteis`
  return restoHoras ? `${rotulo} ${restoHoras} h` : rotulo
}

/**
 * Vendedores da agenda. O código de uma letra é o que vem do cronograma e é o que fica
 * gravado; o nome é só a exibição. A lista vem do servidor, montada dos usuários
 * cadastrados (a primeira letra do nome) — nenhum nome fica no código.
 */
export interface Vendedor {
  codigo: string
  nome: string
}

let vendedores: Vendedor[] = []
let carregando: Promise<Vendedor[]> | null = null

/** Busca a lista uma vez por sessão; chamadas seguintes reaproveitam. */
export function carregarVendedores(): Promise<Vendedor[]> {
  if (!carregando) {
    carregando = api
      .get<Vendedor[]>('/vendedores')
      .then((lista: Vendedor[]) => (vendedores = lista))
      .catch(() => {
        carregando = null // tenta de novo na próxima tela
        return vendedores
      })
  }
  return carregando
}

/** A lista de vendedores, e um re-render quando ela chega do servidor. */
export function useVendedores(): Vendedor[] {
  const [lista, setLista] = useState(vendedores)
  useEffect(() => {
    let ativo = true
    carregarVendedores().then((l) => ativo && setLista(l))
    return () => {
      ativo = false
    }
  }, [])
  return lista
}

/** Cai no próprio código se aparecer uma letra que ainda não tem vendedor. */
export const rotuloVendedor = (codigo: string | null) =>
  codigo ? (vendedores.find((v) => v.codigo === codigo.toUpperCase())?.nome ?? codigo) : ''

const ROTULO_STATUS_AGENDA: Record<StatusAgendamento, string> = {
  PROGRAMADO: 'Programado',
  EM_PATIO: 'Em pátio',
  EXECUTANDO: 'Executando',
  CONCLUIDO: 'Concluído',
  NAO_VEIO: 'Não veio',
  EXTERNO: 'Externo',
}

export const STATUS_AGENDA: StatusAgendamento[] = [
  'PROGRAMADO',
  'EM_PATIO',
  'EXECUTANDO',
  'CONCLUIDO',
  'NAO_VEIO',
  'EXTERNO',
]

export const rotuloStatusAgenda = (status: StatusAgendamento) => ROTULO_STATUS_AGENDA[status] ?? status

export function ChipStatusAgenda({ status }: { status: StatusAgendamento }) {
  return <span className={`chip ag-${status.toLowerCase()}`}>{rotuloStatusAgenda(status)}</span>
}

/** Frase curta com a situação do material da OS vinculada ao carro. */
export function resumoMaterial(material: {
  pronto: boolean
  osCancelada: boolean
  fluxos: { setorAtual: SetorNome }[]
}): string {
  if (material.osCancelada) return 'OS cancelada'
  if (material.fluxos.length === 0) return 'OS sem fluxos'
  const setores = [...new Set(material.fluxos.map((f) => rotuloSetor(f.setorAtual)))].join(', ')
  return material.pronto ? `Material pronto · ${setores}` : `Em produção · ${setores}`
}

/** "1h", "1h30", "9h" — evita "1.5 horas" na tela. */
export function formatarHoras(horas: number): string {
  const inteiras = Math.floor(horas)
  const minutos = Math.round((horas - inteiras) * 60)
  if (minutos === 0) return `${inteiras}h`
  if (inteiras === 0) return `${minutos}min`
  return `${inteiras}h${String(minutos).padStart(2, '0')}`
}

export function Aviso({ tipo, children }: { tipo: 'erro' | 'ok' | 'info'; children: ReactNode }) {
  return <div className={`aviso aviso-${tipo}`}>{children}</div>
}

export function Carregando({ texto = 'Carregando...' }: { texto?: string }) {
  return <div className="carregando">{texto}</div>
}

export function Vazio({ children }: { children: ReactNode }) {
  return <div className="vazio">{children}</div>
}
