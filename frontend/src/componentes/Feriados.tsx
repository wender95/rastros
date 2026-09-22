import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { Aviso, Carregando, Vazio } from './Ui'

interface Feriado {
  id: number
  data: string
  descricao: string
}

const DIA_SEMANA = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb']
const formatar = (iso: string) => {
  const d = new Date(`${iso}T12:00:00`)
  return `${DIA_SEMANA[d.getDay()]} ${d.toLocaleDateString('pt-BR')}`
}

/**
 * Dias em que a empresa não abre. O tempo das OS só conta o horário comercial (seg a sex,
 * 07:30–12:00 e 13:30–18:00), e um feriado cadastrado aqui também fica de fora.
 */
export default function Feriados() {
  const [feriados, setFeriados] = useState<Feriado[] | null>(null)
  const [data, setData] = useState('')
  const [descricao, setDescricao] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  const carregar = useCallback(() => {
    api
      .get<Feriado[]>('/admin/feriados')
      .then(setFeriados)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao listar os feriados.'))
  }, [])

  useEffect(carregar, [carregar])

  async function executar(acao: () => Promise<unknown>, mensagem: string) {
    setErro(null)
    setOk(null)
    setOcupado(true)
    try {
      await acao()
      setOk(mensagem)
      carregar()
      return true
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível completar a ação.')
      return false
    } finally {
      setOcupado(false)
    }
  }

  async function adicionar(e: React.FormEvent) {
    e.preventDefault()
    if (!data || !descricao.trim()) return
    const feito = await executar(
      () => api.post('/admin/feriados', { data, descricao }),
      `${descricao.trim()} (${formatar(data)}) cadastrado.`,
    )
    if (feito) {
      setData('')
      setDescricao('')
    }
  }

  const hoje = new Date().toISOString().slice(0, 10)
  const proximos = (feriados ?? []).filter((f) => f.data >= hoje)
  const passados = (feriados ?? []).filter((f) => f.data < hoje)

  return (
    <div className="cartao cartao-estreito" style={{ maxWidth: 640 }}>
      <p style={{ marginTop: 0 }}>
        O tempo das OS (espera, processamento e "parado há") conta só o <strong>horário comercial</strong>:
        segunda a sexta, das 07:30 às 12:00 e das 13:30 às 18:00. Os dias cadastrados aqui também não
        contam. Os feriados nacionais já vêm cadastrados; inclua os da cidade e do estado, e remova um
        ponto facultativo em que a empresa trabalhe.
      </p>
      {erro && <Aviso tipo="erro">{erro}</Aviso>}
      {ok && <Aviso tipo="ok">{ok}</Aviso>}

      <form className="form-feriado" onSubmit={adicionar}>
        <input type="date" value={data} onChange={(e) => setData(e.target.value)} aria-label="Data" required />
        <input
          placeholder="Nome (ex.: Aniversário da cidade)"
          maxLength={80}
          value={descricao}
          onChange={(e) => setDescricao(e.target.value)}
          aria-label="Nome do feriado"
          required
        />
        <button className="botao" disabled={ocupado || !data || !descricao.trim()}>
          + Adicionar
        </button>
      </form>

      {feriados === null ? (
        <Carregando texto="Carregando feriados..." />
      ) : feriados.length === 0 ? (
        <Vazio>Nenhum feriado cadastrado.</Vazio>
      ) : (
        [
          ['Próximos', proximos],
          ['Já passaram', passados],
        ].map(([titulo, lista]) =>
          (lista as Feriado[]).length === 0 ? null : (
            <div key={titulo as string} className="espaco-topo">
              <div className="titulo-secao">{titulo as string}</div>
              <table>
                <tbody>
                  {(lista as Feriado[]).map((f) => (
                    <tr key={f.id}>
                      <td className="mono">{formatar(f.data)}</td>
                      <td>{f.descricao}</td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          className="botao botao-secundario"
                          disabled={ocupado}
                          onClick={() => {
                            if (window.confirm(`Remover ${f.descricao} (${formatar(f.data)})? Esse dia volta a contar como útil.`))
                              executar(() => api.delete(`/admin/feriados/${f.id}`), `${f.descricao} removido.`)
                          }}
                        >
                          Remover
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ),
        )
      )}
    </div>
  )
}
