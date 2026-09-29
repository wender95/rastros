import { useCallback, useEffect, useState, type MouseEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import MenuContexto, { type ItemMenu } from '../agenda/MenuContexto'
import {
  Execucao,
  horaNoDia,
  rotuloProjeto,
  textoDasPausas,
  type AgendaDoDia,
  type ProjetoDoDia,
} from '../agenda/projetos'
import { api } from '../api/client'
import { useMudancas } from '../api/mudancas'
import type { StatusFluxo } from '../api/tipos'
import { pode } from '../auth/acessos'
import { useAuth } from '../auth/AuthContext'
import { Aviso, ChipStatus, Vazio, classeStatus, useLegenda } from './Ui'

const ATUALIZAR_A_CADA_MS = 30_000

/** Uma OS parada no Acabamento. */
interface OsNoSetor {
  fluxoId: number
  osId: number
  numeroOsErp: string
  cliente: string | null
  servico: string | null
  identificadorFluxo: string
  status: StatusFluxo
  recebidoPor: string | null
  dataOs: string
  chegouEm: string
}

const data = (iso: string) => new Date(iso).toLocaleDateString('pt-BR')
const dataHora = (iso: string) =>
  new Date(iso).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })

/**
 * Busca de novo **na hora** em que algo muda no servidor (o adesivador iniciou ou concluiu,
 * uma OS andou) e, por garantia, a cada meio minuto.
 */
