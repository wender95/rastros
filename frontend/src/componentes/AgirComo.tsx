import { useEffect, useState } from 'react'
import { api, tokenStorage } from '../api/client'
import type { LoginResponse, Usuario } from '../api/tipos'
import { rotuloPerfil } from '../auth/acessos'
import { esquecerSetorAtivo } from '../auth/setorAtivo'
import { esquecerSessaoAdmin, guardarSessaoAdmin, lerSessaoAdmin as lerAdmin } from '../auth/sessaoAdmin'
import { rotuloSetor } from './Ui'

/**
 * Fase de teste: o administrador **age como** outra pessoa — vê as telas dela (o setor, a
 * Minha agenda) e recebe, despacha, inicia e conclui como ela, sem precisar da senha.
 *
 * A sessão do administrador fica guardada ao lado; "Voltar" a traz de volta. Trocar de
 * pessoa passa sempre pela sessão do administrador (o servidor não deixa encadear).
 */

/**
 * A sessão do administrador guardada, só enquanto ele está agindo como alguém. Fora disso,
 * o que sobrou guardado é de uma sessão que já caiu (venceu de um dia para o outro, por
 * exemplo): usá-la deixava a lista vazia e a troca falhando.
 */
function sessaoAdminGuardada(agindo: boolean) {
  if (!agindo) {
    esquecerSessaoAdmin()
    return null
  }
  return lerAdmin()
}

/** Começa a agir como a pessoa (ou troca de pessoa) e recarrega o sistema na tela dela. */
async function agirComo(usuarioId: number, agindo: boolean) {
  const guardado = sessaoAdminGuardada(agindo)
  if (guardado) tokenStorage.gravar(guardado) // trocando de pessoa: volta ao admin antes
  let resposta: LoginResponse
  try {
    resposta = await api.post<LoginResponse>(`/admin/personificar/${usuarioId}`, {})
  } catch (erro) {
    // A troca falhou no meio: fica a sessão do administrador, e a tela mostra isso.
    if (guardado) {
      esquecerSessaoAdmin()
      esquecerSetorAtivo() // outra pessoa: o setor escolhido antes não vale
  window.location.assign('/')
    }
    throw erro
  }
  guardarSessaoAdmin(guardado ?? tokenStorage.ler() ?? '')
  tokenStorage.gravar(resposta.token)
  esquecerSetorAtivo() // outra pessoa: o setor escolhido antes não vale
  window.location.assign('/')
}

/** Volta para a sessão do administrador. */
export function voltarParaAdmin() {
  const guardado = lerAdmin()
  esquecerSessaoAdmin()
  if (guardado) tokenStorage.gravar(guardado)
  else tokenStorage.limpar()
  esquecerSetorAtivo() // outra pessoa: o setor escolhido antes não vale
  window.location.assign('/')
}

const descrever = (u: Usuario) =>
  `${u.nome} — ${u.setor ? rotuloSetor(u.setor) : rotuloPerfil(u.perfil)}`

/** As pessoas por quem dá para agir: ativas e que não são administradoras. */
function usePessoas(agindo: boolean) {
  const [pessoas, setPessoas] = useState<Usuario[]>([])
  useEffect(() => {
    // Agindo como alguém, a lista vem pela sessão guardada do administrador.
    const guardado = sessaoAdminGuardada(agindo)
    const pedido = guardado
      ? fetch('/api/admin/usuarios', { headers: { Authorization: `Bearer ${guardado}` } }).then((r) =>
          r.ok ? (r.json() as Promise<Usuario[]>) : [],
        )
      : api.get<Usuario[]>('/admin/usuarios')
    pedido
      .then((lista) =>
        setPessoas(
          lista.filter((u) => u.ativo && u.perfil !== 'ADMIN').sort((a, b) => descrever(a).localeCompare(descrever(b))),
        ),
      )
      .catch(() => setPessoas([]))
  }, [agindo])
  return pessoas
}

function Seletor({ atual, rotulo }: { atual?: number; rotulo: string }) {
  // Com "atual", o administrador já está agindo como essa pessoa (é a troca de pessoa).
  const agindo = atual !== undefined
  const pessoas = usePessoas(agindo)
  const [erro, setErro] = useState<string | null>(null)
  return (
    <>
      <select
        className="agir-como"
        value={atual ?? ''}
        title="Ver e usar o sistema como outra pessoa, sem a senha dela (fase de teste)"
        onChange={(e) => {
          const id = Number(e.target.value)
          if (!id) return
          setErro(null)
          agirComo(id, agindo).catch((err) => setErro(err instanceof Error ? err.message : 'Não foi possível.'))
        }}
      >
        <option value="">{rotulo}</option>
        {pessoas.map((u) => (
          <option key={u.id} value={u.id}>
            {descrever(u)}
          </option>
        ))}
      </select>
      {/* O motivo aparece na tela (antes ficava escondido na dica do mouse). */}
      {erro && (
        <span className="agir-como-erro" role="alert">
          {erro}
        </span>
      )}
    </>
  )
}

/** No topo, para o administrador: escolher por quem agir. */
export function SeletorAgirComo({ usuario }: { usuario: Usuario }) {
  if (usuario.perfil !== 'ADMIN' || usuario.agindoPor) return null
  return <Seletor rotulo="Agir como…" />
}

/** A faixa que avisa, em toda tela, que o administrador está agindo como outra pessoa. */
export function FaixaAgindoComo({ usuario }: { usuario: Usuario }) {
  if (!usuario.agindoPor) return null
  return (
    <div className="faixa-agindo-como" role="status">
      <span className="faixa-agindo-longa">
        Você (<strong>{usuario.agindoPor}</strong>) está agindo como <strong>{usuario.nome}</strong> ·{' '}
        {usuario.setor ? rotuloSetor(usuario.setor) : rotuloPerfil(usuario.perfil)}. O que fizer fica em nome
        dele(a).
      </span>
      {/* No celular, a mesma coisa numa linha só. */}
      <span className="faixa-agindo-curta">
        Agindo como <strong>{usuario.nome}</strong>
      </span>
      <span className="faixa-agindo-como-acoes">
        <Seletor atual={usuario.id} rotulo="Trocar de pessoa…" />
        <button className="botao botao-secundario" onClick={voltarParaAdmin}>
          Voltar para o admin
        </button>
      </span>
    </div>
  )
}
