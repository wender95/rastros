import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Adesivador } from '../api/tipos'
import { Aviso, Carregando } from './Ui'

interface Remocao {
  resultado: 'APAGADO' | 'RETIRADO_DA_AGENDA'
  mensagem: string
}

/**
 * Modo edição dos adesivadores da agenda (Diretoria e Administrador): adicionar,
 * renomear, mudar a ordem, remover e restaurar. Cada ação vai direto para o servidor;
 * a agenda recarrega ao fechar.
 */
export default function EditarAdesivadores({ aoFechar }: { aoFechar: () => void }) {
  const [colunas, setColunas] = useState<Adesivador[] | null>(null)
  const [novo, setNovo] = useState('')
  const [editando, setEditando] = useState<{ id: number; nome: string } | null>(null)
  const [ocupado, setOcupado] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)

  const carregar = () =>
    api
      .get<Adesivador[]>('/agenda/colunas')
      .then(setColunas)
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar os adesivadores.'))

  useEffect(() => {
    carregar()
  }, [])

  const executar = async (acao: () => Promise<unknown>, mensagem?: (r: unknown) => string) => {
    setOcupado(true)
    setErro(null)
    setOk(null)
    try {
      const r = await acao()
      if (mensagem) setOk(mensagem(r))
      await carregar()
      return true
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível completar a ação.')
      return false
    } finally {
      setOcupado(false)
    }
  }

  if (!colunas) {
    return (
      <div className="modal-fundo">
        <div className="modal">{erro ? <Aviso tipo="erro">{erro}</Aviso> : <Carregando />}</div>
      </div>
    )
  }

  const ativas = colunas.filter((c) => c.ativo)
  const removidas = colunas.filter((c) => !c.ativo)

  const mover = (indice: number, passo: -1 | 1) => {
    const ids = ativas.map((c) => c.id)
    const destino = indice + passo
    ;[ids[indice], ids[destino]] = [ids[destino], ids[indice]]
    // Mostra a nova ordem na hora; o servidor confirma em seguida.
    setColunas([...ids.map((id) => ativas.find((c) => c.id === id)!), ...removidas])
    executar(() => api.put('/agenda/colunas/ordem', ids))
  }

  const adicionar = async () => {
    const nome = novo.trim()
    if (!nome) return
    if (await executar(() => api.post<Adesivador>('/agenda/colunas', { nome }), () => `${nome.toUpperCase()} entrou na agenda.`))
      setNovo('')
  }

  const salvarNome = async () => {
    if (!editando || !editando.nome.trim()) return
    const { id, nome } = editando
    if (await executar(() => api.put(`/agenda/colunas/${id}`, { nome }))) setEditando(null)
  }

  const remover = (coluna: Adesivador) => {
    if (!window.confirm(`Remover ${coluna.nome} da agenda?\n\nAs semanas em que já trabalhou continuam no histórico.`))
      return
    executar(
      () => api.delete<Remocao>(`/agenda/colunas/${coluna.id}`),
      (r) => (r as Remocao).mensagem,
    )
  }

  return (
    <div className="modal-fundo" onClick={() => !ocupado && aoFechar()}>
      <div className="modal modal-largo editar-adesivadores" onClick={(e) => e.stopPropagation()}>
        <h2>Editar adesivadores</h2>
        <p className="subtitulo">
          A ordem aqui é a ordem das colunas na agenda, da esquerda para a direita.
        </p>

        {erro && <Aviso tipo="erro">{erro}</Aviso>}
        {ok && <Aviso tipo="ok">{ok}</Aviso>}

        <ul className="lista-adesivadores">
          {ativas.map((coluna, i) => (
            <li key={coluna.id}>
              <span className="ordem-adesivador">{i + 1}</span>
              {editando?.id === coluna.id ? (
                <>
                  <input
                    autoFocus
                    maxLength={60}
                    value={editando.nome}
                    onChange={(e) => setEditando({ id: coluna.id, nome: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') salvarNome()
                      if (e.key === 'Escape') setEditando(null)
                    }}
                  />
                  <button className="botao" disabled={ocupado || !editando.nome.trim()} onClick={salvarNome}>
                    Salvar
                  </button>
                  <button className="botao botao-secundario" disabled={ocupado} onClick={() => setEditando(null)}>
                    Cancelar
                  </button>
                </>
              ) : (
                <>
                  <span className="nome-adesivador">
                    {coluna.nome}
                    {coluna.tipo !== 'ADESIVADOR' && <small> · coluna especial</small>}
                  </span>
                  <button
                    className="botao botao-secundario botao-icone"
                    title="Mover para a esquerda"
                    disabled={ocupado || i === 0}
                    onClick={() => mover(i, -1)}
                  >
                    ↑
                  </button>
                  <button
                    className="botao botao-secundario botao-icone"
                    title="Mover para a direita"
                    disabled={ocupado || i === ativas.length - 1}
                    onClick={() => mover(i, 1)}
                  >
                    ↓
                  </button>
                  <button
                    className="botao botao-secundario"
                    disabled={ocupado}
                    onClick={() => setEditando({ id: coluna.id, nome: coluna.nome })}
                  >
                    Renomear
                  </button>
                  <button className="botao botao-perigo" disabled={ocupado} onClick={() => remover(coluna)}>
                    Remover
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>

        <div className="adicionar-adesivador">
          <input
            placeholder="Nome do novo adesivador"
            maxLength={60}
            value={novo}
            onChange={(e) => setNovo(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && adicionar()}
          />
          <button className="botao" disabled={ocupado || !novo.trim()} onClick={adicionar}>
            + Adicionar
          </button>
        </div>

        {removidas.length > 0 && (
          <>
            <h3 className="titulo-removidos">Removidos da agenda</h3>
            <p className="subtitulo">Continuam nas semanas em que trabalharam, no relatório e na produtividade.</p>
            <ul className="lista-adesivadores removidos">
              {removidas.map((coluna) => (
                <li key={coluna.id}>
                  <span className="nome-adesivador">{coluna.nome}</span>
                  <button
                    className="botao botao-secundario"
                    disabled={ocupado}
                    onClick={() =>
                      executar(
                        () => api.post(`/agenda/colunas/${coluna.id}/restaurar`),
                        () => `${coluna.nome} voltou para a agenda.`,
                      )
                    }
                  >
                    Restaurar
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}

        <div className="rodape-modal">
          <button className="botao" disabled={ocupado} onClick={aoFechar}>
            Concluir
          </button>
        </div>
      </div>
    </div>
  )
}
