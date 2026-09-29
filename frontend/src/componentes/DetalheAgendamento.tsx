import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { bloqueioDesde, diasAlcancados, faixasCobertas } from '../api/faixas'
import type {
  Agendamento,
  FaixaHoraria,
  OrdemAberta,
  StatusAgendamento,
  TipoAgendamento,
} from '../api/tipos'
import {
  Aviso,
  ChipSetor,
  ChipStatus,
  classeStatus,
  useLegenda,
  useVendedores,
  formatarHoras,
} from './Ui'

/** Opções de horas estimadas, em passos que batem com as faixas do dia. */
const HORAS = [0.5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 7, 8]

/** Serviço longo: escolhe por dia inteiro (1 dia = as horas úteis do dia). */
const DIAS = [1, 2, 3, 4, 5, 8, 10, 15]

interface Props {
  agendamento: Agendamento | null
  novaCelula: { data: string; adesivadorId: number; slot: number } | null
  faixas: FaixaHoraria[]
  podeEditar: boolean
  /** Marcar o andamento do carro (hoje, quem pode editar a agenda). */
  podeMudarStatus: boolean
  /**
   * Esconde horário, duração e estado: na agenda simplificada isso tudo se resolve na
   * própria grade (o lugar do card, a alça do canto e o menu do botão direito), e repetir
   * aqui só dá duas verdades para a mesma coisa.
   */
  semHorarios?: boolean
  aoFechar: () => void
  aoSalvar: () => void
}

/** Motivos prontos para bloquear a agenda de um adesivador. */
const MOTIVOS_BLOQUEIO = ['FÉRIAS', 'FALTA', 'ATESTADO', 'FERIADO', 'TREINAMENTO']

/**
 * Painel de um carro da agenda: status, vínculo com a OS e, quando vinculado,
 * a situação de cada fluxo de material dessa OS.
 */
