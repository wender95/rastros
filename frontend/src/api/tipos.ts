export type PerfilNome = 'OPERACIONAL' | 'VENDEDOR' | 'DIRETORIA' | 'ADMIN' | 'FINANCEIRO'

export type SetorNome =
  | 'CRIACAO'
  | 'IMPRESSAO'
  | 'RECORTE'
  | 'PREPARACAO'
  | 'ACABAMENTO'
  | 'FROTA'
  | 'PRATELEIRA'
  | 'PATIO'
  | 'FINANCEIRO'

export type StatusFluxo = 'AGUARDANDO_RECEBIMENTO' | 'EM_PROCESSAMENTO' | 'ENCERRADA' | 'CANCELADA'

export type TipoEvento =
  | 'CRIACAO'
  | 'RECEBIMENTO'
  | 'DESPACHO'
  | 'RETORNO'
  | 'CANCELAMENTO'
  | 'ENTREGA'
  | 'CONCLUSAO'

export interface Usuario {
  id: number
  nome: string
  /** O que a pessoa digita para entrar. */
  login: string
  /** Contato, opcional. */
  email: string | null
  perfil: PerfilNome
  setor: SetorNome | null
  setorId: number | null
  ativo: boolean
  /** Senha provisória: a troca de senha aparece antes de qualquer outra tela. */
  trocarSenha?: boolean
  /** Tem uma coluna na agenda dos adesivadores: vê a aba "Minha agenda". */
  temAgenda?: boolean
}

export interface LoginResponse {
  token: string
  expiraEmSegundos: number
  usuario: Usuario
}

export interface Setor {
  id: number
  nome: SetorNome
  ativo: boolean
  localFisico: boolean
}

export interface Fluxo {
  id: number
  osId: number
  numeroOsErp: string
  cliente: string | null
  identificadorFluxo: string
  status: StatusFluxo
  setorAtualId: number
  setorAtual: SetorNome
  setorAnterior: SetorNome | null
  recebidoPor: string | null
  recebidoEm: string | null
  entrouNoSetorEm: string
  encerrado: boolean
  encerradoEm: string | null
  motivoCancelamento: string | null
  criadoEm: string
  criadoPor: string
  segundosNoSetor: number
}

export interface Evento {
  id: number
  tipoEvento: TipoEvento
  setorOrigem: SetorNome | null
  setorDestino: SetorNome
  usuario: string
  perfilUsuario: PerfilNome
  dataHora: string
  observacao: string | null
}

export interface DestinoPermitido {
  setorId: number
  setor: SetorNome
  retorno: boolean
}

export interface AcoesDisponiveis {
  podeReceber: boolean
  podeDespachar: boolean
  podeCancelar: boolean
  destinos: DestinoPermitido[]
}

/** Uma OS na tela do setor. */
export interface ItemMovimentacao {
  fluxoId: number
  numeroOsErp: string
  cliente: string | null
  identificador: string
  veioDe: SetorNome | null
  desde: string
  /** Tempo desde então, só em horário comercial. */
  segundosDesde: number
  recebidoPor: string | null
  destinos: DestinoPermitido[]
  devolverPara: SetorNome | null
  /** Só no Financeiro: a OS recebida pode ser concluída. */
  podeConcluir: boolean
}

export interface Movimentacao {
  setor: SetorNome
  paraReceber: ItemMovimentacao[]
  noSetor: ItemMovimentacao[]
}

export interface FluxoDetalhe {
  fluxo: Fluxo
  eventos: Evento[]
  acoes: AcoesDisponiveis
}

export interface Ordem {
  id: number
  numeroOsErp: string
  cliente: string | null
  criadoPor: string
  criadoEm: string
  cancelada: boolean
  motivoCancelamento: string | null
  fluxos: Fluxo[]
}

export interface ContagemSetor {
  setor: SetorNome
  aguardando: number
  emProcessamento: number
  /** No período: OS que saíram do setor. */
  saidasNoPeriodo: number
}

export interface Backup {
  arquivo: string
  tamanhoBytes: number
  criadoEm: string
}

export interface Painel {
  fluxosAtivos: number
  aguardandoRecebimento: number
  emProcessamento: number
  inicio: string
  fim: string
  osAbertas: number
  concluidas: number
  canceladas: number
  porSetor: ContagemSetor[]
}

export interface Transicao {
  id: number
  setorOrigem: SetorNome | null
  setorDestino: SetorNome
}

// ---------------------------------------------------- produtividade (métricas)

/** Uma pessoa dentro do setor: quem recebeu a OS (ou quem abriu, no comercial). */
export interface ProdutividadePessoa {
  usuarioId: number
  nome: string
  quantidade: number
  horasMediaNoSetor: number | null
}

