import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Fluxo, Setor, SetorNome } from '../api/tipos'
import { Aviso, Carregando, formatarDuracao, rotuloSetor } from '../componentes/Ui'

const ATUALIZAR_A_CADA_MS = 30_000
const LOCAIS: SetorNome[] = ['PRATELEIRA', 'PATIO']

interface OsEmEspera {
  fluxo: Fluxo
  /** Carros da agenda desta OS, ex.: "TAXI 888 SPIN COMPLETO". */
  servicos: string[]
}

/** Minúsculas e sem acento, para "joao" achar "João". */
const normalizar = (texto: string) =>
  texto
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()

/** Número da OS, cliente, serviço (da OS ou da agenda) ou vendedor que abriu. */
function combina(item: OsEmEspera, busca: string) {
  const termos = normalizar(busca).split(/\s+/).filter(Boolean)
  if (termos.length === 0) return true
  const { fluxo, servicos } = item
  const texto = normalizar(
    [fluxo.numeroOsErp, fluxo.cliente ?? '', fluxo.identificadorFluxo, fluxo.criadoPor, ...servicos].join(' '),
  )
  return termos.every((t) => texto.includes(t))
}

/**
 * Prateleira e Pátio são locais de espera, sem operador: a OS sai deles para o
 * Financeiro só pelas mãos do Comercial, da Diretoria ou do Administrador. Esta tela
 * mostra os dois lado a lado e manda para o Financeiro com um toque.
 */
export default function PatioPrateleira() {
  const [itens, setItens] = useState<OsEmEspera[] | null>(null)
  const [financeiroId, setFinanceiroId] = useState<number | null>(null)
  const [busca, setBusca] = useState('')
  const [confirmando, setConfirmando] = useState<number | null>(null)
  const [ocupado, setOcupado] = useState<number | null>(null)
  const [erro, setErro] = useState<string | null>(null)
  const [feito, setFeito] = useState<string | null>(null)

  const carregar = useCallback(() => {
    api
      .get<OsEmEspera[]>('/fluxos/em-espera')
      .then((lista) => {
        setItens(lista)
        setErro(null)
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Não foi possível carregar.'))
  }, [])

  useEffect(() => {
    api
      .get<Setor[]>('/setores')
      .then((s) => setFinanceiroId(s.find((x) => x.nome === 'FINANCEIRO')?.id ?? null))
      .catch(() => undefined)
    carregar()
    const id = window.setInterval(carregar, ATUALIZAR_A_CADA_MS)
    return () => window.clearInterval(id)
  }, [carregar])

  function enviar(fluxo: Fluxo) {
    if (financeiroId == null) return
    setOcupado(fluxo.id)
    setErro(null)
    setFeito(null)
    api
      .post(`/fluxos/${fluxo.id}/despachar`, { setorDestinoId: financeiroId })
      .then(() => {
        setFeito(`OS ${fluxo.numeroOsErp} enviada para o Financeiro.`)
        setConfirmando(null)
        carregar()
      })
      .catch((e) => {
        setErro(e instanceof Error ? e.message : 'Não foi possível enviar.')
        carregar()
      })
      .finally(() => setOcupado(null))
  }

  if (!itens && !erro) return <Carregando texto="Carregando pátio e prateleira..." />

  const filtrados = (itens ?? []).filter((i) => combina(i, busca))

  return (
    <div className="patio-prateleira">
      <div className="movimentar-topo">
        <h1>Pátio e prateleira</h1>
        <button className="botao botao-secundario botao-grande" onClick={carregar}>
          ↻ Atualizar
        </button>
      </div>
      <p className="patio-dica">Só o Comercial e a Diretoria liberam a OS daqui para o Financeiro.</p>

      <div className="patio-busca">
        <input
          type="search"
          placeholder="Buscar por nº da OS, cliente, serviço ou vendedor"
          value={busca}
          onChange={(e) => setBusca(e.target.value)}
          aria-label="Buscar OS"
        />
        {busca && (
          <button className="botao botao-secundario" onClick={() => setBusca('')}>
            Limpar
          </button>
        )}
      </div>

      {feito && <Aviso tipo="ok">{feito}</Aviso>}
      {erro && <Aviso tipo="erro">{erro}</Aviso>}

      {itens && (
        <div className="patio-colunas">
          {LOCAIS.map((local) => {
            const total = itens.filter((i) => i.fluxo.setorAtual === local).length
            const aqui = filtrados
              .filter((i) => i.fluxo.setorAtual === local)
              .sort((a, b) => b.fluxo.segundosNoSetor - a.fluxo.segundosNoSetor)
            return (
              <section key={local} className="patio-coluna">
                <h2 className="movimentar-secao">
                  {rotuloSetor(local)}{' '}
                  <span className="contador">{busca ? `${aqui.length} de ${total}` : total}</span>
                </h2>
                {aqui.length === 0 ? (
                  <p className="movimentar-vazio">
                    {busca && total > 0 ? 'Nenhuma OS com essa busca.' : 'Nenhuma OS esperando.'}
                  </p>
                ) : (
                  <div className="movimentar-lista">
                    {aqui.map(({ fluxo: f, servicos }) => (
                      <div className={`os-cartao${confirmando === f.id ? ' os-cartao-aberto' : ''}`} key={f.id}>
                        <div className="os-identificacao">
                          <div className="os-numero">OS {f.numeroOsErp}</div>
                          <div className="os-detalhe">
                            {f.cliente ?? 'Cliente não informado'}
                            {f.identificadorFluxo !== 'Principal' && ` · ${f.identificadorFluxo}`}
                          </div>
                          {servicos.length > 0 && <div className="os-servico">{servicos.join(' · ')}</div>}
                          <div className="os-detalhe os-suave">
                            Aberta por {f.criadoPor} · esperando há {formatarDuracao(f.segundosNoSetor)}
                          </div>
                        </div>
                        {confirmando === f.id ? (
                          <div className="os-escolha">
                            <p className="os-pergunta">Enviar a OS {f.numeroOsErp} para o Financeiro?</p>
                            <div className="os-destinos">
                              <button
                                className="botao botao-grande botao-receber"
                                disabled={ocupado === f.id}
                                onClick={() => enviar(f)}
                              >
                                Sim, enviar
                              </button>
                              <button
                                className="botao botao-secundario botao-grande"
                                onClick={() => setConfirmando(null)}
                              >
                                Não
                              </button>
                            </div>
                          </div>
                        ) : (
                          <div className="os-botoes">
                            <button
                              className="botao botao-grande"
                              disabled={financeiroId == null}
                              onClick={() => setConfirmando(f.id)}
                            >
                              Enviar ao Financeiro →
                            </button>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </section>
            )
          })}
        </div>
      )}
    </div>
  )
}
