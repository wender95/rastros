import { useState } from 'react'
import { api } from '../api/client'
import {
  Aviso,
  ChipStatusAgenda,
  carregarLegenda,
  carregarVendedores,
  useLegenda,
  useVendedores,
  type StatusDaLegenda,
  type Vendedor,
} from './Ui'

interface Remocao {
  resultado: 'APAGADO' | 'RETIRADO_DA_AGENDA'
  mensagem: string
}

/** Cores prontas para um status novo: uma que ainda não está na legenda. */
const CORES_SUGERIDAS = ['#0ea5e9', '#f59e0b', '#ec4899', '#14b8a6', '#8b5cf6', '#ef4444', '#84cc16', '#64748b']

/** O vaivém de cada ação: trava os botões, mostra o erro ou o ok, e busca a lista de novo. */
function useAcao(recarregar: () => Promise<unknown>) {
  const [ocupado, setOcupado] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)

  const executar = async (acao: () => Promise<unknown>, mensagem?: (r: unknown) => string) => {
    setOcupado(true)
    setErro(null)
    setOk(null)
    try {
      const r = await acao()
      if (mensagem) setOk(mensagem(r))
      await recarregar()
      return true
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível completar a ação.')
      return false
    } finally {
      setOcupado(false)
    }
  }
  return { ocupado, erro, ok, executar }
}

/**
 * Modo edição dos status da agenda (Diretoria e Administrador). Os do sistema mudam de nome
 * e cor; os criados aqui também saem. A ordem é a da legenda e dos menus.
 */