function useAoVivo<T>(rota: string) {
  const [dados, setDados] = useState<T | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const carregar = useCallback(() => {
    api
      .get<T>(rota)
      .then((d) => {
        setDados(d)
        setErro(null)
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Não foi possível carregar.'))
  }, [rota])
  useEffect(() => {
    carregar()
    const id = window.setInterval(carregar, ATUALIZAR_A_CADA_MS)
    return () => window.clearInterval(id)
  }, [carregar])
  useMudancas(carregar)
  return { dados, erro }
}

/**
 * A agenda de hoje de cada adesivador: o espelho da "Minha agenda" que cada um vê no
 * próprio login. O projeto em que ele está — o último que ele iniciou — vem em destaque;
 * os outros mostram se estão aguardando ou concluídos.
 */
export function AgendasDoDia({ dia }: { dia?: string } = {}) {
  useLegenda() // nomes e cores dos status, editados na agenda
  const { usuario } = useAuth()
  // Sem dia, é o de hoje (a TV); o painel escolhe o dia no navegador de dias.
  const { dados, erro } = useAoVivo<AgendaDoDia[]>(dia ? `/painel/agendas?data=${dia}` : '/painel/agendas')
  /** Botão direito num card: iniciar, pausar, retomar ou concluir por um adesivador. */
  const [menu, setMenu] = useState<{ x: number; y: number; projeto: ProjetoDoDia } | null>(null)
  const [resultado, setResultado] = useState<{ tipo: 'ok' | 'erro'; texto: string } | null>(null)
  const podeAgir = pode(usuario, 'painelAcoes')

  const abrirMenu = (e: MouseEvent, projeto: ProjetoDoDia) => {
    if (!podeAgir) return
    e.preventDefault()
    setMenu({ x: e.clientX, y: e.clientY, projeto })
  }

  const agir = (projeto: ProjetoDoDia, acao: 'iniciar' | 'pausar' | 'retomar' | 'concluir', feito: string) => {
    if (acao === 'concluir' && !window.confirm(`Concluir "${projeto.descricao}"?`)) return
    setResultado(null)
    api
      .post(`/painel/projetos/${projeto.agendamentoId}/${acao}`, {})
      .then(() => setResultado({ tipo: 'ok', texto: `"${projeto.descricao}" ${feito}.` }))
      .catch((e) => setResultado({ tipo: 'erro', texto: e instanceof Error ? e.message : 'Não foi possível.' }))
  }

  /** As ações que valem para o projeto agora, como os botões da Minha agenda. */
  const itensDoMenu = (p: ProjetoDoDia): ItemMenu[] => {
    const emAndamento = p.status === 'EXECUTANDO'
    const aguardando = p.status === 'PROGRAMADO' || p.status === 'EM_PATIO' || p.status === 'NAO_VEIO'
    return [
      { rotulo: '▶ Iniciar', aoEscolher: aguardando ? () => agir(p, 'iniciar', 'iniciado') : undefined },
      { rotulo: '⏸ Pausar', aoEscolher: emAndamento && !p.pausadoDesde ? () => agir(p, 'pausar', 'pausado') : undefined },
      { rotulo: '▶ Retomar', aoEscolher: emAndamento && p.pausadoDesde ? () => agir(p, 'retomar', 'retomado') : undefined },
      {
        rotulo: '✓ Concluir',
        separadorAntes: true,
        aoEscolher: emAndamento ? () => agir(p, 'concluir', 'concluído') : undefined,
      },
    ]
  }

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!dados) return <Vazio>Carregando a agenda dos adesivadores…</Vazio>
  if (dados.length === 0) return <Vazio>Nenhum adesivador na agenda.</Vazio>

  // O Noturno só aparece no dia em que tem serviço; vem depois dos adesivadores.
  const temServico = (a: AgendaDoDia) => a.carros.some((c) => c.tipo !== 'INDISPONIVEL')
  const agendas = [...dados.filter((a) => !a.noturno), ...dados.filter((a) => a.noturno && temServico(a))]

  return (
    <>
    {resultado && <Aviso tipo={resultado.tipo}>{resultado.texto}</Aviso>}
    {menu && (
      <MenuContexto
        x={menu.x}
        y={menu.y}
        titulo={menu.projeto.descricao}
        itens={itensDoMenu(menu.projeto)}
        aoFechar={() => setMenu(null)}
      />
    )}
    <div className={`painel-adesivadores${podeAgir ? ' painel-com-acoes' : ''}`}>
      {agendas.map((agenda) => {
        const projetos = agenda.carros.filter((c) => c.tipo !== 'INDISPONIVEL')
        const bloqueios = agenda.carros.filter((c) => c.tipo === 'INDISPONIVEL')
        // O destaque "Agora" é do último serviço que entrou em execução (iniciado ou retomado);
        // os demais, inclusive outros em execução, pausados e concluídos, ficam na lista.
        // Cada um aparece uma vez só: no destaque ou na lista.
        const emExecucao = projetos.filter((c) => c.atual)
        const concluidos = projetos.filter((c) => c.status === 'CONCLUIDO').length
        const demais = projetos.filter((c) => !c.atual)
        return (
          <section className={`painel-adesivador${agenda.noturno ? ' painel-noturno' : ''}`} key={agenda.adesivadorId}>
            <header className="painel-adesivador-topo">
              <strong>
                {agenda.noturno && <span aria-hidden="true">🌙 </span>}
                {agenda.adesivador}
              </strong>
              <span>
                {projetos.length === 0
                  ? 'sem projetos hoje'
                  : `${concluidos} de ${projetos.length} concluído${projetos.length > 1 ? 's' : ''}`}
              </span>
            </header>

            {emExecucao.length > 0 ? (
              <div className="painel-destaque">
                {emExecucao.map((atual) => (
                  <div
                    key={atual.agendamentoId}
                    className="painel-atual"
                    title={atual.pausas?.length ? textoDasPausas(atual.pausas, agenda.data) : undefined}
                    onContextMenu={(e) => abrirMenu(e, atual)}
                  >
                    <span className="painel-atual-rotulo">▶ Agora</span>
                    <strong>{atual.descricao}</strong>
                    <small>
                      {atual.iniciadoEm && `início ${horaNoDia(atual.iniciadoEm, agenda.data)}`}
                      {atual.os && ` · OS ${atual.os.numeroOsErp}`}
                    </small>
                    {agenda.noturno && !!atual.atribuidos?.length && (
                      <small className="painel-atribuidos">👥 {atual.atribuidos.join(' · ')}</small>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="painel-atual painel-atual-vazio">
                {bloqueios.length > 0 && projetos.length === 0 ? 'Indisponível hoje' : 'Nenhum projeto em andamento'}
              </div>
            )}

            {demais.length > 0 && (
              <ul className="painel-projetos">
                {/* Cada projeto pintado com a cor do estado, como o card da agenda. */}
                {demais.map((c) => (
                  <li
                    key={c.agendamentoId}
                    className={classeStatus(c.status, c.etiquetaId)}
                    onContextMenu={(e) => abrirMenu(e, c)}
                  >
                    <span className="painel-projeto-nome">{c.descricao}</span>
                    <span className="painel-projeto-linha">
                      <strong>{rotuloProjeto(c.status, c.etiquetaId)}</strong>
                      <Execucao projeto={c} dia={agenda.data} />
                      {c.pausadoDesde && (
                        <span className="painel-pausado" title={textoDasPausas(c.pausas ?? [], agenda.data)}>
                          ⏸ pausado desde {horaNoDia(c.pausadoDesde, agenda.data)}
                        </span>
                      )}
                      {c.os && <span>OS {c.os.numeroOsErp}</span>}
                    </span>
                    {/* No Noturno, quem vai fazer o serviço (atribuído pelo botão direito na agenda). */}
                    {agenda.noturno && (
                      <span className="painel-atribuidos">
                        {c.atribuidos?.length ? `👥 ${c.atribuidos.join(' · ')}` : 'Nenhum adesivador atribuído'}
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>
        )
      })}
    </div>
    </>
  )
}

/** As OS que estão no Acabamento agora — a que chegou primeiro no topo. */
export function OsNoAcabamento() {
  const { usuario } = useAuth()
  const navegar = useNavigate()
  const { dados, erro } = useAoVivo<OsNoSetor[]>('/painel/acabamento')
  const podeAbrir = pode(usuario, 'consulta')

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!dados) return <Vazio>Carregando…</Vazio>
  if (dados.length === 0) return <Vazio>Nenhuma OS no Acabamento agora.</Vazio>

  return (
    <div className="tabela-rolagem">
      <table className="tabela-cartoes">
        <thead>
          <tr>
            <th>OS</th>
            <th>Serviço / fluxo</th>
            <th>Cliente</th>
            <th>Data da OS</th>
            <th>Chegou no Acabamento</th>
            <th>Situação</th>
          </tr>
        </thead>
        <tbody>
          {dados.map((os) => (
            <tr
              key={os.fluxoId}
              className={podeAbrir ? 'clicavel' : undefined}
              onClick={podeAbrir ? () => navegar(`/fluxos/${os.fluxoId}`) : undefined}
            >
              <td className="celula-titulo">
                <strong>OS {os.numeroOsErp}</strong>
              </td>
              <td data-rotulo="Serviço">
                {os.servico ?? os.identificadorFluxo}
                {os.servico && os.identificadorFluxo !== os.servico && (
                  <small className="servico-da-os">{os.identificadorFluxo}</small>
                )}
              </td>
              <td data-rotulo="Cliente">{os.cliente ?? '—'}</td>
              <td className="mono" data-rotulo="Data da OS">
                {data(os.dataOs)}
              </td>
              <td className="mono" data-rotulo="Chegou no Acabamento">
                {dataHora(os.chegouEm)}
              </td>
              <td data-rotulo="Situação">
                <ChipStatus status={os.status} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** A previsão que o servidor guarda (Open-Meteo), para a linha do topo. */
interface Clima {
  cidade: string
  temperatura: number
  minima: number
  maxima: number
  chanceDeChuva: number | null
  /** Código do tempo (WMO). */
  codigo: number
}

/** O código do tempo em ícone e palavra. */
function tempoDe(codigo: number): [string, string] {
  if (codigo === 0) return ['☀️', 'céu limpo']
  if (codigo <= 2) return ['⛅', 'parcialmente nublado']
  if (codigo === 3) return ['☁️', 'nublado']
  if (codigo <= 48) return ['🌫️', 'neblina']
  if (codigo <= 57) return ['🌦️', 'garoa']
  if (codigo <= 67) return ['🌧️', 'chuva']
  if (codigo <= 77) return ['❄️', 'neve']
  if (codigo <= 82) return ['🌧️', 'pancadas de chuva']
  return ['⛈️', 'tempestade']
}

/**
 * Data, hora e previsão do tempo numa linha só, embaixo do título do painel. A hora anda
 * sozinha; a previsão se renova a cada meia hora. Sem internet no servidor, a linha fica só
 * com data e hora.
 */
export function LinhaDoDia() {
  const [agora, setAgora] = useState(() => new Date())
  const [clima, setClima] = useState<Clima | null>(null)

  useEffect(() => {
    const relogio = window.setInterval(() => setAgora(new Date()), 10_000)
    return () => window.clearInterval(relogio)
  }, [])

  useEffect(() => {
    const buscar = () =>
      api
        .get<Clima | undefined>('/painel/clima')
        .then((c) => setClima(c ?? null))
        .catch(() => undefined)
    buscar()
    const id = window.setInterval(buscar, 30 * 60_000)
    return () => window.clearInterval(id)
  }, [])

  const data = agora.toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  const hora = agora.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  const [icone, tempo] = clima ? tempoDe(clima.codigo) : ['', '']

  return (
    <p className="linha-do-dia">
      <span>{data.charAt(0).toUpperCase() + data.slice(1)}</span>
      <span className="linha-do-dia-hora">{hora}</span>
      {clima && (
        <span title={`Previsão para ${clima.cidade}`}>
          <span aria-hidden="true">{icone}</span> {clima.cidade} {clima.temperatura}° · {tempo} · mín {clima.minima}° máx{' '}
          {clima.maxima}°{clima.chanceDeChuva != null && ` · chuva ${clima.chanceDeChuva}%`}
        </span>
      )}
    </p>
  )
}
