import { useCallback, useEffect, useState } from 'react'
import { pode } from '../auth/acessos'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { Agendamento, Ordem, Setor } from '../api/tipos'
import { useAuth } from '../auth/AuthContext'
import {
  Aviso,
  Carregando,
  ChipSetor,
  ChipStatus,
  formatarDataHora,
  formatarDuracao,
  rotuloSetor,
  ChipStatusAgenda,
  useLegenda,
} from '../componentes/Ui'

export default function OrdemDetalhe() {
  useLegenda()
  const { id } = useParams<{ id: string }>()
  const navegar = useNavigate()
  const { usuario } = useAuth()
  const [ordem, setOrdem] = useState<Ordem | null>(null)
  const [agendamentos, setAgendamentos] = useState<Agendamento[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [erro, setErro] = useState<string | null>(null)
  const [modal, setModal] = useState<'fluxo' | 'cancelar' | null>(null)
  const [identificador, setIdentificador] = useState('')
  const [setorInicialId, setSetorInicialId] = useState('')
  const [motivo, setMotivo] = useState('')
  const [ocupado, setOcupado] = useState(false)
  const [erroModal, setErroModal] = useState<string | null>(null)

  const carregar = useCallback(() => {
    setErro(null)
    api
      .get<Ordem>(`/ordens/${id}`)
      .then(setOrdem)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar a OS.'))
    api
      .get<Agendamento[]>(`/agenda/por-os/${id}`)
      .then(setAgendamentos)
      .catch(() => setAgendamentos([]))
  }, [id])

  useEffect(carregar, [carregar])

  useEffect(() => {
    if (pode(usuario, 'criarOs')) {
      api.get<Setor[]>('/setores/iniciais').then(setSetores).catch(() => undefined)
    }
  }, [usuario])

  async function executar(acao: () => Promise<unknown>) {
    setErroModal(null)
    setOcupado(true)
    try {
      await acao()
      setModal(null)
      setIdentificador('')
      setMotivo('')
      carregar()
    } catch (e) {
      setErroModal(e instanceof Error ? e.message : 'Falha na operação.')
    } finally {
      setOcupado(false)
    }
  }

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!ordem) return <Carregando />

  // Abrir, acrescentar fluxo e cancelar: comercial, diretoria e administrador.
  const podeAdicionarFluxo = pode(usuario, 'criarOs') && !ordem.cancelada
  const podeCancelar = pode(usuario, 'criarOs') && !ordem.cancelada

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>OS {ordem.numeroOsErp}</h1>
          <p>
            {ordem.cliente ?? 'Cliente não informado'}
            {ordem.servico ? ` · ${ordem.servico}` : ''} · aberta por {ordem.criadoPor} em{' '}
            {formatarDataHora(ordem.criadoEm)} · {ordem.fluxos.length} fluxo(s)
          </p>
        </div>
        <div className="acoes-linha">
          {podeAdicionarFluxo && (
            <button
              className="botao botao-secundario"
              onClick={() => {
                setErroModal(null)
                setSetorInicialId(String(setores[0]?.id ?? ''))
                setModal('fluxo')
              }}
            >
              + Novo fluxo
            </button>
          )}
          {podeCancelar && (
            <button
              className="botao botao-perigo"
              onClick={() => {
                setErroModal(null)
                setModal('cancelar')
              }}
            >
              Cancelar OS inteira
            </button>
          )}
        </div>
      </div>

      {ordem.cancelada && (
        <Aviso tipo="erro">
          OS cancelada. Motivo: {ordem.motivoCancelamento ?? 'não informado'}
        </Aviso>
      )}

      <div className="cartao">
        <div className="titulo-secao">Fluxos da OS</div>
        <div className="tabela-rolagem">
          <table>
            <thead>
              <tr>
                <th>Fluxo</th>
                <th>Setor atual</th>
                <th>Status</th>
                <th>Recebido por</th>
                <th>No setor há</th>
              </tr>
            </thead>
            <tbody>
              {ordem.fluxos.map((fluxo) => (
                <tr key={fluxo.id} className="clicavel" onClick={() => navegar(`/fluxos/${fluxo.id}`)}>
                  <td>
                    <strong>{fluxo.identificadorFluxo}</strong>
                  </td>
                  <td>
                    <ChipSetor setor={fluxo.setorAtual} />
                  </td>
                  <td>
                    <ChipStatus status={fluxo.status} />
                  </td>
                  <td>{fluxo.recebidoPor ?? '—'}</td>
                  <td className="mono">
                    {fluxo.encerrado ? '—' : formatarDuracao(fluxo.segundosNoSetor)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {agendamentos.length > 0 && (
        <div className="cartao" style={{ marginTop: 18 }}>
          <div className="titulo-secao">Agenda dos adesivadores</div>
          <div className="tabela-rolagem">
            <table>
              <thead>
                <tr>
                  <th>Data</th>
                  <th>Adesivador</th>
                  <th>Carro / serviço</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {agendamentos.map((a) => (
                  <tr key={a.id}>
                    <td className="mono">
                      {new Date(`${a.data}T12:00:00`).toLocaleDateString('pt-BR')}
                    </td>
                    <td>{a.adesivador}</td>
                    <td>{a.descricao}</td>
                    <td>
                      <ChipStatusAgenda status={a.status} etiquetaId={a.etiquetaId} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {modal === 'fluxo' && (
        <div className="modal-fundo" onClick={() => !ocupado && setModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>Novo fluxo paralelo</h2>
            <p className="subtitulo">OS {ordem.numeroOsErp}</p>
            {erroModal && <Aviso tipo="erro">{erroModal}</Aviso>}
            <div className="campo">
              <label>Identificador</label>
              <input
                value={identificador}
                onChange={(e) => setIdentificador(e.target.value)}
                placeholder="Ex.: Adesivação frota"
              />
            </div>
            <div className="campo">
              <label>Setor inicial</label>
              <select value={setorInicialId} onChange={(e) => setSetorInicialId(e.target.value)}>
                <option value="">Selecione...</option>
                {setores.map((setor) => (
                  <option key={setor.id} value={setor.id}>
                    {rotuloSetor(setor.nome)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rodape-modal">
              <button className="botao botao-secundario" onClick={() => setModal(null)} disabled={ocupado}>
                Voltar
              </button>
              <button
                className="botao"
                disabled={ocupado || !setorInicialId}
                onClick={() =>
                  executar(() =>
                    api.post(`/ordens/${ordem.id}/fluxos`, {
                      identificador: identificador.trim() || ordem.servico?.slice(0, 50) || 'Principal',
                      setorInicialId: Number(setorInicialId),
                    }),
                  )
                }
              >
                Criar fluxo
              </button>
            </div>
          </div>
        </div>
      )}

      {modal === 'cancelar' && (
        <div className="modal-fundo" onClick={() => !ocupado && setModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>Cancelar OS {ordem.numeroOsErp}</h2>
            <p className="subtitulo">
              Todos os fluxos ativos serão cancelados. O histórico permanece intacto.
            </p>
            {erroModal && <Aviso tipo="erro">{erroModal}</Aviso>}
            <div className="campo">
              <label>Motivo do cancelamento</label>
              <textarea rows={3} value={motivo} onChange={(e) => setMotivo(e.target.value)} />
            </div>
            <div className="rodape-modal">
              <button className="botao botao-secundario" onClick={() => setModal(null)} disabled={ocupado}>
                Voltar
              </button>
              <button
                className="botao botao-perigo"
                disabled={ocupado || motivo.trim().length === 0}
                onClick={() =>
                  executar(() => api.post(`/ordens/${ordem.id}/cancelar`, { motivo: motivo.trim() }))
                }
              >
                Confirmar cancelamento
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