export function EditarStatus({ aoFechar }: { aoFechar: () => void }) {
  const legenda = useLegenda()
  const { ocupado, erro, ok, executar } = useAcao(() => carregarLegenda(true))
  const [editando, setEditando] = useState<{ id: number; nome: string; cor: string } | null>(null)
  const livre = CORES_SUGERIDAS.find((c) => !legenda.some((s) => s.cor === c)) ?? CORES_SUGERIDAS[0]
  const [novo, setNovo] = useState({ nome: '', cor: livre })
  const carregada = legenda.every((s) => s.id > 0)

  const mover = (indice: number, passo: -1 | 1) => {
    const ids = legenda.map((s) => s.id)
    const destino = indice + passo
    ;[ids[indice], ids[destino]] = [ids[destino], ids[indice]]
    executar(() => api.put('/status-agenda/ordem', ids))
  }

  const adicionar = async () => {
    const nome = novo.nome.trim()
    if (!nome) return
    if (await executar(() => api.post('/status-agenda', { nome, cor: novo.cor }), () => `"${nome}" entrou na legenda.`)) {
      setNovo({ nome: '', cor: CORES_SUGERIDAS.find((c) => c !== novo.cor && !legenda.some((s) => s.cor === c)) ?? novo.cor })
    }
  }

  const salvar = async () => {
    if (!editando || !editando.nome.trim()) return
    const { id, nome, cor } = editando
    if (await executar(() => api.put(`/status-agenda/${id}`, { nome, cor }))) setEditando(null)
  }

  const remover = (s: StatusDaLegenda) => {
    if (!window.confirm(`Remover o status "${s.nome}"?\n\nOs cards que estão nele voltam para Programado.`)) return
    executar(
      () => api.delete<Remocao>(`/status-agenda/${s.id}`),
      (r) => (r as Remocao).mensagem,
    )
  }

  return (
    <div className="modal-fundo" onClick={() => !ocupado && aoFechar()}>
      <div className="modal modal-largo editar-adesivadores" onClick={(e) => e.stopPropagation()}>
        <h2>Editar status</h2>
        <p className="subtitulo">
          A ordem aqui é a da legenda e do menu do card. Os status do sistema (executando, concluído…) têm regra por
          trás: mudam de nome e cor, mas não saem. Os criados aqui são para serviço que ainda não começou.
        </p>

        {erro && <Aviso tipo="erro">{erro}</Aviso>}
        {ok && <Aviso tipo="ok">{ok}</Aviso>}

        <ul className="lista-adesivadores">
          {legenda.map((s, i) => (
            <li key={s.id}>
              <span className="ordem-adesivador">{i + 1}</span>
              {editando?.id === s.id ? (
                <>
                  <input
                    type="color"
                    className="cor-status"
                    value={editando.cor}
                    onChange={(e) => setEditando({ ...editando, cor: e.target.value })}
                    title="Cor do status"
                  />
                  <input
                    autoFocus
                    maxLength={40}
                    value={editando.nome}
                    onChange={(e) => setEditando({ ...editando, nome: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') salvar()
                      if (e.key === 'Escape') setEditando(null)
                    }}
                  />
                  <button className="botao" disabled={ocupado || !editando.nome.trim()} onClick={salvar}>
                    Salvar
                  </button>
                  <button className="botao botao-secundario" disabled={ocupado} onClick={() => setEditando(null)}>
                    Cancelar
                  </button>
                </>
              ) : (
                <>
                  <span className="nome-adesivador">
                    <ChipStatusAgenda status={s.chave ?? 'PROGRAMADO'} etiquetaId={s.chave ? null : s.id} />
                    {s.doSistema && <small> · do sistema</small>}
                  </span>
                  <button
                    className="botao botao-secundario botao-icone"
                    title="Subir na legenda"
                    disabled={ocupado || !carregada || i === 0}
                    onClick={() => mover(i, -1)}
                  >
                    ↑
                  </button>
                  <button
                    className="botao botao-secundario botao-icone"
                    title="Descer na legenda"
                    disabled={ocupado || !carregada || i === legenda.length - 1}
                    onClick={() => mover(i, 1)}
                  >
                    ↓
                  </button>
                  <button
                    className="botao botao-secundario"
                    disabled={ocupado || !carregada}
                    onClick={() => setEditando({ id: s.id, nome: s.nome, cor: s.cor })}
                  >
                    Editar
                  </button>
                  {s.doSistema ? (
                    <span className="sem-remover" title="Status do sistema: dá para mudar o nome e a cor, mas não remover">
                      fixo
                    </span>
                  ) : (
                    <button className="botao botao-perigo" disabled={ocupado} onClick={() => remover(s)}>
                      Remover
                    </button>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>

        <div className="adicionar-adesivador">
          <input
            type="color"
            className="cor-status"
            value={novo.cor}
            onChange={(e) => setNovo({ ...novo, cor: e.target.value })}
            title="Cor do novo status"
          />
          <input
            placeholder="Nome do novo status (ex.: Aguardando material)"
            maxLength={40}
            value={novo.nome}
            onChange={(e) => setNovo({ ...novo, nome: e.target.value })}
            onKeyDown={(e) => e.key === 'Enter' && adicionar()}
          />
          <button className="botao" disabled={ocupado || !novo.nome.trim()} onClick={adicionar}>
            + Adicionar
          </button>
        </div>

        <div className="rodape-modal">
          <button className="botao" disabled={ocupado} onClick={aoFechar}>
            Concluir
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Modo edição dos vendedores da agenda (Diretoria e Administrador). O código é a letra que
 * fica no card (a do cronograma); renomear muda só o nome exibido.
 */
export function EditarVendedores({ aoFechar }: { aoFechar: () => void }) {
  const vendedores = useVendedores()
  const { ocupado, erro, ok, executar } = useAcao(() => carregarVendedores(true))
  const [editando, setEditando] = useState<{ id: number; nome: string } | null>(null)
  const [novo, setNovo] = useState({ nome: '', codigo: '' })

  const ativos = vendedores.filter((v) => v.ativo)
  const removidos = vendedores.filter((v) => !v.ativo)

  const adicionar = async () => {
    const nome = novo.nome.trim()
    if (!nome) return
    const codigo = novo.codigo.trim() || null
    if (
      await executar(
        () => api.post<Vendedor>('/vendedores', { nome, codigo }),
        (r) => `${(r as Vendedor).nome} entrou na lista, com o código ${(r as Vendedor).codigo}.`,
      )
    )
      setNovo({ nome: '', codigo: '' })
  }

  const salvar = async () => {
    if (!editando || !editando.nome.trim()) return
    const { id, nome } = editando
    if (await executar(() => api.put(`/vendedores/${id}`, { nome }))) setEditando(null)
  }

  const remover = (v: Vendedor) => {
    if (!window.confirm(`Remover ${v.nome}?\n\nOs cards que já têm este vendedor continuam com o nome dele.`)) return
    executar(
      () => api.delete<Remocao>(`/vendedores/${v.id}`),
      (r) => (r as Remocao).mensagem,
    )
  }

  return (
    <div className="modal-fundo" onClick={() => !ocupado && aoFechar()}>
      <div className="modal modal-largo editar-adesivadores" onClick={(e) => e.stopPropagation()}>
        <h2>Editar vendedores</h2>
        <p className="subtitulo">
          O código é a letra que aparece no card, como no cronograma. Sem código, vale a primeira letra do nome.
        </p>

        {erro && <Aviso tipo="erro">{erro}</Aviso>}
        {ok && <Aviso tipo="ok">{ok}</Aviso>}

        {ativos.length === 0 && <p className="subtitulo">Nenhum vendedor ainda: adicione abaixo.</p>}
        <ul className="lista-adesivadores">
          {ativos.map((v) => (
            <li key={v.id}>
              <span className="codigo-vendedor" title="Código gravado nos cards">
                {v.codigo}
              </span>
              {editando?.id === v.id ? (
                <>
                  <input
                    autoFocus
                    maxLength={60}
                    value={editando.nome}
                    onChange={(e) => setEditando({ id: v.id, nome: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') salvar()
                      if (e.key === 'Escape') setEditando(null)
                    }}
                  />
                  <button className="botao" disabled={ocupado || !editando.nome.trim()} onClick={salvar}>
                    Salvar
                  </button>
                  <button className="botao botao-secundario" disabled={ocupado} onClick={() => setEditando(null)}>
                    Cancelar
                  </button>
                </>
              ) : (
                <>
                  <span className="nome-adesivador">{v.nome}</span>
                  <button
                    className="botao botao-secundario"
                    disabled={ocupado}
                    onClick={() => setEditando({ id: v.id, nome: v.nome })}
                  >
                    Renomear
                  </button>
                  <button className="botao botao-perigo" disabled={ocupado} onClick={() => remover(v)}>
                    Remover
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>

        <div className="adicionar-adesivador">
          <input
            placeholder="Nome do vendedor"
            maxLength={60}
            value={novo.nome}
            onChange={(e) => setNovo({ ...novo, nome: e.target.value })}
            onKeyDown={(e) => e.key === 'Enter' && adicionar()}
          />
          <input
            className="campo-codigo"
            placeholder="Código"
            title="Opcional: sem código, vale a primeira letra do nome"
            maxLength={5}
            value={novo.codigo}
            onChange={(e) => setNovo({ ...novo, codigo: e.target.value.toUpperCase() })}
            onKeyDown={(e) => e.key === 'Enter' && adicionar()}
          />
          <button className="botao" disabled={ocupado || !novo.nome.trim()} onClick={adicionar}>
            + Adicionar
          </button>
        </div>

        {removidos.length > 0 && (
          <>
            <h3 className="titulo-removidos">Removidos</h3>
            <p className="subtitulo">Não aparecem para escolher, mas os cards antigos continuam com o nome.</p>
            <ul className="lista-adesivadores removidos">
              {removidos.map((v) => (
                <li key={v.id}>
                  <span className="codigo-vendedor">{v.codigo}</span>
                  <span className="nome-adesivador">{v.nome}</span>
                  <button
                    className="botao botao-secundario"
                    disabled={ocupado}
                    onClick={() =>
                      executar(
                        () => api.post(`/vendedores/${v.id}/restaurar`),
                        () => `${v.nome} voltou para a lista.`,
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
