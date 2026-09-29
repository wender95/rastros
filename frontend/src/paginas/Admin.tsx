import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { PerfilNome, SecaoDoSistema, Setor, SetorNome, Transicao, Usuario } from '../api/tipos'
import { SECOES, rotuloPerfil } from '../auth/acessos'
import { useAuth } from '../auth/AuthContext'
import Backups from '../componentes/Backups'
import Feriados from '../componentes/Feriados'
import { Aviso, Carregando, Vazio, rotuloSetor } from '../componentes/Ui'

const PERFIS: PerfilNome[] = ['OPERACIONAL', 'VENDEDOR', 'FINANCEIRO', 'DIRETORIA', 'ADMIN', 'PERSONALIZADO']
/** O Financeiro cadastra e redefine senhas só destes (o resto é com o Administrador). */
const GERENCIAVEIS_PELO_FINANCEIRO: PerfilNome[] = ['OPERACIONAL', 'VENDEDOR']

type Padroes = Partial<Record<PerfilNome, SecaoDoSistema[]>>

/** Quem mexe na agenda (ou na legenda dela) também a vê — como o servidor completa. */
function marcar(secoes: SecaoDoSistema[], secao: SecaoDoSistema, ligada: boolean): SecaoDoSistema[] {
  let nova = ligada ? [...new Set([...secoes, secao])] : secoes.filter((s) => s !== secao)
  if (ligada && (secao === 'AGENDA_EDITAR' || secao === 'AGENDA_LEGENDA')) nova = [...new Set([...nova, 'AGENDA_VER' as const])]
  if (!ligada && secao === 'AGENDA_VER') nova = nova.filter((s) => s !== 'AGENDA_EDITAR' && s !== 'AGENDA_LEGENDA')
  return nova
}

/** Trocou o perfil: vem o padrão do novo, com os mesmos acréscimos e retiradas de antes. */
function trocarPerfil(secoes: SecaoDoSistema[], antes: SecaoDoSistema[], depois: SecaoDoSistema[]): SecaoDoSistema[] {
  const aMais = secoes.filter((s) => !antes.includes(s))
  const tiradas = antes.filter((s) => !secoes.includes(s))
  return [...new Set([...depois, ...aMais])].filter((s) => !tiradas.includes(s))
}

interface FormUsuario {
  id: number | null
  nome: string
  login: string
  email: string
  senha: string
  perfil: PerfilNome
  setorId: string
  /** Setores a mais, além do principal (ex.: Recorte e também Frota). */
  outrosSetoresIds: number[]
  ativo: boolean
  /** As seções que a pessoa vai acessar (o padrão do perfil, com os ajustes). */
  secoes: SecaoDoSistema[]
}

const FORM_VAZIO: FormUsuario = {
  id: null,
  nome: '',
  login: '',
  email: '',
  senha: '',
  perfil: 'OPERACIONAL',
  setorId: '',
  outrosSetoresIds: [],
  ativo: true,
  secoes: [],
}

/**
 * RF07 - gestão de usuários, setores e matriz de transição.
 *
 * O Financeiro entra aqui só para cadastrar usuários e redefinir senhas (`completo`
 * falso): sem matriz, sem backups e sem mexer em conta de administrador.
 */
