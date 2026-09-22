import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SetorNome, StatusAgendamento, StatusFluxo, TipoAgendamento } from '../api/tipos'
import { Aviso, Carregando, ChipStatusAgenda, rotuloSetor, rotuloVendedor, useVendedores } from '../componentes/Ui'

const ATUALIZAR_A_CADA_MS = 30_000

interface OsDoCarro {
  osId: number
  numeroOsErp: string
  cliente: string | null
  fluxoId: number | null
  setorAtual: SetorNome | null
  statusFluxo: StatusFluxo | null
  recebidoPor: string | null
  podeReceber: boolean
  podeEntregar: boolean
  patioId: number | null
  devolverPara: SetorNome | null
}

interface CarroDoDia {
  agendamentoId: number
  descricao: string
  tipo: TipoAgendamento
  status: StatusAgendamento
  horarioInicio: string
  horarioFim: string
  comecaEm: string
  terminaEm: string
  horasEstimadas: number
  vendedorCodigo: string | null
  observacao: string | null
  os: OsDoCarro | null
}

interface MinhaAgendaDia {
  adesivador: string
  data: string
  carros: CarroDoDia[]
}

const DIAS = ['domingo', 'segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado']
const comoData = (iso: string) => new Date(`${iso}T12:00:00`)
const iso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const curto = (isoData: string) => {
  const d = comoData(isoData)
  return `${DIAS[d.getDay()].slice(0, 3)} ${String(d.getDate()).padStart(2, '0')}/${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** Próximo (ou anterior) dia útil: a agenda não tem sábado nem domingo. */
function outroDiaUtil(isoData: string, passo: 1 | -1) {
  const d = comoData(isoData)
  do d.setDate(d.getDate() + passo)
  while (d.getDay() === 0 || d.getDay() === 6)
  return iso(d)
}

function hojeUtil() {
  const d = new Date()
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1)
  return iso(d)
}

/** Onde está a OS do carro, em palavras. */
function situacaoDaOs(os: OsDoCarro) {
  if (!os.setorAtual) return 'sem movimentação'
  if (os.statusFluxo === 'ENCERRADA') return 'concluída'
  if (os.statusFluxo === 'CANCELADA') return 'cancelada'
  const setor = rotuloSetor(os.setorAtual)
  if (os.setorAtual === 'PATIO' || os.setorAtual === 'PRATELEIRA') return `no ${setor}, aguardando o comercial liberar`
  if (os.setorAtual === 'FINANCEIRO') return 'no Financeiro'
  if (os.setorAtual === 'FROTA') {
    return os.statusFluxo === 'EM_PROCESSAMENTO'
      ? `na Frota, recebida${os.recebidoPor ? ` por ${os.recebidoPor}` : ''}`
      : 'chegou na Frota — pronta para receber'
  }
  return os.statusFluxo === 'EM_PROCESSAMENTO' ? `em produção: ${setor}` : `a caminho: esperando em ${setor}`
}

/**
 * A agenda do adesivador, no celular: só os carros dele, dia a dia. É por aqui que a Frota
 * trabalha as OS: receber quando o material chega (o carro vira "Executando"), entregar no
 * Pátio quando fica pronto (vira "Concluído") ou devolver a quem mandou.
 */
export default function MinhaAgenda() {
  useVendedores() // os nomes chegam do servidor; o hook re-renderiza quando chegam
  const [dia, setDia] = useState(hojeUtil)
  const [dados, setDados] = useState<MinhaAgendaDia | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState<number | null>(null)
  /** Carro com a confirmação de entregar no Pátio ou de devolver aberta. */
  const [confirmando, setConfirmando] = useState<{ id: number; acao: 'entregar' | 'devolver' } | null>(null)

  const carregar = useCallback(() => {
    api
      .get<MinhaAgendaDia>(`/minha-agenda?data=${dia}`)
      .then((d) => {
        setDados(d)
        setErro(null)
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Não foi possível carregar a agenda.'))
  }, [dia])

  useEffect(() => {
    carregar()
    const id = window.setInterval(carregar, ATUALIZAR_A_CADA_MS)
    return () => window.clearInterval(id)
  }, [carregar])

  function agir(carro: CarroDoDia, rota: string, corpo: unknown, mensagem: string) {
    const os = carro.os
    if (!os?.fluxoId) return
    setOcupado(carro.agendamentoId)
    setErro(null)
    setFeito(null)
    api
      .post(`/fluxos/${os.fluxoId}/${rota}`, corpo)
      .then(() => {
        setFeito(mensagem)
        setConfirmando(null)
        carregar()
      })
      .catch((e) => {
        setErro(e instanceof Error ? e.message : 'Não foi possível concluir a ação.')
        carregar()
      })
      .finally(() => setOcupado(null))
  }

  const hoje = hojeUtil()
  const d = comoData(dia)

  return (
    <div className="movimentar minha-agenda">
      <div className="movimentar-topo">
        <h1>Minha agenda</h1>
        <button className="botao botao-secundario botao-grande" onClick={carregar}>
          ↻
        </button>
      </div>

      <div className="minha-agenda-dia">
        <button className="botao botao-secundario botao-grande" onClick={() => setDia(outroDiaUtil(dia, -1))} aria-label="Dia anterior">
          ←
        </button>
        <div className="minha-agenda-data">
          <strong>{dia === hoje ? 'Hoje' : DIAS[d.getDay()]}</strong>
          <span>{d.toLocaleDateString('pt-BR')}</span>
        </div>
        <button className="botao botao-secundario botao-grande" onClick={() => setDia(outroDiaUtil(dia, 1))} aria-label="Próximo dia">
          →
        </button>
      </div>
      {dia !== hoje && (
        <button className="botao botao-secundario minha-agenda-hoje" onClick={() => setDia(hoje)}>
          Voltar para hoje
        </button>
      )}

      {feito && <Aviso tipo="ok">{feito}</Aviso>}
      {erro && <Aviso tipo="erro">{erro}</Aviso>}

      {!dados && !erro ? (
        <Carregando texto="Carregando sua agenda..." />
      ) : dados && dados.carros.length === 0 ? (
        <p className="movimentar-vazio">Nenhum carro na sua agenda neste dia.</p>
      ) : (
        <div className="movimentar-lista">
          {dados?.carros.map((c) => (
            <div
              key={c.agendamentoId}
              className={`os-cartao carro-do-dia${c.tipo === 'INDISPONIVEL' ? ' carro-bloqueio' : ''}${confirmando?.id === c.agendamentoId ? ' os-cartao-aberto' : ''}`}
            >
              <div className="os-identificacao">
                <div className="carro-horario">
                  {c.horarioInicio} – {c.horarioFim}
                  {c.tipo !== 'INDISPONIVEL' && <ChipStatusAgenda status={c.status} />}
                </div>
                <div className="os-numero">{c.descricao}</div>
                {(c.comecaEm !== dia || c.terminaEm !== dia) && (
                  <div className="os-detalhe os-suave">
                    {c.comecaEm !== dia && `começou ${curto(c.comecaEm)}`}
                    {c.comecaEm !== dia && c.terminaEm !== dia && ' · '}
                    {c.terminaEm !== dia && `segue até ${curto(c.terminaEm)}`}
                  </div>
                )}
                {c.os ? (
                  <div className="os-detalhe">
                    OS {c.os.numeroOsErp}
                    {c.os.cliente && ` · ${c.os.cliente}`}
                    <div className="os-suave">{situacaoDaOs(c.os)}</div>
                  </div>
                ) : (
                  c.tipo !== 'INDISPONIVEL' && <div className="os-detalhe os-suave">Sem OS vinculada</div>
                )}
                {c.vendedorCodigo && <div className="os-detalhe os-suave">Vendedor: {rotuloVendedor(c.vendedorCodigo)}</div>}
                {c.observacao && !c.observacao.includes('[ficticio]') && (
                  <div className="os-detalhe os-suave">{c.observacao}</div>
                )}
              </div>
              {c.os?.podeReceber && (
                <div className="os-botoes">
                  <button
                    className="botao botao-grande botao-receber"
                    disabled={ocupado === c.agendamentoId}
                    onClick={() =>
                      agir(c, 'receber', {}, `OS ${c.os!.numeroOsErp} recebida — ${c.descricao} em execução.`)
                    }
                  >
                    ✓ Receber OS {c.os.numeroOsErp}
                  </button>
                </div>
              )}
              {c.os && (c.os.podeEntregar || c.os.devolverPara) && confirmando?.id !== c.agendamentoId && (
                <div className="os-botoes">
                  {c.os.podeEntregar && (
                    <button
                      className="botao botao-grande"
                      onClick={() => setConfirmando({ id: c.agendamentoId, acao: 'entregar' })}
                    >
                      Pronto — entregar no Pátio →
                    </button>
                  )}
                  {c.os.devolverPara && (
                    <button
                      className="botao botao-secundario botao-grande botao-devolver"
                      onClick={() => setConfirmando({ id: c.agendamentoId, acao: 'devolver' })}
                    >
                      ↩ Devolver para {rotuloSetor(c.os.devolverPara)}
                    </button>
                  )}
                </div>
              )}
              {c.os && confirmando?.id === c.agendamentoId && (
                <div className="os-escolha">
                  <p className="os-pergunta">
                    {confirmando.acao === 'entregar'
                      ? `${c.descricao} está pronto? A OS ${c.os.numeroOsErp} vai para o Pátio e o carro fica concluído.`
                      : `Devolver a OS ${c.os.numeroOsErp} para ${rotuloSetor(c.os.devolverPara!)}?`}
                  </p>
                  <div className="os-destinos">
                    <button
                      className={`botao botao-grande ${confirmando.acao === 'entregar' ? 'botao-receber' : 'botao-devolver'}`}
                      disabled={ocupado === c.agendamentoId}
                      onClick={() =>
                        confirmando.acao === 'entregar'
                          ? agir(c, 'despachar', { setorDestinoId: c.os!.patioId }, `OS ${c.os!.numeroOsErp} entregue no Pátio — ${c.descricao} concluído.`)
                          : agir(c, 'devolver', {}, `OS ${c.os!.numeroOsErp} devolvida para ${rotuloSetor(c.os!.devolverPara!)}.`)
                      }
                    >
                      Sim, {confirmando.acao === 'entregar' ? 'entregar' : 'devolver'}
                    </button>
                    <button className="botao botao-secundario botao-grande" onClick={() => setConfirmando(null)}>
                      Não
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
