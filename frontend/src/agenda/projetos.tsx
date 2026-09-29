import type { SetorNome, StatusAgendamento, StatusFluxo, TipoAgendamento } from '../api/tipos'
import { classeStatus, nomePadraoDoStatus, rotuloSetor, statusDaLegenda } from '../componentes/Ui'

/**
 * Projetos da agenda: o que a "Minha agenda" do adesivador e o painel operacional mostram.
 * O adesivador inicia e conclui o projeto; a OS anda sozinha dentro da Frota.
 */

export interface OsDoProjeto {
  osId: number
  numeroOsErp: string
  cliente: string | null
  servico: string | null
  fluxoId: number | null
  setorAtual: SetorNome | null
  statusFluxo: StatusFluxo | null
  recebidoPor: string | null
  /** Recebida na Frota e projeto em andamento: pode voltar a quem mandou. */
  devolverPara: SetorNome | null
}

export interface ProjetoDoDia {
  agendamentoId: number
  descricao: string
  tipo: TipoAgendamento
  status: StatusAgendamento
  /** Status criado na agenda que o card mostra. */
  etiquetaId?: number | null
  horarioInicio: string
  horarioFim: string
  comecaEm: string
  terminaEm: string
  horasEstimadas: number
  vendedorCodigo: string | null
  observacao: string | null
  os: OsDoProjeto | null
  iniciadoEm: string | null
  iniciadoPor: string | null
  concluidoEm: string | null
  /** Coluna Noturno: os adesivadores atribuídos. */
  atribuidos?: string[]
  /** Pausado agora: desde quando. */
  pausadoDesde?: string | null
  /** Todas as pausas do serviço, da primeira para a última. */
  pausas?: Pausa[]
  podePausar?: boolean
  podeRetomar?: boolean
  /** O projeto em que o adesivador está agora: o último que ele iniciou. */
  atual: boolean
  podeIniciar: boolean
  podeConcluir: boolean
}

export interface AgendaDoDia {
  adesivadorId: number
  adesivador: string
  data: string
  carros: ProjetoDoDia[]
  /** A coluna Noturno: o painel só a mostra quando tem serviço no dia. */
  noturno?: boolean
}

/** O que aconteceu ao iniciar ou concluir. */
export interface ResultadoProjeto {
  agendamentoId: number
  descricao: string
  status: StatusAgendamento
  numeroOsErp: string | null
  recebidas: number
  paraOPatio: number
  faltam: string[]
  osACaminho: boolean
}

/** O estado do projeto, na língua de quem adesiva. */
const ROTULO_PROJETO: Record<StatusAgendamento, string> = {
  PROGRAMADO: 'Aguardando',
  EM_PATIO: 'Aguardando · no pátio',
  EXECUTANDO: 'Em andamento',
  CONCLUIDO: 'Concluído',
  NAO_VEIO: 'Não veio',
  EXTERNO: 'Externo',
}

/**
 * O nome do estado para quem adesiva. Um status criado na agenda, ou um do sistema que foi
 * renomeado na legenda, aparece com o nome da legenda.
 */
export const rotuloProjeto = (status: StatusAgendamento, etiquetaId?: number | null) => {
  const daLegenda = statusDaLegenda(status, etiquetaId)
  if (daLegenda && (!daLegenda.doSistema || daLegenda.nome !== nomePadraoDoStatus(status))) return daLegenda.nome
  return ROTULO_PROJETO[status] ?? status
}

export function ChipProjeto({ status, etiquetaId }: { status: StatusAgendamento; etiquetaId?: number | null }) {
  return <span className={`chip ${classeStatus(status, etiquetaId)}`}>{rotuloProjeto(status, etiquetaId)}</span>
}

/** Onde está a OS do projeto, em palavras. */
export function situacaoDaOs(os: OsDoProjeto) {
  if (!os.setorAtual) return 'sem movimentação'
  if (os.statusFluxo === 'ENCERRADA') return 'concluída'
  if (os.statusFluxo === 'CANCELADA') return 'cancelada'
  const setor = rotuloSetor(os.setorAtual)
  if (os.setorAtual === 'PATIO' || os.setorAtual === 'PRATELEIRA') return `no ${setor}, aguardando o comercial liberar`
  if (os.setorAtual === 'FINANCEIRO') return 'no Financeiro'
  if (os.setorAtual === 'FROTA') {
    return os.statusFluxo === 'EM_PROCESSAMENTO'
      ? `na Frota, recebida${os.recebidoPor ? ` por ${os.recebidoPor}` : ''}`
      : 'chegou na Frota — é recebida quando o projeto começa'
  }
  return os.statusFluxo === 'EM_PROCESSAMENTO' ? `em produção: ${setor}` : `a caminho: esperando em ${setor}`
}