export interface ProdutividadeSetor {
  setor: SetorNome
  processadas: number
  horasMediaNoSetor: number | null
  horasMediaEspera: number | null
  horasMediaProcessamento: number | null
  retornos: number
  pessoas: ProdutividadePessoa[]
}

export interface ProdutividadeComercial {
  abertas: number
  pessoas: ProdutividadePessoa[]
}

export interface ProdutividadeAdesivador {
  adesivadorId: number
  adesivador: string
  servicos: number
  concluidos: number
  scoreConcluido: number | null
  scoreAgendado: number | null
  horasAgendadas: number
  horasIndisponiveis: number
  naoCompareceu: number
}

export interface ServicoDoRelatorio {
  agendamentoId: number
  data: string
  diaSemana: string
  horarioInicio: string
  horarioFim: string
  /** Dia em que termina — diferente de data quando o serviço passa do fim do dia. */
  dataFim: string
  diaSemanaFim: string
  /** Em quantos dias úteis o serviço se estende (1 = mesmo dia). */
  dias: number
  descricao: string
  status: StatusAgendamento
  tipo: TipoAgendamento
  vendedor: string | null
  numeroOsErp: string | null
  osId: number | null
  materialPronto: boolean | null
  score: number | null
  horasEstimadas: number
}

export interface RelatorioAdesivador {
  adesivadorId: number
  adesivador: string
  servicos: ServicoDoRelatorio[]
  totalServicos: number
  concluidos: number
  naoCompareceu: number
  /** Soma dos pontos dos serviços concluídos. */
  score: number | null
  /** Soma dos pontos de todos os serviços com score, concluídos ou não. */
  scoreLancado: number | null
  horasIndisponiveis: number
}

export interface RelatorioSemanal {
  inicio: string
  fim: string
  adesivadores: RelatorioAdesivador[]
}

export interface Produtividade {
  inicio: string
  fim: string
  comercial: ProdutividadeComercial
  setores: ProdutividadeSetor[]
  adesivadores: ProdutividadeAdesivador[]
}

// ---------------------------------------------------------------- agenda

export type TipoColunaAgenda = 'ADESIVADOR' | 'ENCAIXE' | 'NOTURNO'

export type StatusAgendamento =
  | 'PROGRAMADO'
  | 'EM_PATIO'
  | 'EXECUTANDO'
  | 'CONCLUIDO'
  | 'NAO_VEIO'
  | 'EXTERNO'

export interface OrdemAberta {
  id: number
  numeroOsErp: string
  cliente: string | null
  fluxosAtivos: number
  setores: SetorNome[]
}

export interface Adesivador {
  id: number
  nome: string
  tipo: TipoColunaAgenda
  ordem: number
  ativo: boolean
}

export interface MaterialFluxo {
  fluxoId: number
  identificador: string
  setorAtual: SetorNome
  status: StatusFluxo
}

export interface Material {
  osId: number
  numeroOsErp: string
  cliente: string | null
  osCancelada: boolean
  pronto: boolean
  fluxos: MaterialFluxo[]
}

export interface FaixaHoraria {
  indice: number
  inicio: string
  fim: string
  rotulo: string
  horas: number
  almoco: boolean
}

export type TipoAgendamento = 'SERVICO' | 'INDISPONIVEL'

/** Pedaço contíguo na grade; o almoço parte o serviço em dois. */
export interface SegmentoAgenda {
  data: string
  faixaInicio: number
  quantidade: number
}

export interface Agendamento {
  id: number
  data: string
  adesivadorId: number
  adesivador: string
  tipo: TipoAgendamento
  slotInicio: number
  faixasOcupadas: number
  segmentos: SegmentoAgenda[]
  horasEstimadas: number
  horarioInicio: string
  horarioFim: string
  descricao: string
  vendedorCodigo: string | null
  status: StatusAgendamento
  observacao: string | null
  material: Material | null
}

export interface DiaAgenda {
  data: string
  diaSemana: string
  agendamentos: Agendamento[]
}

export interface TotalPorColuna {
  adesivadorId: number
  total: number
}

export interface SemanaAgenda {
  inicio: string
  fim: string
  colunas: Adesivador[]
  dias: DiaAgenda[]
  /** Começaram numa semana anterior e ainda ocupam o começo desta. */
  continuacoes?: Agendamento[]
  faixas: FaixaHoraria[]
  horasUteisDoDia: number
  /** Horas de trabalho dos dias mostrados: 44 numa semana cheia (sexta até 17h). */
  horasUteisDaSemana: number
  horasOcupadas: TotalPorColuna[]
  horasIndisponiveis: TotalPorColuna[]
  ultimaAcao: string | null
}
