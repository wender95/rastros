import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PerfilNome, Setor, SetorNome, Transicao, Usuario } from '../api/tipos'
import { rotuloPerfil } from '../auth/acessos'
import { useAuth } from '../auth/AuthContext'
import Backups from '../componentes/Backups'
import Feriados from '../componentes/Feriados'
import { Aviso, Carregando, Vazio, rotuloSetor } from '../componentes/Ui'

const PERFIS: PerfilNome[] = ['OPERACIONAL', 'VENDEDOR', 'FINANCEIRO', 'DIRETORIA', 'ADMIN']

interface FormUsuario {
  id: number | null
  nome: string
  login: string
  email: string
  senha: string
  perfil: PerfilNome
  setorId: string
  ativo: boolean
}

const FORM_VAZIO: FormUsuario = {
  id: null,
  nome: '',
  login: '',
  email: '',
  senha: '',
  perfil: 'OPERACIONAL',
  setorId: '',
  ativo: true,
}

/**
 * RF07 - gestão de usuários, setores e matriz de transição.
 *
 * O Financeiro entra aqui só para cadastrar usuários e redefinir senhas (`completo`
 * falso): sem matriz, sem backups e sem mexer em conta de administrador.
 */
export default function Admin({ completo = true }: { completo?: boolean }) {
  const { usuario: eu } = useAuth()
  const [aba, setAba] = useState<'usuarios' | 'matriz' | 'feriados' | 'backups'>('usuarios')
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [transicoes, setTransicoes] = useState<Transicao[]>([])
  const [carregando, setCarregando] = useState(true)
  const [erro, setErro] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [form, setForm] = useState<FormUsuario>(FORM_VAZIO)
  const [novaOrigem, setNovaOrigem] = useState<string>('')
  const [novoDestino, setNovoDestino] = useState<string>('')

  const carregar = useCallback(() => {
    setCarregando(true)
    Promise.all([
      api.get<Usuario[]>('/admin/usuarios'),
      api.get<Setor[]>('/setores'),
      completo ? api.get<Transicao[]>('/admin/transicoes') : Promise.resolve([] as Transicao[]),
    ])
      .then(([u, s, t]) => {
        setUsuarios(u)
        setSetores(s)
        setTransicoes(t)
      })
      .catch((e) => setErro(e instanceof Error ? e.message : 'Falha ao carregar dados.'))
      .finally(() => setCarregando(false))
  }, [completo])

  useEffect(carregar, [carregar])

  async function salvarUsuario(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setOk(null)
    const corpo = {
      nome: form.nome,
      login: form.login.trim().toLowerCase(),
      email: form.email.trim() || null,
      senha: form.senha || null,
      perfil: form.perfil,
      setorId: form.perfil === 'OPERACIONAL' ? Number(form.setorId) || null : null,
      ativo: form.ativo,
    }
    try {
      if (form.id) await api.put(`/admin/usuarios/${form.id}`, corpo)
      else await api.post('/admin/usuarios', corpo)
      setOk(form.id ? 'Usuário atualizado.' : 'Usuário criado.')
      setForm(FORM_VAZIO)
      carregar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha ao salvar usuário.')
    }
  }

  /** Exclui de vez. O servidor recusa quem tem histórico — esse se desativa. */
  async function excluirUsuario(u: Usuario) {
    if (
      !window.confirm(
        `Excluir ${u.nome} (${u.login}) de vez?\n\nIsso não tem volta. Quem já movimentou OS ou mexeu na agenda não pode ser excluído — nesse caso, desative.`,
      )
    )
      return
    setErro(null)
    setOk(null)
    try {
      const r = await api.delete<{ mensagem: string }>(`/admin/usuarios/${u.id}`)
      setOk(r.mensagem)
      if (form.id === u.id) setForm(FORM_VAZIO)
      carregar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha ao excluir usuário.')
    }
  }

  async function acaoMatriz(acao: () => Promise<unknown>) {
    setErro(null)
    setOk(null)
    try {
      await acao()
      setOk('Matriz de transição atualizada.')
      carregar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha ao atualizar a matriz.')
    }
  }

  if (carregando) return <Carregando />

  const porOrigem = new Map<string, Transicao[]>()
  transicoes.forEach((t) => {
    const chave = t.setorOrigem ?? 'VENDAS'
    porOrigem.set(chave, [...(porOrigem.get(chave) ?? []), t])
  })

  return (
    <>
      <div className="cabecalho-pagina">
        <div>
          <h1>{completo ? 'Administração' : 'Usuários'}</h1>
          <p>
            {completo
              ? 'Usuários, setores, parametrização da matriz de transição, feriados e backups.'
              : 'Cadastro de usuários e redefinição de senha.'}
          </p>
        </div>
      </div>

      {completo && (
      <div className="linha-abas">
        <button className={aba === 'usuarios' ? 'ativo' : ''} onClick={() => setAba('usuarios')}>
          Usuários
        </button>
        <button className={aba === 'matriz' ? 'ativo' : ''} onClick={() => setAba('matriz')}>
          Matriz de transição
        </button>
        <button className={aba === 'feriados' ? 'ativo' : ''} onClick={() => setAba('feriados')}>
          Feriados
        </button>
        <button className={aba === 'backups' ? 'ativo' : ''} onClick={() => setAba('backups')}>
          Backups
        </button>
      </div>
      )}

      {erro && <Aviso tipo="erro">{erro}</Aviso>}
      {ok && <Aviso tipo="ok">{ok}</Aviso>}

      {aba === 'backups' ? (
        <Backups />
      ) : aba === 'feriados' ? (
        <Feriados />
      ) : aba === 'usuarios' ? (
        <div className="duas-colunas">
          <div className="cartao">
            <div className="titulo-secao">Usuários cadastrados</div>
            <div className="tabela-rolagem">
              <table>
                <thead>
                  <tr>
                    <th>Nome</th>
                    <th>Perfil</th>
                    <th>Setor</th>
                    <th>Situação</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {usuarios.map((u) => (
                    <tr key={u.id}>
                      <td>
                        <strong>{u.nome}</strong>
                        <div style={{ color: '#64748b', fontSize: 13 }}>
                          {u.login}
                          {u.email ? ` · ${u.email}` : ''}
                        </div>
                      </td>
                      <td>{rotuloPerfil(u.perfil)}</td>
                      <td>{u.setor ? rotuloSetor(u.setor) : '—'}</td>
                      <td>
                        <span className={`chip ${u.ativo ? 'chip-encerrada' : 'chip-cancelada'}`}>
                          {u.ativo ? 'Ativo' : 'Inativo'}
                        </span>
                      </td>
                      <td>
                        {(completo || u.perfil !== 'ADMIN') && (
                        <button
                          className="botao botao-secundario"
                          onClick={() =>
                            setForm({
                              id: u.id,
                              nome: u.nome,
                              login: u.login,
                              email: u.email ?? '',
                              senha: '',
                              perfil: u.perfil,
                              setorId: u.setorId ? String(u.setorId) : '',
                              ativo: u.ativo,
                            })
                          }
                        >
                          Editar
                        </button>
                        )}
                        {(completo || u.perfil !== 'ADMIN') && u.id !== eu?.id && (
                          <button
                            className="botao botao-perigo"
                            style={{ marginLeft: 6 }}
                            onClick={() => excluirUsuario(u)}
                          >
                            Excluir
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="cartao">
            <div className="titulo-secao">{form.id ? 'Editar usuário' : 'Novo usuário'}</div>
            <form className="cartao-corpo" onSubmit={salvarUsuario}>
              <div className="campo">
                <label>Nome</label>
                <input value={form.nome} onChange={(e) => setForm({ ...form, nome: e.target.value })} required />
              </div>
              <div className="campo">
                <label>Usuário (para entrar no sistema)</label>
                <input
                  value={form.login}
                  onChange={(e) => setForm({ ...form, login: e.target.value.toLowerCase().replace(/\s/g, '') })}
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  placeholder="ex.: joao.silva"
                  required
                />
                <small className="dica-campo">Sem espaço nem acento. Pode ter ponto, hífen e sublinhado.</small>
              </div>
              <div className="campo">
                <label>E-mail (opcional)</label>
                <input
                  type="email"
                  value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                />
              </div>
              <div className="campo">
                <label>
                  {form.id ? 'Nova senha provisória (deixe vazio para manter a atual)' : 'Senha provisória'}
                </label>
                <input
                  type="password"
                  value={form.senha}
                  onChange={(e) => setForm({ ...form, senha: e.target.value })}
                  required={!form.id}
                />
                <small className="dica-campo">A pessoa escolhe a própria senha no primeiro acesso.</small>
              </div>
              <div className="campo">
                <label>Perfil</label>
                <select
                  value={form.perfil}
                  onChange={(e) => setForm({ ...form, perfil: e.target.value as PerfilNome })}
                >
                  {PERFIS.filter((p) => completo || p !== 'ADMIN').map((p) => (
                    <option key={p} value={p}>
                      {rotuloPerfil(p)}
                    </option>
                  ))}
                </select>
              </div>
              {form.perfil === 'OPERACIONAL' && (
                <div className="campo">
                  <label>Setor</label>
                  <select
                    value={form.setorId}
                    onChange={(e) => setForm({ ...form, setorId: e.target.value })}
                    required
                  >
                    <option value="">Selecione...</option>
                    {/* Prateleira e Pátio são lugares, e o Financeiro é perfil: nenhum tem operador. */}
                    {setores
                      .filter((s) => !s.localFisico && s.nome !== 'FINANCEIRO')
                      .map((s) => (
                        <option key={s.id} value={s.id}>
                          {rotuloSetor(s.nome)}
                        </option>
                      ))}
                  </select>
                </div>
              )}
              <div className="campo">
                <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input
                    type="checkbox"
                    style={{ width: 'auto' }}
                    checked={form.ativo}
                    onChange={(e) => setForm({ ...form, ativo: e.target.checked })}
                  />
                  Usuário ativo
                </label>
              </div>
              <div className="acoes-linha">
                <button className="botao">{form.id ? 'Salvar alterações' : 'Criar usuário'}</button>
                {form.id && (
                  <button
                    type="button"
                    className="botao botao-secundario"
                    onClick={() => setForm(FORM_VAZIO)}
                  >
                    Cancelar edição
                  </button>
                )}
              </div>
            </form>
          </div>
        </div>
      ) : (
        <div className="duas-colunas">
          <div className="cartao">
            <div className="titulo-secao">Destinos permitidos por origem</div>
            {transicoes.length === 0 ? (
              <Vazio>Nenhuma transição cadastrada.</Vazio>
            ) : (
              <div className="tabela-rolagem">
                <table>
                  <thead>
                    <tr>
                      <th>Origem</th>
                      <th>Destinos</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...porOrigem.entries()].map(([origem, lista]) => (
                      <tr key={origem}>
                        <td>
                          <strong>
                            {origem === 'VENDAS' ? 'Vendas (entrada)' : rotuloSetor(origem as SetorNome)}
                          </strong>
                        </td>
                        <td>
                          <div className="acoes-linha">
                            {lista.map((t) => (
                              <span key={t.id} className="chip chip-setor">
                                {rotuloSetor(t.setorDestino)}
                                <button
                                  title="Remover"
                                  onClick={() =>
                                    acaoMatriz(() => api.delete(`/admin/transicoes/${t.id}`))
                                  }
                                  style={{
                                    border: 'none',
                                    background: 'none',
                                    cursor: 'pointer',
                                    color: '#b91c1c',
                                    fontWeight: 700,
                                    padding: 0,
                                  }}
                                >
                                  ×
                                </button>
                              </span>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="cartao">
            <div className="titulo-secao">Nova transição</div>
            <div className="cartao-corpo">
              <div className="campo">
                <label>Setor de origem</label>
                <select value={novaOrigem} onChange={(e) => setNovaOrigem(e.target.value)}>
                  <option value="">Vendas (entrada)</option>
                  {setores.map((s) => (
                    <option key={s.id} value={s.nome}>
                      {rotuloSetor(s.nome)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="campo">
                <label>Setor de destino</label>
                <select value={novoDestino} onChange={(e) => setNovoDestino(e.target.value)}>
                  <option value="">Selecione...</option>
                  {setores.map((s) => (
                    <option key={s.id} value={s.nome}>
                      {rotuloSetor(s.nome)}
                    </option>
                  ))}
                </select>
              </div>
              <button
                className="botao"
                disabled={!novoDestino}
                onClick={() =>
                  acaoMatriz(async () => {
                    await api.post('/admin/transicoes', {
                      setorOrigem: novaOrigem || null,
                      setorDestino: novoDestino,
                    })
                    setNovoDestino('')
                  })
                }
              >
                Adicionar transição
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
