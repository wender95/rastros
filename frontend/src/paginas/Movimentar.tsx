import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { ItemMovimentacao, Movimentacao } from '../api/tipos'
import { Aviso, Carregando, formatarDuracao, rotuloSetor } from '../componentes/Ui'

/** Recarrega sozinha: uma OS despachada por outro setor aparece sem ninguém apertar nada. */
const ATUALIZAR_A_CADA_MS = 30_000


/**
 * A tela de quem trabalha num setor. De propósito, só isto: as OS que chegaram para
 * receber e as que já estão no setor, com três botões — Receber, Devolver e Despachar.
 * No Financeiro, no lugar do Despachar vem o Concluir: é o único setor que encerra a OS.
 * Nada para digitar: o número da OS vem na lista, e o destino é escolhido num botão.
 */
export default function Movimentar() {
  const [dados, setDados] = useState<Movimentacao | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)
  /** OS com a escolha de destino (ou a confirmação de devolver) aberta. */
  const [aberta, setAberta] = useState<{ id: number; acao: 'despachar' | 'devolver' | 'concluir' } | null>(null)
  const [ocupado, setOcupado] = useState<number | null>(null)

  const carregar = useCallback(() => {
    api
      .get<Movimentacao>('/movimentacao')
      .then((d) => {
        setDados(d)
        setErro(null)
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Não foi possível carregar.'))
  }, [])

  useEffect(() => {
    carregar()
    const id = window.setInterval(carregar, ATUALIZAR_A_CADA_MS)
    return () => window.clearInterval(id)
  }, [carregar])

  function executar(item: ItemMovimentacao, rota: string, corpo: unknown, mensagem: string) {
    setOcupado(item.fluxoId)
    setErro(null)
    setFeito(null)
    api
      .post(`/fluxos/${item.fluxoId}/${rota}`, corpo)
      .then(() => {
        setFeito(mensagem)
        setAberta(null)
        carregar()
      })
      .catch((e) => {
        setErro(e instanceof Error ? e.message : 'Não foi possível concluir.')
        carregar() // outra pessoa pode ter mexido na mesma OS
      })
      .finally(() => setOcupado(null))
  }

  if (!dados && !erro) return <Carregando texto="Carregando as OS do setor..." />

  return (
    <div className="movimentar">
      <div className="movimentar-topo">
        <h1>{dados ? rotuloSetor(dados.setor) : 'Meu setor'}</h1>
        <button className="botao botao-secundario botao-grande" onClick={carregar}>
          ↻ Atualizar
        </button>
      </div>

      {feito && <Aviso tipo="ok">{feito}</Aviso>}
      {erro && <Aviso tipo="erro">{erro}</Aviso>}

      {dados && (
        <>
          <h2 className="movimentar-secao">
            Chegaram para receber <span className="contador">{dados.paraReceber.length}</span>
          </h2>
          {dados.paraReceber.length === 0 ? (
            <p className="movimentar-vazio">Nada para receber agora.</p>
          ) : (
            <div className="movimentar-lista">
              {dados.paraReceber.map((item) => (
                <div className="os-cartao" key={item.fluxoId}>
                  <Identificacao item={item} />
                  <div className="os-botoes">
                    <button
                      className="botao botao-grande botao-receber"
                      disabled={ocupado === item.fluxoId}
                      onClick={() =>
                        executar(item, 'receber', {}, `OS ${item.numeroOsErp} recebida.`)
                      }
                    >
                      ✓ Receber
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          <h2 className="movimentar-secao">
            No setor <span className="contador">{dados.noSetor.length}</span>
          </h2>
          {dados.noSetor.length === 0 ? (
            <p className="movimentar-vazio">Nenhuma OS no setor.</p>
          ) : (
            <div className="movimentar-lista">
              {dados.noSetor.map((item) => {
                const escolhendo = aberta?.id === item.fluxoId ? aberta.acao : null
                return (
                  <div className={`os-cartao${escolhendo ? ' os-cartao-aberto' : ''}`} key={item.fluxoId}>
                    <Identificacao item={item} />

                    {escolhendo === 'despachar' ? (
                      <div className="os-escolha">
                        <p className="os-pergunta">Enviar a OS {item.numeroOsErp} para:</p>
                        <div className="os-destinos">
                          {item.destinos.map((d) => (
                            <button
                              key={d.setorId}
                              className="botao botao-grande"
                              disabled={ocupado === item.fluxoId}
                              onClick={() =>
                                executar(
                                  item,
                                  'despachar',
                                  { setorDestinoId: d.setorId },
                                  `OS ${item.numeroOsErp} enviada para ${rotuloSetor(d.setor)}.`,
                                )
                              }
                            >
                              {rotuloSetor(d.setor)}
                            </button>
                          ))}
                        </div>
                        <button className="botao botao-secundario" onClick={() => setAberta(null)}>
                          Voltar
                        </button>
                      </div>
                    ) : escolhendo === 'concluir' ? (
                      <div className="os-escolha">
                        <p className="os-pergunta">
                          Concluir a OS {item.numeroOsErp}? Isso encerra a OS e não tem volta.
                        </p>
                        <div className="os-destinos">
                          <button
                            className="botao botao-grande botao-receber"
                            disabled={ocupado === item.fluxoId}
                            onClick={() =>
                              executar(item, 'concluir', {}, `OS ${item.numeroOsErp} concluída.`)
                            }
                          >
                            Sim, concluir
                          </button>
                          <button className="botao botao-secundario botao-grande" onClick={() => setAberta(null)}>
                            Não
                          </button>
                        </div>
                      </div>
                    ) : escolhendo === 'devolver' && item.devolverPara ? (
                      <div className="os-escolha">
                        <p className="os-pergunta">
                          Devolver a OS {item.numeroOsErp} para {rotuloSetor(item.devolverPara)}?
                        </p>
                        <div className="os-destinos">
                          <button
                            className="botao botao-grande botao-devolver"
                            disabled={ocupado === item.fluxoId}
                            onClick={() =>
                              executar(
                                item,
                                'devolver',
                                {},
                                `OS ${item.numeroOsErp} devolvida para ${rotuloSetor(item.devolverPara!)}.`,
                              )
                            }
                          >
                            Sim, devolver
                          </button>
                          <button className="botao botao-secundario botao-grande" onClick={() => setAberta(null)}>
                            Não
                          </button>
                        </div>
                      </div>
                    ) : (
                      <div className="os-botoes">
                        {item.podeConcluir && (
                          <button
                            className="botao botao-grande botao-receber"
                            onClick={() => setAberta({ id: item.fluxoId, acao: 'concluir' })}
                          >
                            ✓ Concluir
                          </button>
                        )}
                        {item.destinos.length > 0 && (
                          <button
                            className="botao botao-grande"
                            onClick={() => setAberta({ id: item.fluxoId, acao: 'despachar' })}
                          >
                            Despachar →
                          </button>
                        )}
                        {item.devolverPara && (
                          <button
                            className="botao botao-secundario botao-grande"
                            onClick={() => setAberta({ id: item.fluxoId, acao: 'devolver' })}
                          >
                            ← Devolver para {rotuloSetor(item.devolverPara)}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </>
      )}
    </div>
  )
}

function Identificacao({ item }: { item: ItemMovimentacao }) {
  return (
    <div className="os-identificacao">
      <div className="os-numero">OS {item.numeroOsErp}</div>
      <div className="os-detalhe">
        {item.cliente ?? 'Cliente não informado'}
        {item.identificador !== 'Principal' && ` · ${item.identificador}`}
      </div>
      <div className="os-detalhe os-suave">
        {item.veioDe ? `Veio de ${rotuloSetor(item.veioDe)}` : 'Veio do comercial'}
        {' · '}
        {item.recebidoPor ? `recebida por ${item.recebidoPor} há ${formatarDuracao(item.segundosDesde)}` : `chegou há ${formatarDuracao(item.segundosDesde)}`}
      </div>
    </div>
  )
}
