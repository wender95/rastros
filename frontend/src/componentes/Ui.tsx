import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../api/client'
import { ouvirMudancas } from '../api/mudancas'
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
 * Vendedores da agenda. O código (a letra do cronograma) é o que fica gravado no card; o
 * nome é só a exibição. A lista é cadastrada na própria agenda (✎ Vendedores). Os removidos
 * vêm também (ativo = false): os cards antigos ainda mostram o nome, mas eles não aparecem
 * para escolher.
 */
export interface Vendedor {
  id: number
  codigo: string
  nome: string
  ativo: boolean
}

/**
 * Um status da legenda da agenda. Os do sistema (com `chave`) têm regra por trás e só mudam
 * de nome e cor; os criados na agenda (`chave` nula) são etiquetas de um serviço que ainda
 * não começou e podem ser removidos.
 */
export interface StatusDaLegenda {
  id: number
  chave: StatusAgendamento | null
  nome: string
  cor: string
  ordem: number
  doSistema: boolean
}

/** A legenda de fábrica: vale até a do servidor chegar (e se ela não vier). */
const LEGENDA_PADRAO: StatusDaLegenda[] = (
  [
    ['PROGRAMADO', 'Programado', '#94a3b8'],
    ['EM_PATIO', 'Em pátio', '#0284c7'],
    ['EXECUTANDO', 'Executando', '#d97706'],
    ['CONCLUIDO', 'Concluído', '#16a34a'],
    ['NAO_VEIO', 'Não veio', '#dc2626'],
    ['EXTERNO', 'Externo', '#7c3aed'],
  ] as [StatusAgendamento, string, string][]
).map(([chave, nome, cor], i) => ({ id: -(i + 1), chave, nome, cor, ordem: i + 1, doSistema: true }))

let vendedores: Vendedor[] = []
let legenda: StatusDaLegenda[] = LEGENDA_PADRAO
let buscaVendedores: Promise<Vendedor[]> | null = null
let buscaLegenda: Promise<StatusDaLegenda[]> | null = null
const aoMudarVendedores = new Set<(l: Vendedor[]) => void>()
const aoMudarLegenda = new Set<(l: StatusDaLegenda[]) => void>()

/** Busca a lista uma vez por sessão; `forcar` busca de novo (depois de editar). */
export function carregarVendedores(forcar = false): Promise<Vendedor[]> {
  if (!buscaVendedores || forcar) {
    buscaVendedores = api
      .get<Vendedor[]>('/vendedores')
      .then((lista: Vendedor[]) => {
        vendedores = lista
        aoMudarVendedores.forEach((f) => f(lista))
        return lista
      })
      .catch(() => {
        buscaVendedores = null // tenta de novo na próxima tela
        return vendedores
      })
  }
  return buscaVendedores
}

export function carregarLegenda(forcar = false): Promise<StatusDaLegenda[]> {
  if (!buscaLegenda || forcar) {
    buscaLegenda = api
      .get<StatusDaLegenda[]>('/status-agenda')
      .then((lista: StatusDaLegenda[]) => {
        legenda = lista
        pintarLegenda(lista)
        aoMudarLegenda.forEach((f) => f(lista))
        return lista
      })
      .catch(() => {
        buscaLegenda = null
        return legenda
      })
  }
  return buscaLegenda
}

/**
 * Alguém editou a legenda ou os vendedores (nesta ou noutra tela): busca de novo. Liga na
 * primeira tela que usa a lista, e desliga quando nenhuma usa mais.
 */
let pararDeOuvir: (() => void) | null = null
function acompanharEdicoes() {
  if (pararDeOuvir) return
  pararDeOuvir = ouvirMudancas((assuntos) => {
    if (assuntos !== '' && !assuntos.split(',').includes('legenda')) return
    if (aoMudarLegenda.size > 0) carregarLegenda(true)
    if (aoMudarVendedores.size > 0) carregarVendedores(true)
  })
}
function largarEdicoes() {
  if (aoMudarLegenda.size > 0 || aoMudarVendedores.size > 0 || !pararDeOuvir) return
  pararDeOuvir()
  pararDeOuvir = null
}