export default function DetalheAgendamento({
  agendamento,
  novaCelula,
  faixas,
  podeEditar,
  podeMudarStatus,
  semHorarios = false,
  aoFechar,
  aoSalvar,
}: Props) {
  const vendedores = useVendedores()
  const legenda = useLegenda()
  const [descricao, setDescricao] = useState(agendamento?.descricao ?? '')
  const [vendedor, setVendedor] = useState(agendamento?.vendedorCodigo ?? '')
  const [horas, setHoras] = useState(agendamento?.horasEstimadas ?? 1)
  const [slotInicio, setSlotInicio] = useState(agendamento?.slotInicio ?? novaCelula?.slot ?? 1)
  const [tipo, setTipo] = useState<TipoAgendamento>(agendamento?.tipo ?? 'SERVICO')
  const [status, setStatus] = useState<StatusAgendamento>(agendamento?.status ?? 'PROGRAMADO')
  /** Status criado na agenda (etiqueta), no lugar do status do sistema. */
  const [etiquetaId, setEtiquetaId] = useState<number | null>(agendamento?.etiquetaId ?? null)
  const [osId, setOsId] = useState<string>(
    agendamento?.material?.osId != null ? String(agendamento.material.osId) : '',
  )
  const [observacao, setObservacao] = useState(agendamento?.observacao ?? '')
  const [erro, setErro] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const [ordens, setOrdens] = useState<OrdemAberta[]>([])

  const ehNovo = !agendamento

  useEffect(() => {
    if (podeEditar) {
      api.get<OrdemAberta[]>('/ordens/abertas').then(setOrdens).catch(() => setOrdens([]))
    }
  }, [podeEditar])

  async function executar(acao: () => Promise<unknown>) {
    setErro(null)
    setOcupado(true)
    try {
      await acao()
      aoSalvar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha na operação.')
    } finally {
      setOcupado(false)
    }
  }

  function salvar() {
    const corpo = {
      data: ehNovo ? novaCelula!.data : agendamento!.data,
      adesivadorId: ehNovo ? novaCelula!.adesivadorId : agendamento!.adesivadorId,
      tipo,
      slotInicio,
      horasEstimadas: horas,
      descricao: descricao.trim(),
      vendedorCodigo: vendedor.trim() || null,
      status,
      etiquetaId,
      // O score não se lança aqui: ele é atribuído no relatório semanal, olhando o
      // serviço entregue.
      observacao: observacao.trim() || null,
    }
    const os = osId ? Number(osId) : null
    return executar(async () => {
      if (ehNovo) {
        await api.post<Agendamento>('/agenda', { ...corpo, osId: os })
      } else {
        await api.put(`/agenda/${agendamento!.id}`, { ...corpo, osId: os })
      }
    })
  }

  const material = agendamento?.material
  const horasDoDia = faixas.filter((f) => !f.almoco).reduce((s, f) => s + f.horas, 0)
  // A sexta vai ate as 17h: o servico pula 17:00-18:00 como pula o almoco.
  const dataDoCarro = ehNovo ? novaCelula!.data : agendamento!.data
  const ehSexta = new Date(`${dataDoCarro}T12:00:00`).getDay() === 5
  const bloqueio = bloqueioDesde(faixas, dataDoCarro)
  const cobertas = faixasCobertas(faixas, slotInicio, horas, bloqueio)
  const diasExtras = diasAlcancados(faixas, slotInicio, horas, bloqueio)

  return (
    <div className="modal-fundo" onClick={() => !ocupado && aoFechar()}>
      <div className="modal modal-largo" onClick={(e) => e.stopPropagation()}>
        <h2>{ehNovo ? 'Novo carro na agenda' : agendamento!.descricao}</h2>
        <p className="subtitulo">
          {ehNovo
            ? 'Preencha a faixa selecionada'
            : `${agendamento!.adesivador} · ${new Date(
                `${agendamento!.data}T12:00:00`,
              ).toLocaleDateString('pt-BR')} · faixa ${agendamento!.slotInicio}`}
        </p>

        {erro && <Aviso tipo="erro">{erro}</Aviso>}

        {material && (
          <div className={`painel-material ${material.pronto ? 'pronto' : 'producao'}`}>
            <div className="painel-material-topo">
              <strong>
                OS {material.numeroOsErp}
                {material.cliente ? ` · ${material.cliente}` : ''}
              </strong>
              <Link to={`/ordens/${material.osId}`} onClick={aoFechar}>
                abrir OS
              </Link>
            </div>
            {material.osCancelada && <div className="material-alerta">Esta OS está cancelada.</div>}
            {material.fluxos.length === 0 ? (
              <div className="material-alerta">A OS ainda não tem fluxos abertos.</div>
            ) : (
              <table className="tabela-material">
                <tbody>
                  {material.fluxos.map((fluxo) => (
                    <tr key={fluxo.fluxoId}>
                      <td>{fluxo.identificador}</td>
                      <td>
                        <ChipSetor setor={fluxo.setorAtual} />
                      </td>
                      <td>
                        <ChipStatus status={fluxo.status} />
                      </td>
                      <td>
                        <Link to={`/fluxos/${fluxo.fluxoId}`} onClick={aoFechar}>
                          linha do tempo
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}

        <div className="campo">
          <label>O que é esta faixa</label>
          <div className="seletor-status">
            <button
              type="button"
              disabled={!podeEditar}
              className={`chip chip-tipo ${tipo === 'SERVICO' ? 'selecionado' : ''}`}
              onClick={() => setTipo('SERVICO')}
            >
              Carro / serviço
            </button>
            <button
              type="button"
              disabled={!podeEditar}
              className={`chip chip-tipo chip-tipo-bloqueio ${
                tipo === 'INDISPONIVEL' ? 'selecionado' : ''
              }`}
              onClick={() => {
                setTipo('INDISPONIVEL')
                if (!MOTIVOS_BLOQUEIO.includes(descricao.toUpperCase())) setDescricao('FALTA')
              }}
            >
              Indisponível
            </button>
          </div>
        </div>

        {tipo === 'INDISPONIVEL' ? (
          <div className="campo">
            <label>Motivo</label>
            <div className="seletor-status">
              {MOTIVOS_BLOQUEIO.map((m) => (
                <button
                  key={m}
                  type="button"
                  disabled={!podeEditar}
                  className={`chip chip-setor ${
                    descricao.toUpperCase() === m ? 'selecionado' : ''
                  }`}
                  onClick={() => setDescricao(m)}
                >
                  {m}
                </button>
              ))}
            </div>
            <input
              style={{ marginTop: 8 }}
              value={descricao}
              onChange={(e) => setDescricao(e.target.value)}
              disabled={!podeEditar}
              placeholder="Ou escreva outro motivo"
            />
          </div>
        ) : (
          <div className="campo">
            <label>Carro / serviço</label>
            <input
              value={descricao}
              onChange={(e) => setDescricao(e.target.value)}
              disabled={!podeEditar}
              placeholder="Ex.: FASTBACK PPF FULL"
            />
          </div>
        )}

        <div className="campo" hidden={tipo === 'INDISPONIVEL'}>
          <label>Ordem de serviço</label>
          <select value={osId} onChange={(e) => setOsId(e.target.value)} disabled={!podeEditar}>
            <option value="">Sem OS vinculada</option>
            {ordens.map((o) => (
              <option key={o.id} value={o.id}>
                OS {o.numeroOsErp}
                {o.cliente ? ` — ${o.cliente}` : ''} ({o.fluxosAtivos} fluxo
                {o.fluxosAtivos > 1 ? 's' : ''})
              </option>
            ))}
            {/* A OS já vinculada pode ter sido encerrada e sair da lista de abertas. */}
            {material && !ordens.some((o) => o.id === material.osId) && (
              <option value={material.osId}>
                OS {material.numeroOsErp}
                {material.cliente ? ` — ${material.cliente}` : ''} (concluída)
              </option>
            )}
          </select>
        </div>

        <div className="campo" hidden={tipo === 'INDISPONIVEL'}>
          <label>Vendedor</label>
          <div className="seletor-status">
            <button
              type="button"
              disabled={!podeEditar}
              className={`chip ag-programado ${vendedor === '' ? 'selecionado' : ''}`}
              onClick={() => setVendedor('')}
            >
              Sem vendedor
            </button>
            {vendedores.filter((v) => v.ativo || v.codigo === vendedor.toUpperCase()).map((v) => (
              <button
                key={v.codigo}
                type="button"
                disabled={!podeEditar}
                className={`chip chip-setor ${
                  vendedor.toUpperCase() === v.codigo ? 'selecionado' : ''
                }`}
                onClick={() => setVendedor(v.codigo)}
              >
                {v.nome}
              </button>
            ))}
          </div>
        </div>

        <div className="campo" hidden={semHorarios}>
          <label>Começa às</label>
          <div className="seletor-status">
            {faixas
              .filter((f) => !f.almoco && !(ehSexta && f.indice === faixas.length))
              .map((f) => (
                <button
                  key={f.indice}
                  type="button"
                  disabled={!podeEditar}
                  className={`chip chip-horario ${slotInicio === f.indice ? 'selecionado' : ''}`}
                  onClick={() => setSlotInicio(f.indice)}
                >
                  {f.inicio}
                </button>
              ))}
          </div>
        </div>

        <div className="campo" hidden={semHorarios}>
          <label>Duração — até um dia</label>
          <div className="seletor-status">
            {HORAS.map((h) => (
              <button
                key={h}
                type="button"
                disabled={!podeEditar}
                className={`chip chip-numero ${horas === h ? 'selecionado' : ''}`}
                onClick={() => setHoras(h)}
              >
                {formatarHoras(h)}
              </button>
            ))}
          </div>

          <label style={{ marginTop: 12 }}>
            Duração — dias inteiros ({formatarHoras(horasDoDia)} cada)
          </label>
          <div className="seletor-status">
            {DIAS.map((d) => {
              const total = d * horasDoDia
              return (
                <button
                  key={d}
                  type="button"
                  disabled={!podeEditar}
                  className={`chip chip-numero ${horas === total ? 'selecionado' : ''}`}
                  onClick={() => setHoras(total)}
                >
                  {d} {d === 1 ? 'dia' : 'dias'}
                </button>
              )
            })}
            {!HORAS.includes(horas) && !DIAS.some((d) => d * horasDoDia === horas) && (
              <span className="chip chip-numero selecionado">{formatarHoras(horas)}</span>
            )}
          </div>

          {/* Preenche sozinho os horários que o serviço vai ocupar. */}
          <div className="previa-horarios">
            <span className="previa-rotulo">Vai ocupar</span>
            {cobertas.slice(0, 6).map((f, i) => (
              <span key={`${f.indice}-${i}`} className="faixa-previa">
                {f.rotulo}
              </span>
            ))}
            {cobertas.length > 6 && (
              <span className="faixa-previa">… e mais {cobertas.length - 6} faixas</span>
            )}
            {diasExtras > 0 && (
              <span className="faixa-previa transborda">
                passa para {diasExtras === 1 ? 'o dia seguinte' : `os ${diasExtras} dias seguintes`}
              </span>
            )}
          </div>
        </div>

        <div className="campo" hidden={semHorarios || tipo === 'INDISPONIVEL'}>
          <label>Status</label>
          <div className="seletor-status">
            {legenda.map((s) => {
              // Os do sistema pelo status; os criados na agenda pela etiqueta (o card fica Programado).
              const novoStatus: StatusAgendamento = s.chave ?? 'PROGRAMADO'
              const novaEtiqueta = s.chave ? null : s.id
              const escolhido = novaEtiqueta != null ? etiquetaId === novaEtiqueta : etiquetaId == null && status === s.chave
              return (
                <button
                  key={s.id}
                  type="button"
                  disabled={!podeMudarStatus || ocupado}
                  className={`chip ${classeStatus(novoStatus, novaEtiqueta)} ${escolhido ? 'selecionado' : ''}`}
                  onClick={() => {
                    setStatus(novoStatus)
                    setEtiquetaId(novaEtiqueta)
                    // Operador da Frota só mexe no andamento: aplica na hora.
                    if (!podeEditar && agendamento) {
                      executar(() =>
                        api.patch(`/agenda/${agendamento.id}/status`, { status: novoStatus, etiquetaId: novaEtiqueta }),
                      )
                    }
                  }}
                >
                  {s.nome}
                </button>
              )
            })}
          </div>
        </div>

        <div className="campo">
          <label>Observação</label>
          <textarea
            rows={2}
            value={observacao}
            onChange={(e) => setObservacao(e.target.value)}
            disabled={!podeEditar}
          />
        </div>

        <div className="rodape-modal">
          {!ehNovo && podeEditar && (
            <button
              className="botao botao-perigo"
              disabled={ocupado}
              onClick={() => executar(() => api.delete(`/agenda/${agendamento!.id}`))}
              style={{ marginRight: 'auto' }}
            >
              Excluir
            </button>
          )}
          <button className="botao botao-secundario" disabled={ocupado} onClick={aoFechar}>
            Fechar
          </button>
          {podeEditar && (
            <button className="botao" disabled={ocupado || !descricao.trim()} onClick={salvar}>
              {ocupado ? 'Salvando...' : 'Salvar'}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
