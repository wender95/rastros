import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { useMudancas } from '../api/mudancas'
import {
  ChipProjeto,
  Execucao,
  fraseDoResultado,
  horaNoDia,
  situacaoDaOs,
  textoDasPausas,
  type AgendaDoDia,
  type ProjetoDoDia,
  type ResultadoProjeto,
} from '../agenda/projetos'
import { Aviso, Carregando, rotuloSetor, rotuloVendedor, useLegenda, useVendedores } from '../componentes/Ui'

const ATUALIZAR_A_CADA_MS = 30_000

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

/**
 * A agenda do adesivador, no celular: só os projetos dele, dia a dia. Ele **inicia** e
 * **conclui** projetos — não recebe nem despacha OS. A OS acompanha sozinha: é recebida na
 * Frota quando o projeto começa (ou quando chega, se ele já começou) e vai para o Pátio
 * quando os projetos dela terminam.
 */
export default function MinhaAgenda() {
  useVendedores() // os nomes chegam do servidor; o hook re-renderiza quando chegam
  useLegenda() // idem para os nomes e as cores dos status
  const [dia, setDia] = useState(hojeUtil)
  const [dados, setDados] = useState<AgendaDoDia | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState<number | null>(null)
  /** Projeto com a confirmação de concluir ou de devolver aberta. */
  const [confirmando, setConfirmando] = useState<{ id: number; acao: 'concluir' | 'devolver' } | null>(null)

  const carregar = useCallback(() => {
    api
      .get<AgendaDoDia>(`/minha-agenda?data=${dia}`)
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
  // Projeto novo ou mudado no escritório aparece na hora.
  useMudancas(carregar)

  function executar(projeto: ProjetoDoDia, chamada: () => Promise<string>) {
    setOcupado(projeto.agendamentoId)
    setErro(null)
    setFeito(null)
    chamada()
      .then((mensagem) => {
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

  const iniciar = (p: ProjetoDoDia) =>
    executar(p, () =>
      api.post<ResultadoProjeto>(`/minha-agenda/${p.agendamentoId}/iniciar`, {}).then(fraseDoResultado),
    )

  const concluir = (p: ProjetoDoDia) =>
    executar(p, () =>
      api.post<ResultadoProjeto>(`/minha-agenda/${p.agendamentoId}/concluir`, {}).then(fraseDoResultado),
    )

  const pausar = (p: ProjetoDoDia) =>
    executar(p, () =>
      api.post(`/minha-agenda/${p.agendamentoId}/pausar`, {}).then(() => `"${p.descricao}" pausado. Toque em Retomar para voltar.`),
    )

  const retomar = (p: ProjetoDoDia) =>
    executar(p, () => api.post(`/minha-agenda/${p.agendamentoId}/retomar`, {}).then(() => `"${p.descricao}" retomado.`))

  const devolver = (p: ProjetoDoDia) =>
    executar(p, () =>
      api
        .post(`/fluxos/${p.os!.fluxoId}/devolver`, {})
        .then(() => `OS ${p.os!.numeroOsErp} devolvida para ${rotuloSetor(p.os!.devolverPara!)}.`),
    )

  const hoje = hojeUtil()
  const d = comoData(dia)

  return (
    <div className="movimentar minha-agenda">
      <div className="movimentar-topo">
        <h1>Minha agenda</h1>
        <button className="botao botao-secundario botao-grande" onClick={carregar} aria-label="Atualizar">
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
        <p className="movimentar-vazio">Nenhum projeto na sua agenda neste dia.</p>
      ) : (
        <div className="movimentar-lista">
          {dados?.carros.map((c) => {
            const aberto = confirmando?.id === c.agendamentoId
            return (
              <div
                key={c.agendamentoId}
                className={[
                  'os-cartao carro-do-dia',
                  c.tipo === 'INDISPONIVEL' && 'carro-bloqueio',
                  c.atual && 'projeto-atual',
                  aberto && 'os-cartao-aberto',
                ]
                  .filter(Boolean)
                  .join(' ')}
              >
                <div className="os-identificacao">
                  {c.atual && <div className="projeto-atual-selo">▶ Você está neste projeto</div>}
                  {/* Sem relógio da agenda: o que aparece é quando o projeto começou e terminou de verdade. */}
                  {c.tipo !== 'INDISPONIVEL' && (
                    <div className="carro-horario">
                      <ChipProjeto status={c.status} etiquetaId={c.etiquetaId} />
                      <Execucao projeto={c} dia={dia} />
                    </div>
                  )}
                  {c.pausadoDesde && (
                    <div className="projeto-pausado" title={textoDasPausas(c.pausas ?? [], dia)}>
                      ⏸ Pausado desde {horaNoDia(c.pausadoDesde, dia)}
                    </div>
                  )}
                  <div className="os-numero">{c.descricao}</div>
                  {(c.comecaEm !== dia || c.terminaEm !== dia) && (
                    <div className="os-detalhe os-suave">
                      {c.comecaEm !== dia && `agendado para ${curto(c.comecaEm)}`}
                      {c.comecaEm !== dia && c.terminaEm !== dia && ' · '}
                      {c.terminaEm !== dia && `segue até ${curto(c.terminaEm)}`}
                    </div>
                  )}
                  {c.os ? (
                    <div className="os-detalhe">
                      OS {c.os.numeroOsErp}
                      {c.os.cliente && ` · ${c.os.cliente}`}
                      {c.os.servico && <div className="os-descricao">{c.os.servico}</div>}
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

                {!aberto && (c.podeIniciar || c.podeConcluir || c.podePausar || c.podeRetomar || c.os?.devolverPara) && (
                  <div className="os-botoes">
                    {c.podeIniciar && (
                      <button
                        className="botao botao-grande botao-receber"
                        disabled={ocupado === c.agendamentoId}
                        onClick={() => iniciar(c)}
                      >
                        ▶ Iniciar projeto
                      </button>
                    )}
                    {c.podeRetomar && (
                      <button
                        className="botao botao-grande botao-receber"
                        disabled={ocupado === c.agendamentoId}
                        onClick={() => retomar(c)}
                      >
                        ▶ Retomar projeto
                      </button>
                    )}
                    {c.podePausar && (
                      <button
                        className="botao botao-secundario botao-grande"
                        disabled={ocupado === c.agendamentoId}
                        onClick={() => pausar(c)}
                      >
                        ⏸ Pausar
                      </button>
                    )}
                    {c.podeConcluir && (
                      <button
                        className="botao botao-grande"
                        onClick={() => setConfirmando({ id: c.agendamentoId, acao: 'concluir' })}
                      >
                        ✓ Concluir projeto
                      </button>
                    )}
                    {c.os?.devolverPara && (
                      <button
                        className="botao botao-secundario botao-grande botao-devolver"
                        onClick={() => setConfirmando({ id: c.agendamentoId, acao: 'devolver' })}
                      >
                        ↩ Devolver OS para {rotuloSetor(c.os.devolverPara)}
                      </button>
                    )}
                  </div>
                )}

                {aberto && (
                  <div className="os-escolha">
                    <p className="os-pergunta">
                      {confirmando.acao === 'concluir'
                        ? c.os
                          ? `"${c.descricao}" terminou? A OS ${c.os.numeroOsErp} vai para o Pátio quando todos os projetos dela estiverem concluídos.`
                          : `"${c.descricao}" terminou?`
                        : `Devolver a OS ${c.os!.numeroOsErp} para ${rotuloSetor(c.os!.devolverPara!)}? O projeto volta a aguardar.`}
                    </p>
                    <div className="os-destinos">
                      <button
                        className={`botao botao-grande ${confirmando.acao === 'concluir' ? 'botao-receber' : 'botao-devolver'}`}
                        disabled={ocupado === c.agendamentoId}
                        onClick={() => (confirmando.acao === 'concluir' ? concluir(c) : devolver(c))}
                      >
                        Sim, {confirmando.acao === 'concluir' ? 'concluir' : 'devolver'}
                      </button>
                      <button className="botao botao-secundario botao-grande" onClick={() => setConfirmando(null)}>
                        Não
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