/** A frase que o adesivador lê depois de iniciar ou concluir. */
export function fraseDoResultado(r: ResultadoProjeto) {
  const os = r.numeroOsErp ? `OS ${r.numeroOsErp}` : null
  if (r.status === 'EXECUTANDO') {
    if (!os) return `"${r.descricao}" iniciado.`
    if (r.recebidas > 0) return `"${r.descricao}" iniciado. A ${os} foi recebida na Frota em seu nome.`
    if (r.osACaminho) return `"${r.descricao}" iniciado. A ${os} ainda não chegou na Frota — será recebida sozinha quando chegar.`
    return `"${r.descricao}" iniciado.`
  }
  if (!os) return `"${r.descricao}" concluído.`
  if (r.paraOPatio > 0) return `"${r.descricao}" concluído. A ${os} foi para o Pátio.`
  if (r.faltam.length > 0) {
    return `"${r.descricao}" concluído. A ${os} vai para o Pátio quando terminarem: ${r.faltam.join(', ')}.`
  }
  if (r.osACaminho) return `"${r.descricao}" concluído. A ${os} ainda não chegou na Frota — quando chegar, vai direto para o Pátio.`
  return `"${r.descricao}" concluído.`
}

/**
 * "09:12", ou "23/09 09:12" quando não foi no dia de referência. A agenda nova não tem
 * relógio: o horário que aparece é o real, de quando o adesivador iniciou e concluiu.
 */
export function horaNoDia(iso: string, diaIso: string) {
  const d = new Date(iso)
  const hora = d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  const mesmoDia =
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` === diaIso
  if (mesmoDia) return hora
  return `${d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })} ${hora}`
}

/** Início e conclusão reais do projeto, relativos ao dia mostrado na tela. */
/** Uma pausa do projeto: sem fim, está pausado agora. */
export interface Pausa {
  inicio: string
  fim: string | null
  /** Quem pausou e quem retomou. */
  pausadoPor?: string | null
  retomadoPor?: string | null
}

/** "10:15 às 10:40 (25 min)" para cada pausa — o que o ícone de pausa mostra ao passar o mouse. */
export function textoDasPausas(pausas: Pausa[], dia: string): string {
  const linhas = pausas.map((p) => {
    const ate = p.fim ? horaNoDia(p.fim, dia) : 'agora'
    const minutos = Math.round(((p.fim ? new Date(p.fim) : new Date()).getTime() - new Date(p.inicio).getTime()) / 60_000)
    const duracao = minutos >= 60 ? `${Math.floor(minutos / 60)} h ${String(minutos % 60).padStart(2, '0')} min` : `${minutos} min`
    const quem = [p.pausadoPor && `pausou: ${p.pausadoPor}`, p.retomadoPor && `retomou: ${p.retomadoPor}`]
      .filter(Boolean)
      .join(', ')
    return `${horaNoDia(p.inicio, dia)} às ${ate} (${duracao})${quem ? ` — ${quem}` : ''}`
  })
  return `${pausas.length === 1 ? 'Pausa' : `${pausas.length} pausas`}:\n${linhas.join('\n')}`
}

export function Execucao({ projeto, dia }: { projeto: Pick<ProjetoDoDia, 'iniciadoEm' | 'concluidoEm'>; dia: string }) {
  if (!projeto.iniciadoEm && !projeto.concluidoEm) return <span className="execucao execucao-vazia">não iniciado</span>
  return (
    <span className="execucao">
      {projeto.iniciadoEm && <span>Início {horaNoDia(projeto.iniciadoEm, dia)}</span>}
      {projeto.concluidoEm && <span>Conclusão {horaNoDia(projeto.concluidoEm, dia)}</span>}
    </span>
  )
}