export default function Admin({ completo = true }: { completo?: boolean }) {
  const { usuario: eu } = useAuth()
  const [aba, setAba] = useState<'usuarios' | 'permissoes' | 'matriz' | 'feriados' | 'backups'>('usuarios')
  const [usuarios, setUsuarios] = useState<Usuario[]>([])
  const [setores, setSetores] = useState<Setor[]>([])
  const [transicoes, setTransicoes] = useState<Transicao[]>([])
  const [carregando, setCarregando] = useState(true)
  const [erro, setErro] = useState<string | null>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [form, setForm] = useState<FormUsuario>(FORM_VAZIO)
  const [novaOrigem, setNovaOrigem] = useState<string>('')
  const [novoDestino, setNovoDestino] = useState<string>('')
  const [padroes, setPadroes] = useState<Padroes>({})
  const padraoDe = (perfil: PerfilNome) => padroes[perfil] ?? []
  const formVazio = (): FormUsuario => ({ ...FORM_VAZIO, secoes: padraoDe(FORM_VAZIO.perfil) })

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
    api
      .get<Padroes>('/admin/usuarios/secoes-padrao')
      .then((pad) => {
        setPadroes(pad)
        // O formulário novo já nasce com as seções do perfil escolhido.
        setForm((f) => (f.id ? f : { ...f, secoes: pad[f.perfil] ?? [] }))
      })
      .catch(() => undefined)
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
      outrosSetoresIds:
        form.perfil === 'OPERACIONAL' ? form.outrosSetoresIds.filter((id) => id !== Number(form.setorId)) : [],
      ativo: form.ativo,
      ...(completo ? { secoes: form.secoes } : {}),
    }
    try {
      if (form.id) await api.put(`/admin/usuarios/${form.id}`, corpo)
      else await api.post('/admin/usuarios', corpo)
      setOk(form.id ? 'Usuário atualizado.' : 'Usuário criado.')
      setForm(formVazio())
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
      if (form.id === u.id) setForm(formVazio())
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
              : 'Cadastro e redefinição de senha de usuários Operacionais e do Comercial. As demais contas ficam com o Administrador.'}
          </p>
        </div>
      </div>

      {completo && (
      <div className="linha-abas">
        <button className={aba === 'usuarios' ? 'ativo' : ''} onClick={() => setAba('usuarios')}>
          Usuários
        </button>
        <button className={aba === 'permissoes' ? 'ativo' : ''} onClick={() => setAba('permissoes')}>
          Permissões
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

      {aba === 'permissoes' ? (
        <PermissoesDosPerfis
          padroes={padroes}
          aoMudar={(novos) => {
            setPadroes(novos)
            // As seções de cada pessoa acompanham o perfil: a lista se refaz sem piscar a tela.
            api.get<Usuario[]>('/admin/usuarios').then(setUsuarios).catch(() => undefined)
          }}
        />
      ) : aba === 'backups' ? (
        <Backups />
      ) : aba === 'feriados' ? (
        <Feriados />
      ) : aba === 'usuarios' ? (
        <div className="duas-colunas">
          <div className="cartao">
            <div className="titulo-secao">Usuários cadastrados</div>
            <div className="tabela-rolagem">
              <table className="tabela-cartoes">
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
                      <td className="celula-titulo">
                        <strong>{u.nome}</strong>
                        <div style={{ color: '#64748b', fontSize: 13 }}>
                          {u.login}
                          {u.email ? ` · ${u.email}` : ''}
                        </div>
                      </td>
                      <td data-rotulo="Perfil">
                        {rotuloPerfil(u.perfil)}
                        <ResumoDeSecoes usuario={u} />
                      </td>
                      <td data-rotulo="Setor">
                        {u.setores?.length ? u.setores.map((s) => rotuloSetor(s.nome)).join(' / ') : u.setor ? rotuloSetor(u.setor) : '—'}
                      </td>
                      <td data-rotulo="Situação">
                        <span className={`chip ${u.ativo ? 'chip-encerrada' : 'chip-cancelada'}`}>
                          {u.ativo ? 'Ativo' : 'Inativo'}
                        </span>
                      </td>
                      <td className="celula-acoes">
                        {(completo || (GERENCIAVEIS_PELO_FINANCEIRO.includes(u.perfil) && u.id !== eu?.id)) && (
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
                              outrosSetoresIds: (u.setores ?? []).map((s) => s.id).filter((id) => id !== u.setorId),
                              ativo: u.ativo,
                              secoes: u.secoes ?? padraoDe(u.perfil),
                            })
                          }
                        >
                          Editar
                        </button>
                        )}
                        {(completo || GERENCIAVEIS_PELO_FINANCEIRO.includes(u.perfil)) && u.id !== eu?.id && (
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
                  onChange={(e) => {
                    const perfil = e.target.value as PerfilNome
                    setForm({ ...form, perfil, secoes: trocarPerfil(form.secoes, padraoDe(form.perfil), padraoDe(perfil)) })
                  }}
                >
                  {PERFIS.filter((p) => completo || GERENCIAVEIS_PELO_FINANCEIRO.includes(p)).map((p) => (
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
              {/* Mais de um setor: em Meu setor a pessoa escolhe em qual está. Com a Frota e uma
                  coluna na agenda, ela tem também a Minha agenda de adesivador. */}
              {form.perfil === 'OPERACIONAL' && form.setorId && (
                <div className="campo">
                  <label>Outros setores (opcional)</label>
                  <small className="dica-campo">
                    Para quem trabalha em mais de um setor. Em "Meu setor" a pessoa escolhe em qual está.
                    Com a Frota marcada e uma coluna com o nome dela na agenda, ela vê também a Minha agenda.
                  </small>
                  <div className="lista-secoes">
                    {setores
                      .filter((s) => !s.localFisico && s.nome !== 'FINANCEIRO' && String(s.id) !== form.setorId)
                      .map((s) => (
                        <label key={s.id} className="secao-opcao">
                          <input
                            type="checkbox"
                            checked={form.outrosSetoresIds.includes(s.id)}
                            onChange={(e) =>
                              setForm({
                                ...form,
                                outrosSetoresIds: e.target.checked
                                  ? [...form.outrosSetoresIds, s.id]
                                  : form.outrosSetoresIds.filter((id) => id !== s.id),
                              })
                            }
                          />
                          <span>{rotuloSetor(s.nome)}</span>
                        </label>
                      ))}
                  </div>
                </div>
              )}
              {/* Permissões são só do Administrador. */}
              {completo && (
              <div className="campo">
                <label>Seções que acessa</label>
                <small className="dica-campo">
                  {form.perfil === 'PERSONALIZADO'
                    ? 'O Personalizado começa sem nada: marque só o que a pessoa usa (ex.: uma TV com a agenda dos adesivadores).'
                    : `Já vêm marcadas as do perfil ${rotuloPerfil(form.perfil)}. Marque ou desmarque para esta pessoa.`}
                </small>
                <div className="lista-secoes">
                  {SECOES.map(({ secao, rotulo, detalhe }) => {
                    const marcada = form.secoes.includes(secao)
                    const doPadrao = padraoDe(form.perfil).includes(secao)
                    return (
                      <label key={secao} className="secao-opcao" title={detalhe}>
                        <input
                          type="checkbox"
                          checked={marcada}
                          onChange={(e) => setForm({ ...form, secoes: marcar(form.secoes, secao, e.target.checked) })}
                        />
                        <span>
                          {rotulo}
                          {marcada && !doPadrao && <em className="secao-a-mais"> a mais</em>}
                          {!marcada && doPadrao && <em className="secao-tirada"> tirada</em>}
                        </span>
                      </label>
                    )
                  })}
                </div>
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

/** Na lista: o que foi mudado em cima do perfil (ou, no Personalizado, o que a pessoa vê). */
function ResumoDeSecoes({ usuario }: { usuario: Usuario }) {
  const nome = (s: SecaoDoSistema) => SECOES.find((x) => x.secao === s)?.rotulo ?? s
  if (usuario.perfil === 'PERSONALIZADO') {
    return <small className="resumo-secoes">{(usuario.secoes ?? []).map(nome).join(' · ') || 'nenhuma seção'}</small>
  }
  const ajustes = Object.entries(usuario.ajustesDeSecao ?? {}) as [SecaoDoSistema, boolean][]
  if (ajustes.length === 0) return null
  return (
    <small className="resumo-secoes">
      {ajustes.map(([s, liberada]) => `${liberada ? '+' : '−'} ${nome(s)}`).join(' · ')}
    </small>
  )
}

/**
 * Administração → Permissões: as seções que cada perfil traz prontas, como a matriz de
 * transição. Mudar aqui vale na hora para todo mundo do perfil; quem tem ajustes próprios
 * (no cadastro da pessoa) continua com eles por cima. O Administrador tem tudo, sempre.
 */
function PermissoesDosPerfis({ padroes, aoMudar }: { padroes: Padroes; aoMudar: (novos: Padroes) => void }) {
  const [ocupado, setOcupado] = useState(false)
  const [erro, setErro] = useState<string | null>(null)
  const perfis = PERFIS.filter((p) => p !== 'ADMIN')

  async function alternar(perfil: PerfilNome, secao: SecaoDoSistema, ligada: boolean) {
    setOcupado(true)
    setErro(null)
    try {
      const novas = marcar(padroes[perfil] ?? [], secao, ligada)
      aoMudar(await api.put<Padroes>(`/admin/perfis/${perfil}/secoes`, novas))
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível mudar a permissão.')
    } finally {
      setOcupado(false)
    }
  }

  return (
    <div className="cartao">
      <div className="titulo-secao">Permissões de cada perfil</div>
      <div className="cartao-corpo">
        <p className="subtitulo">
          O que cada perfil vê. Vale na hora para todo mundo do perfil; no cadastro de cada pessoa dá para marcar ou
          desmarcar seções só para ela. O Administrador tem todas, sempre.
        </p>
        {erro && <Aviso tipo="erro">{erro}</Aviso>}
        <div className="tabela-rolagem">
          <table className="tabela-permissoes">
            <thead>
              <tr>
                <th>Seção</th>
                {perfis.map((p) => (
                  <th key={p}>{rotuloPerfil(p)}</th>
                ))}
                <th>{rotuloPerfil('ADMIN')}</th>
              </tr>
            </thead>
            <tbody>
              {SECOES.map(({ secao, rotulo, detalhe }) => (
                <tr key={secao}>
                  <td title={detalhe}>
                    {rotulo}
                    <small className="resumo-secoes">{detalhe}</small>
                  </td>
                  {perfis.map((p) => (
                    <td key={p} className="celula-permissao">
                      <input
                        type="checkbox"
                        aria-label={`${rotulo} para ${rotuloPerfil(p)}`}
                        disabled={ocupado}
                        checked={(padroes[p] ?? []).includes(secao)}
                        onChange={(e) => alternar(p, secao, e.target.checked)}
                      />
                    </td>
                  ))}
                  <td className="celula-permissao">
                    <input type="checkbox" checked disabled aria-label={`${rotulo} para o Administrador`} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
