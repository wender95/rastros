import { useState } from 'react'
import { api } from '../api/client'
import type { DestinoPermitido, Fluxo, FluxoDetalhe } from '../api/tipos'
import { Aviso, rotuloSetor } from './Ui'

interface Props {
  fluxo: Fluxo
  /** Destinos já conhecidos (tela de detalhe). Quando ausente, são buscados ao abrir o modal. */
  destinos?: DestinoPermitido[]
  podeReceber: boolean
  podeDespachar: boolean
  podeCancelar: boolean
  aoConcluir: () => void
}

export default function AcoesFluxo({
  fluxo,
  destinos,
  podeReceber,
  podeDespachar,
  podeCancelar,
  aoConcluir,
}: Props) {
  const [modal, setModal] = useState<'despacho' | 'cancelamento' | null>(null)
  const [opcoes, setOpcoes] = useState<DestinoPermitido[]>(destinos ?? [])
  const [destinoId, setDestinoId] = useState<string>('')
  const [observacao, setObservacao] = useState('')
  const [motivo, setMotivo] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  async function executar(acao: () => Promise<unknown>) {
    setErro(null)
    setOcupado(true)
    try {
      await acao()
      setModal(null)
      setObservacao('')
      setMotivo('')
      setDestinoId('')
      aoConcluir()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha na operação.')
    } finally {
      setOcupado(false)
    }
  }

  async function abrirDespacho() {
    setErro(null)
    setModal('despacho')
    if (opcoes.length === 0) {
      try {
        const detalhe = await api.get<FluxoDetalhe>(`/fluxos/${fluxo.id}`)
        setOpcoes(detalhe.acoes.destinos)
      } catch (e) {
        setErro(e instanceof Error ? e.message : 'Falha ao carregar destinos.')
      }
    }
  }

  return (
    <>
      <div className="acoes-linha">
        {podeReceber && (
          <button
            className="botao"
            disabled={ocupado}
            onClick={() => executar(() => api.post(`/fluxos/${fluxo.id}/receber`, {}))}
          >
            Receber
          </button>
        )}
        {podeDespachar && (
          <button className="botao" disabled={ocupado} onClick={abrirDespacho}>
            Despachar
          </button>
        )}
        {podeCancelar && (
          <button
            className="botao botao-secundario"
            disabled={ocupado}
            onClick={() => {
              setErro(null)
              setModal('cancelamento')
            }}
          >
            Cancelar OS
          </button>
        )}
      </div>

      {modal === 'despacho' && (
        <div className="modal-fundo" onClick={() => !ocupado && setModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>Despachar OS {fluxo.numeroOsErp}</h2>
            <p className="subtitulo">
              {fluxo.identificadorFluxo} · saindo de {rotuloSetor(fluxo.setorAtual)}
            </p>

            {erro && <Aviso tipo="erro">{erro}</Aviso>}

            <div className="campo">
              <label htmlFor="destino">Setor de destino</label>
              <select
                id="destino"
                value={destinoId}
                onChange={(e) => setDestinoId(e.target.value)}
              >
                <option value="">Selecione...</option>
                {opcoes.map((d) => (
                  <option key={d.setorId} value={d.setorId}>
                    {rotuloSetor(d.setor)}
                    {d.retorno ? ' — retorno ao setor de origem' : ''}
                  </option>
                ))}
              </select>
            </div>

            <div className="campo">
              <label htmlFor="obs">Observação (opcional)</label>
              <textarea
                id="obs"
                rows={3}
                value={observacao}
                onChange={(e) => setObservacao(e.target.value)}
              />
            </div>

            <div className="rodape-modal">
              <button
                className="botao botao-secundario"
                disabled={ocupado}
                onClick={() => setModal(null)}
              >
                Voltar
              </button>
              <button
                className="botao"
                disabled={ocupado || !destinoId}
                onClick={() =>
                  executar(() =>
                    api.post(`/fluxos/${fluxo.id}/despachar`, {
                      setorDestinoId: Number(destinoId),
                      observacao: observacao || null,
                    }),
                  )
                }
              >
                {ocupado ? 'Despachando...' : 'Confirmar despacho'}
              </button>
            </div>
          </div>
        </div>
      )}

      {modal === 'cancelamento' && (
        <div className="modal-fundo" onClick={() => !ocupado && setModal(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h2>Cancelar fluxo</h2>
            <p className="subtitulo">
              OS {fluxo.numeroOsErp} · {fluxo.identificadorFluxo}. O histórico de eventos é preservado.
            </p>

            {erro && <Aviso tipo="erro">{erro}</Aviso>}

            <div className="campo">
              <label htmlFor="motivo">Motivo do cancelamento</label>
              <textarea
                id="motivo"
                rows={3}
                value={motivo}
                onChange={(e) => setMotivo(e.target.value)}
                placeholder="Justificativa obrigatória"
              />
            </div>

            <div className="rodape-modal">
              <button
                className="botao botao-secundario"
                disabled={ocupado}
                onClick={() => setModal(null)}
              >
                Voltar
              </button>
              <button
                className="botao botao-perigo"
                disabled={ocupado || motivo.trim().length === 0}
                onClick={() =>
                  executar(() => api.post(`/fluxos/${fluxo.id}/cancelar`, { motivo: motivo.trim() }))
                }
              >
                {ocupado ? 'Cancelando...' : 'Confirmar cancelamento'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
