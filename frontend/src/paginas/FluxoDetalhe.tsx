import { useCallback, useEffect, useState } from 'react'
import { rotuloPerfil } from '../auth/acessos'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { FluxoDetalhe as Detalhe } from '../api/tipos'
import AcoesFluxo from '../componentes/AcoesFluxo'
import {
  Aviso,
  Carregando,
  ChipSetor,
  ChipStatus,
  formatarDataHora,
  formatarDuracao,
  rotuloEvento,
  rotuloSetor,
} from '../componentes/Ui'

export default function FluxoDetalhe() {
  const { id } = useParams<{ id: string }>()
  const [detalhe, setDetalhe] = useState<Detalhe | null>(null)
  const [erro, setErro] = useState<string | null>(null)

  const carregar = useCallback(() => {
    setErro(null)
    api
      .get<Detalhe>(`/fluxos/${id}`)
      .then(setDetalhe)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar o fluxo.'))
  }, [id])

  useEffect(carregar, [carregar])

  if (erro) return <Aviso tipo="erro">{erro}</Aviso>
  if (!detalhe) return <Carregando />

  const { fluxo, eventos, acoes } = detalhe

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>
            OS {fluxo.numeroOsErp} · {fluxo.identificadorFluxo}
          </h1>
          <p>
            {fluxo.cliente ?? 'Cliente não informado'} · aberta por {fluxo.criadoPor} em{' '}
            {formatarDataHora(fluxo.criadoEm)} ·{' '}
            <Link to={`/ordens/${fluxo.osId}`} style={{ color: '#1d4ed8', fontWeight: 600 }}>
              ver todos os fluxos da OS
            </Link>
          </p>
        </div>
        <AcoesFluxo
          fluxo={fluxo}
          destinos={acoes.destinos}
          podeReceber={acoes.podeReceber}
          podeDespachar={acoes.podeDespachar}
          podeCancelar={acoes.podeCancelar}
          aoConcluir={carregar}
        />
      </div>

      {fluxo.status === 'CANCELADA' && (
        <Aviso tipo="erro">
          Fluxo cancelado. Motivo: {fluxo.motivoCancelamento ?? 'não informado'}
        </Aviso>
      )}
      {fluxo.status === 'ENCERRADA' && (
        <Aviso tipo="ok">
          Fluxo concluído pelo Financeiro em {formatarDataHora(fluxo.encerradoEm ?? fluxo.entrouNoSetorEm)}.
        </Aviso>
      )}

      <div className="duas-colunas">
        <div className="cartao">
          <div className="titulo-secao">Linha do tempo</div>
          <ul className="timeline">
            {eventos.map((evento) => (
              <li key={evento.id} className={`evento-${evento.tipoEvento}`}>
                <div className="linha-topo">
                  <strong>{rotuloEvento(evento.tipoEvento)}</strong>
                  <span className="quando">{formatarDataHora(evento.dataHora)}</span>
                </div>
                <div className="detalhe">
                  {evento.setorOrigem && evento.setorOrigem !== evento.setorDestino
                    ? `${rotuloSetor(evento.setorOrigem)} → ${rotuloSetor(evento.setorDestino)}`
                    : rotuloSetor(evento.setorDestino)}{' '}
                  · {evento.usuario} ({rotuloPerfil(evento.perfilUsuario).toLowerCase()})
                </div>
                {evento.observacao && <div className="observacao">{evento.observacao}</div>}
              </li>
            ))}
          </ul>
        </div>

        <div className="cartao">
          <div className="titulo-secao">Situação atual</div>
          <div className="cartao-corpo">
            <dl style={{ margin: 0, display: 'grid', gap: 14 }}>
              <div>
                <label>Status</label>
                <ChipStatus status={fluxo.status} />
              </div>
              <div>
                <label>Setor atual</label>
                <ChipSetor setor={fluxo.setorAtual} />
              </div>
              <div>
                <label>Setor anterior</label>
                {fluxo.setorAnterior ? (
                  <ChipSetor setor={fluxo.setorAnterior} />
                ) : (
                  <span style={{ color: '#64748b' }}>Entrada pelo comercial</span>
                )}
              </div>
              <div>
                <label>Recebido por</label>
                <div>
                  {fluxo.recebidoPor
                    ? `${fluxo.recebidoPor} · ${formatarDataHora(fluxo.recebidoEm!)}`
                    : 'Ainda não recebido neste setor'}
                </div>
              </div>
              <div>
                <label>Tempo no setor atual</label>
                <div className="mono">{formatarDuracao(fluxo.segundosNoSetor)}</div>
              </div>
            </dl>

            {!acoes.podeReceber && !acoes.podeDespachar && !acoes.podeCancelar && !fluxo.encerrado && (
              <div style={{ marginTop: 16 }}>
                <Aviso tipo="info">
                  Seu perfil não executa ações neste fluxo enquanto ele estiver em{' '}
                  {rotuloSetor(fluxo.setorAtual)}.
                </Aviso>
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  )
}
