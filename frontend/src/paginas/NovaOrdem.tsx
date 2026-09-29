import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Ordem, Setor } from '../api/tipos'
import { Aviso, rotuloSetor } from '../componentes/Ui'

interface LinhaFluxo {
  identificador: string
  setorInicialId: string
}

const LINHA_VAZIA: LinhaFluxo = { identificador: '', setorInicialId: '' }

/** RF01 - o Vendedor abre a OS (número vindo do ERP) com um ou mais fluxos paralelos. */
export default function NovaOrdem() {
  const navegar = useNavigate()
  const [setores, setSetores] = useState<Setor[]>([])
  const [numeroOsErp, setNumeroOsErp] = useState('')
  const [cliente, setCliente] = useState('')
  const [servico, setServico] = useState('')
  const [linhas, setLinhas] = useState<LinhaFluxo[]>([{ ...LINHA_VAZIA }])
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  useEffect(() => {
    api
      .get<Setor[]>('/setores/iniciais')
      .then((lista) => {
        setSetores(lista)
        setLinhas((atual) =>
          atual.map((l) => (l.setorInicialId ? l : { ...l, setorInicialId: String(lista[0]?.id ?? '') })),
        )
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar setores.'))
  }, [])

  function atualizarLinha(indice: number, campo: keyof LinhaFluxo, valor: string) {
    setLinhas((atual) => atual.map((l, i) => (i === indice ? { ...l, [campo]: valor } : l)))
  }

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      const ordem = await api.post<Ordem>('/ordens', {
        numeroOsErp: numeroOsErp.trim(),
        cliente: cliente.trim() || null,
        servico: servico.trim() || null,
        fluxos: linhas.map((l) => ({
          // Sem nome, o fluxo se chama como o serviço da OS — que é como o pessoal a chama.
          identificador: l.identificador.trim() || servico.trim().slice(0, 50) || 'Principal',
          setorInicialId: Number(l.setorInicialId),
        })),
      })
      navegar(`/ordens/${ordem.id}`)
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha ao abrir a OS.')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>OS manual</h1>
          <p>
            O normal é importar a OS do ERP pela extensão do navegador. Use esta tela só quando a
            importação não for possível. O número da OS é o do ERP e cada fluxo caminha de forma
            independente.
          </p>
        </div>
      </div>

      {erro && <Aviso tipo="erro">{erro}</Aviso>}

      <form onSubmit={enviar} className="cartao" style={{ maxWidth: 720 }}>
        <div className="cartao-corpo">
          <div className="linha-campos">
            <div className="campo">
              <label htmlFor="numero">Número da OS (ERP)</label>
              <input
                id="numero"
                value={numeroOsErp}
                onChange={(e) => setNumeroOsErp(e.target.value)}
                placeholder="Ex.: 5050"
                required
              />
            </div>
            <div className="campo">
              <label htmlFor="cliente">Cliente (opcional)</label>
              <input
                id="cliente"
                value={cliente}
                onChange={(e) => setCliente(e.target.value)}
                placeholder="Ex.: Supermercado Estrela"
              />
            </div>
            <div className="campo">
              <label htmlFor="servico">Serviço (opcional)</label>
              <input
                id="servico"
                value={servico}
                onChange={(e) => setServico(e.target.value)}
                placeholder="Ex.: Adesivo de porta — 4 unidades"
              />
            </div>
          </div>

          <div className="titulo-secao" style={{ padding: '14px 0 10px', borderBottom: 'none' }}>
            Fluxos da OS
          </div>

          <div className="grupo-fluxos">
            {linhas.map((linha, indice) => (
              <div className="linha-campos" key={indice} style={{ alignItems: 'end' }}>
                <div className="campo" style={{ marginBottom: 0 }}>
                  <label>Identificador do fluxo</label>
                  <input
                    value={linha.identificador}
                    onChange={(e) => atualizarLinha(indice, 'identificador', e.target.value)}
                    placeholder={servico.trim() ? `Vazio: ${servico.trim()}` : 'Ex.: Lona impressa'}
                  />
                </div>
                <div className="campo" style={{ marginBottom: 0 }}>
                  <label>Setor inicial</label>
                  <select
                    value={linha.setorInicialId}
                    onChange={(e) => atualizarLinha(indice, 'setorInicialId', e.target.value)}
                    required
                  >
                    <option value="">Selecione...</option>
                    {setores.map((setor) => (
                      <option key={setor.id} value={setor.id}>
                        {rotuloSetor(setor.nome)}
                      </option>
                    ))}
                  </select>
                </div>
                <div style={{ paddingBottom: 1 }}>
                  <button
                    type="button"
                    className="botao botao-secundario"
                    disabled={linhas.length === 1}
                    onClick={() => setLinhas((atual) => atual.filter((_, i) => i !== indice))}
                  >
                    Remover
                  </button>
                </div>
              </div>
            ))}
          </div>

          <button
            type="button"
            className="botao botao-secundario"
            style={{ marginTop: 14 }}
            onClick={() =>
              setLinhas((atual) => [
                ...atual,
                { identificador: '', setorInicialId: String(setores[0]?.id ?? '') },
              ])
            }
          >
            + Adicionar fluxo paralelo
          </button>

          <div className="rodape-modal">
            <button className="botao botao-grande" disabled={enviando}>
              {enviando ? 'Lançando...' : 'Lançar OS manual'}
            </button>
          </div>
        </div>
      </form>
    </>
  )
}