/** A lista de vendedores (todos; filtre `ativo` para escolher), com re-render quando muda. */
export function useVendedores(): Vendedor[] {
  const [lista, setLista] = useState(vendedores)
  useEffect(() => {
    aoMudarVendedores.add(setLista)
    acompanharEdicoes()
    carregarVendedores().then(setLista)
    return () => {
      aoMudarVendedores.delete(setLista)
      largarEdicoes()
    }
  }, [])
  return lista
}

/** A legenda de status, com re-render quando muda — as telas que mostram status chamam. */
export function useLegenda(): StatusDaLegenda[] {
  const [lista, setLista] = useState(legenda)
  useEffect(() => {
    aoMudarLegenda.add(setLista)
    acompanharEdicoes()
    carregarLegenda().then(setLista)
    return () => {
      aoMudarLegenda.delete(setLista)
      largarEdicoes()
    }
  }, [])
  return lista
}

/** Cai no próprio código se aparecer uma letra que ainda não tem vendedor. */
export const rotuloVendedor = (codigo: string | null) =>
  codigo ? (vendedores.find((v) => v.codigo === codigo.toUpperCase())?.nome ?? codigo) : ''

/** O status que o card mostra: o criado na agenda (se ainda existe) ou o do sistema. */
export function statusDaLegenda(status: StatusAgendamento, etiquetaId?: number | null): StatusDaLegenda | undefined {
  const etiqueta = etiquetaId != null ? legenda.find((s) => s.id === etiquetaId) : undefined
  return etiqueta ?? legenda.find((s) => s.chave === status) ?? LEGENDA_PADRAO.find((s) => s.chave === status)
}

/** A classe de cor do card/chip: `ag-executando`, ou `ag-e12` para um status criado na agenda. */
export function classeStatus(status: StatusAgendamento, etiquetaId?: number | null) {
  const s = statusDaLegenda(status, etiquetaId)
  return s && !s.doSistema ? `ag-e${s.id}` : `ag-${status.toLowerCase()}`
}

export const rotuloStatusAgenda = (status: StatusAgendamento, etiquetaId?: number | null) =>
  statusDaLegenda(status, etiquetaId)?.nome ?? status

/** O nome de fábrica de um status do sistema: se foi renomeado, as telas usam o nome novo. */
export const nomePadraoDoStatus = (status: StatusAgendamento) =>
  LEGENDA_PADRAO.find((s) => s.chave === status)?.nome

export function ChipStatusAgenda({ status, etiquetaId }: { status: StatusAgendamento; etiquetaId?: number | null }) {
  return <span className={`chip ${classeStatus(status, etiquetaId)}`}>{rotuloStatusAgenda(status, etiquetaId)}</span>
}

/**
 * As cores da legenda viram regras de CSS: a cor forte é a barra; o fundo e o texto saem
 * dela. Os do sistema com a cor de fábrica ficam com o CSS feito à mão (styles.css).
 */
function pintarLegenda(lista: StatusDaLegenda[]) {
  const regras = lista
    .filter((s) => !s.doSistema || LEGENDA_PADRAO.find((p) => p.chave === s.chave)?.cor !== s.cor.toLowerCase())
    .map((s) => {
      const classe = s.doSistema ? `ag-${s.chave!.toLowerCase()}` : `ag-e${s.id}`
      const fundo = `color-mix(in srgb, ${s.cor} 16%, white)`
      return (
        `.${classe}{background:${fundo};color:color-mix(in srgb, ${s.cor} 55%, black);--barra:${s.cor};}` +
        `.card-simples.${classe},.painel-projetos li.${classe}{background-color:${fundo};}` +
        `.menu-cor.${classe}{background:${s.cor};}`
      )
    })
  let estilo = document.getElementById('cores-da-legenda')
  if (!estilo) {
    estilo = document.createElement('style')
    estilo.id = 'cores-da-legenda'
    document.head.appendChild(estilo)
  }
  estilo.textContent = regras.join('\n')
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
